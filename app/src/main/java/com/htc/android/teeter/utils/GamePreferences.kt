package com.htc.android.teeter.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

object GamePreferences {
    private const val PREFS_NAME = "TeeterPrefs"
    private const val KEY_CURRENT_LEVEL = "current_level"
    private const val KEY_TOTAL_TIME = "total_time"
    private const val KEY_TOTAL_ATTEMPTS = "total_attempts"
    private const val KEY_BEST_TIME_PREFIX = "best_time_level_"
    private const val KEY_BEST_ATTEMPTS_PREFIX = "best_attempts_level_"
    private const val KEY_LEVEL_COMPLETED_PREFIX = "level_completed_"
    
    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    fun saveCurrentLevel(context: Context, level: Int) {
        getPrefs(context).edit {
            putInt(KEY_CURRENT_LEVEL, level)
        }
    }
    
    fun getCurrentLevel(context: Context): Int {
        return getPrefs(context).getInt(KEY_CURRENT_LEVEL, 1)
    }
    
    fun saveTotalTime(context: Context, time: Long) {
        getPrefs(context).edit {
            putLong(KEY_TOTAL_TIME, time)
        }
    }
    
    fun getTotalTime(context: Context): Long {
        return getPrefs(context).getLong(KEY_TOTAL_TIME, 0)
    }
    
    fun saveTotalAttempts(context: Context, attempts: Int) {
        getPrefs(context).edit {
            putInt(KEY_TOTAL_ATTEMPTS, attempts)
        }
    }
    
    fun getTotalAttempts(context: Context): Int {
        return getPrefs(context).getInt(KEY_TOTAL_ATTEMPTS, 0)
    }
    
    fun saveLevelScore(context: Context, level: Int, time: Long, attempts: Int) {
        val currentBestTime = getLevelBestTime(context, level)
        
        getPrefs(context).edit {
            // Save if it's a new record or first completion
            if (currentBestTime == 0L || time < currentBestTime) {
                putLong(KEY_BEST_TIME_PREFIX + level, time)
                putInt(KEY_BEST_ATTEMPTS_PREFIX + level, attempts)
            }
            
            putBoolean(KEY_LEVEL_COMPLETED_PREFIX + level, true)
        }
    }
    
    fun getLevelBestTime(context: Context, level: Int): Long {
        return getPrefs(context).getLong(KEY_BEST_TIME_PREFIX + level, 0)
    }
    
    fun getLevelBestAttempts(context: Context, level: Int): Int {
        return getPrefs(context).getInt(KEY_BEST_ATTEMPTS_PREFIX + level, 0)
    }
    
    fun isLevelCompleted(context: Context, level: Int): Boolean {
        return getPrefs(context).getBoolean(KEY_LEVEL_COMPLETED_PREFIX + level, false)
    }
    
    fun getRank(context: Context): String {
        val totalLevels = LevelParser.getTotalLevels()
        val completedLevels = (1..totalLevels).count { isLevelCompleted(context, it) }
        val totalTime = getTotalTime(context)
        val totalAttempts = getTotalAttempts(context)
        
        return when {
            completedLevels < totalLevels -> "Incomplete"
            totalTime < 600000 && totalAttempts < 50 -> "Master" // < 10 min, < 50 attempts
            totalTime < 900000 && totalAttempts < 100 -> "Expert" // < 15 min, < 100 attempts
            totalTime < 1200000 && totalAttempts < 150 -> "Advanced" // < 20 min, < 150 attempts
            totalTime < 1800000 && totalAttempts < 200 -> "Intermediate" // < 30 min, < 200 attempts
            else -> "Beginner"
        }
    }
    
    fun resetProgress(context: Context) {
        getPrefs(context).edit {
            clear()
        }
    }
}
