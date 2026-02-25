package com.htc.android.teeter.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * A utility object for managing Teeter game persistent data using Android SharedPreferences
 * This class handles saving/retrieving game progress, level scores, best records, and ranking data
 * All data is stored in private SharedPreferences to ensure data security
 */
object GamePreferences {
    // SharedPreferences file name
    private const val PREFS_NAME = "TeeterPrefs"

    // Key for current playing level number
    private const val KEY_CURRENT_LEVEL = "current_level"

    // Key for total accumulated play time across all levels (in milliseconds)
    private const val KEY_TOTAL_TIME = "total_time"

    // Key for total number of attempts across all levels
    private const val KEY_TOTAL_ATTEMPTS = "total_attempts"

    // Prefix for best time key (append level number, e.g., "best_time_level_5" for level 5)
    private const val KEY_BEST_TIME_PREFIX = "best_time_level_"

    // Prefix for best attempts key (append level number, e.g., "best_attempts_level_5" for level 5)
    private const val KEY_BEST_ATTEMPTS_PREFIX = "best_attempts_level_"

    // Prefix for level completion status (append level number, boolean value)
    private const val KEY_LEVEL_COMPLETED_PREFIX = "level_completed_"

    // Key for storing rank list data (comma-separated string of best times)
    private const val KEY_RANK_LIST = "rank_list"

    // Key for tracking if clear time has been recorded (for game completion statistics)
    private const val KEY_IS_CLEAR_TIME_RECORDED = "is_clear_time_recorded"

    // Maximum number of records to keep in the rank list (top 5 scores)
    private const val RANK_MAX_COUNT = 5

    /**
     * Retrieves the SharedPreferences instance for the Teeter game
     * Uses private mode to ensure data is only accessible by the app
     *
     * @param context Application or Activity context to access SharedPreferences
     * @return Private SharedPreferences instance for game data storage
     */
    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Saves the current level number the player is on
     *
     * @param context Context to access SharedPreferences
     * @param level Current level number (e.g., 3 for level 3)
     */
    fun saveCurrentLevel(context: Context, level: Int) {
        getPrefs(context).edit {
            putInt(KEY_CURRENT_LEVEL, level)
        }
    }

    /**
     * Retrieves the last saved current level number
     * Returns 1 as default if no level data is found (initial game state)
     *
     * @param context Context to access SharedPreferences
     * @return Current level number (default: 1)
     */
    fun getCurrentLevel(context: Context): Int {
        return getPrefs(context).getInt(KEY_CURRENT_LEVEL, 1)
    }

    /**
     * Retrieves the total accumulated play time across all completed levels
     * Time is stored in milliseconds for precision
     *
     * @param context Context to access SharedPreferences
     * @return Total play time in milliseconds (default: 0)
     */
    fun getTotalTime(context: Context): Long {
        return getPrefs(context).getLong(KEY_TOTAL_TIME, 0)
    }

    /**
     * Retrieves the total number of attempts made across all levels
     *
     * @param context Context to access SharedPreferences
     * @return Total attempt count (default: 0)
     */
    fun getTotalAttempts(context: Context): Int {
        return getPrefs(context).getInt(KEY_TOTAL_ATTEMPTS, 0)
    }

    /**
     * Checks if the overall game clear time has been recorded
     * Used to prevent duplicate recording of final completion time
     *
     * @param context Context to access SharedPreferences
     * @return True if clear time is already recorded, false otherwise (default: false)
     */
    fun isClearTimeRecorded(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_IS_CLEAR_TIME_RECORDED, false)
    }

    /**
     * Sets the flag indicating whether clear time has been recorded
     * Typically set to true after recording the final game completion time
     *
     * @param context Context to access SharedPreferences
     * @param isRecorded Boolean flag for clear time recording status
     */
    fun setClearTimeRecorded(context: Context, isRecorded: Boolean) {
        getPrefs(context).edit {
            putBoolean(KEY_IS_CLEAR_TIME_RECORDED, isRecorded)
        }
    }

    /**
     * Saves the core game state in a single transaction
     * Optimizes performance by updating multiple values in one edit operation
     *
     * @param context Context to access SharedPreferences
     * @param level Current level number
     * @param totalTime Total accumulated play time (milliseconds)
     * @param totalAttempts Total number of attempts across all levels
     */
    fun saveGameCoreState(context: Context, level: Int, totalTime: Long, totalAttempts: Int) {
        getPrefs(context).edit {
            putInt(KEY_CURRENT_LEVEL, level)
            putLong(KEY_TOTAL_TIME, totalTime)
            putInt(KEY_TOTAL_ATTEMPTS, totalAttempts)
        }
    }

    /**
     * Saves the best score (time/attempts) for a specific level
     * Only updates the best time if the new time is better (shorter) than existing record
     * Marks the level as completed regardless of score update
     *
     * @param context Context to access SharedPreferences
     * @param level Level number to save score for
     * @param time Completion time for the level (milliseconds)
     * @param attempts Number of attempts used to complete the level
     */
    fun saveLevelScore(context: Context, level: Int, time: Long, attempts: Int) {
        val currentBestTime = getLevelBestTime(context, level)

        getPrefs(context).edit {
            // Update best time only if new time is better (shorter) or no record exists
            if (currentBestTime == 0L || time < currentBestTime) {
                putLong(KEY_BEST_TIME_PREFIX + level, time)
                putInt(KEY_BEST_ATTEMPTS_PREFIX + level, attempts)
            }
            // Mark level as completed
            putBoolean(KEY_LEVEL_COMPLETED_PREFIX + level, true)
        }
    }

    /**
     * Retrieves the best (shortest) completion time for a specific level
     * Returns 0 if no record exists for the level
     *
     * @param context Context to access SharedPreferences
     * @param level Level number to retrieve best time for
     * @return Best completion time in milliseconds (0 if no record)
     */
    fun getLevelBestTime(context: Context, level: Int): Long {
        return getPrefs(context).getLong(KEY_BEST_TIME_PREFIX + level, 0)
    }

    /**
     * Resets all game progress data (excluding best scores and rank list)
     * Removes current level, total time, total attempts and resets clear time flag
     *
     * @param context Context to access SharedPreferences
     */
    fun resetProgress(context: Context) {
        getPrefs(context).edit {
            remove(KEY_CURRENT_LEVEL)
            remove(KEY_TOTAL_TIME)
            remove(KEY_TOTAL_ATTEMPTS)
            putBoolean(KEY_IS_CLEAR_TIME_RECORDED, false)
        }
    }

    /**
     * Saves the rank list (array of best times) to SharedPreferences
     * Converts the LongArray to a comma-separated string for storage
     *
     * @param context Context to access SharedPreferences
     * @param rankList Array of best times (sorted in ascending order)
     */
    fun saveRankList(context: Context, rankList: LongArray) {
        getPrefs(context).edit {
            val rankStr = rankList.joinToString(separator = ",")
            putString(KEY_RANK_LIST, rankStr)
        }
    }

    /**
     * Retrieves the rank list (array of best times) from SharedPreferences
     * Converts the stored comma-separated string back to a LongArray
     * Returns empty array if no rank data exists
     *
     * @param context Context to access SharedPreferences
     * @return Array of best times (empty if no records)
     */
    fun getRankList(context: Context): LongArray {
        val rankStr = getPrefs(context).getString(KEY_RANK_LIST, "") ?: ""
        return if (rankStr.isBlank()) {
            longArrayOf()
        } else {
            rankStr.split(",").map { it.toLong() }.toLongArray()
        }
    }

    /**
     * Adds a new score to the rank list and maintains maximum rank count
     * Sorts the combined list, keeps only top N (RANK_MAX_COUNT) scores, and saves the result
     * Scores are sorted in ascending order (shorter times = better scores)
     *
     * @param context Context to access SharedPreferences
     * @param newScore New completion time to add to rank list (milliseconds)
     * @return Updated rank list (sorted, max RANK_MAX_COUNT entries)
     */
    fun addRankScore(context: Context, newScore: Long): LongArray {
        val historyRanks = getRankList(context)
        val combinedRanks = historyRanks + newScore
        val sortedRanks = combinedRanks.sorted().toLongArray()
        val finalRanks = if (sortedRanks.size > RANK_MAX_COUNT) {
            // Keep only top N best scores (shortest times)
            sortedRanks.copyOfRange(0, RANK_MAX_COUNT)
        } else {
            sortedRanks
        }
        saveRankList(context, finalRanks)
        return finalRanks
    }
}