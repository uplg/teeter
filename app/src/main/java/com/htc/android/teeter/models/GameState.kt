package com.htc.android.teeter.models

/**
 * Data model that tracks and manages the game's runtime state and statistics
 * This class holds all the key metrics for the Teeter game, including level progress,
 * time tracking, and attempt counts for both current level and overall game session.
 *
 * @property currentLevel The number of the level the player is currently playing (default: 1)
 * @property totalTime Total accumulated play time across all completed levels (in milliseconds)
 * @property totalAttempts Total number of attempts made across all levels (cumulative)
 * @property levelAttempts Number of attempts made on the current level (resets on level change)
 * @property levelStartTime Timestamp (in milliseconds) when the current level was started
 */
data class GameState(
    var currentLevel: Int = 1,
    var totalTime: Long = 0,
    var totalAttempts: Int = 0,
    var levelAttempts: Int = 0,
    var levelStartTime: Long = 0,
    var currentLevelFinalTime: Long = 0
) {
    /**
     * Initializes and resets state for starting a new level
     * Resets level-specific attempt count and records the start time for the new level.
     *
     * @param level The number of the level to start (e.g., 2 for level 2)
     */
    fun startLevel(level: Int) {
        currentLevel = level
        levelAttempts = 0
        levelStartTime = System.currentTimeMillis()
        currentLevelFinalTime = 0
    }

    /**
     * Calculates the elapsed time for the current level
     * Returns the time difference (in milliseconds) between now and when the level started.
     *
     * @return Elapsed time in milliseconds for the current level
     */
    fun getLevelTime(): Long {
        return if (currentLevelFinalTime > 0) {
            currentLevelFinalTime
        } else {
            System.currentTimeMillis() - levelStartTime
        }
    }

    /**
     * Updates game statistics when a level is successfully completed
     * Adds the current level's elapsed time to totalTime and merges levelAttempts into totalAttempts.
     * This method should be called when the player successfully finishes a level.
     */
    fun completeLevel() {
        currentLevelFinalTime = System.currentTimeMillis() - levelStartTime
        totalTime += currentLevelFinalTime
        totalAttempts += levelAttempts
    }

    /**
     * Records a retry attempt for the current level
     * Increments the level attempt counter and resets the level start time to the current timestamp.
     * This method should be called when the player restarts the current level (e.g., after failing).
     */
    fun retry() {
        levelAttempts++
        levelStartTime = System.currentTimeMillis()
        currentLevelFinalTime = 0
    }
}