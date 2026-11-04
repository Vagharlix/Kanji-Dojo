package ua.syt0r.kanji.core.user_data.database.migration

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import kotlinx.datetime.Instant
import org.jetbrains.compose.resources.getString
import ua.syt0r.kanji.Res
import ua.syt0r.kanji.core.logger.Logger
import ua.syt0r.kanji.core.srs.SrsCardKey
import ua.syt0r.kanji.core.srs.fsrs.FsrsCardParams
import ua.syt0r.kanji.core.srs.fsrs.FsrsHistoryReplay
import ua.syt0r.kanji.core.srs.fsrs.FsrsReviewLog
import ua.syt0r.kanji.core.user_data.database.DatabaseMigrationState
import ua.syt0r.kanji.core.user_data.database.DatabaseMigrationState.Running.Progress
import ua.syt0r.kanji.core.user_data.database.UserDataDatabaseContract
import ua.syt0r.kanji.core.user_data.database.updateState
import ua.syt0r.kanji.migration_dialog_fsrs7_recalculating
import ua.syt0r.kanji.migration_dialog_loading_data
import ua.syt0r.kanji.migration_dialog_saving_changes

/**
 * Switch from FSRS-5 to FSRS-7: recalculates every card's memory state (long-term
 * stability, short-term stability, difficulty) by replaying its full review history with
 * FSRS-7. Due dates (last review + interval) are kept as they are, so switching algorithms
 * doesn't suddenly move existing reviews around; new intervals come from FSRS-7 from the
 * next review on. Lapse and repeat counters are recounted from history.
 */
class UserDataDatabaseMigrationAfter12(
    private val migrationObservable: UserDataDatabaseContract.MigrationObservable
) : UserDataDatabaseContract.Migration {

    override val version: Long = 12

    private class HistoryRow(
        val key: String,
        val practiceType: Long,
        val timestamp: Instant,
        val grade: Int
    )

    override suspend fun execute(driver: SqlDriver) {
        migrationObservable.updateState(getString(Res.string.migration_dialog_loading_data))

        val existingCards: Set<SrsCardKey> = driver.executeQuery(
            identifier = null,
            sql = "SELECT key, practice_type FROM fsrs_card;",
            mapper = {
                val set = mutableSetOf<SrsCardKey>()
                while (it.next().value) set.add(SrsCardKey(it.getString(0)!!, it.getLong(1)!!))
                QueryResult.Value(set)
            },
            parameters = 0
        ).value

        val history: Map<SrsCardKey, List<FsrsReviewLog>> = driver.executeQuery(
            identifier = null,
            sql = "SELECT key, practice_type, timestamp, grade FROM review_history;",
            mapper = {
                val list = mutableListOf<HistoryRow>()
                while (it.next().value) {
                    list.add(
                        HistoryRow(
                            key = it.getString(0)!!,
                            practiceType = it.getLong(1)!!,
                            timestamp = Instant.fromEpochMilliseconds(it.getLong(2)!!),
                            grade = it.getLong(3)!!.toInt()
                        )
                    )
                }
                QueryResult.Value(list)
            },
            parameters = 0
        ).value
            .groupBy(
                keySelector = { SrsCardKey(it.key, it.practiceType) },
                valueTransform = { FsrsReviewLog(it.timestamp, it.grade) }
            )

        val replay = FsrsHistoryReplay()
        val cardsToUpdate = existingCards.filter { history.containsKey(it) }

        cardsToUpdate.forEachIndexed { index, key ->
            if (index % 50 == 0) {
                migrationObservable.updateState(
                    message = getString(Res.string.migration_dialog_fsrs7_recalculating),
                    progress = Progress(index, cardsToUpdate.size)
                )
            }

            val replayed = replay.replay(history.getValue(key))
            val card = replayed.lastOrNull()?.cardAfter ?: return@forEachIndexed
            val params = card.params as FsrsCardParams.Existing

            driver.execute(
                identifier = null,
                sql = """
                    UPDATE fsrs_card
                    SET stability = ?, short_stability = ?, difficulty = ?, lapses = ?, repeats = ?
                    WHERE key = ? AND practice_type = ?;
                """.trimIndent(),
                parameters = 7,
                binders = {
                    bindDouble(0, params.stability)
                    bindDouble(1, params.shortStability)
                    bindDouble(2, params.difficulty)
                    bindLong(3, card.lapses.toLong())
                    bindLong(4, card.repeats.toLong())
                    bindString(5, key.itemKey)
                    bindLong(6, key.practiceType)
                }
            )
        }

        migrationObservable.updateState(getString(Res.string.migration_dialog_saving_changes))

        // Cards without any history: keep FSRS-5 stability as the long-term stability and
        // use it for the short-term one as well
        driver.execute(
            identifier = null,
            sql = "UPDATE fsrs_card SET short_stability = stability WHERE short_stability < 0;",
            parameters = 0
        )

        Logger.d("FSRS-7 migration: recalculated ${cardsToUpdate.size} of ${existingCards.size} cards")
        migrationObservable.updateState(DatabaseMigrationState.Idle)
    }

}
