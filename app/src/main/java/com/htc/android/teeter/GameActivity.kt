package com.htc.android.teeter

import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.htc.android.teeter.game.GameView
import com.htc.android.teeter.models.GameState
import com.htc.android.teeter.utils.GamePreferences
import com.htc.android.teeter.utils.LevelParser
import java.util.Locale

class GameActivity : AppCompatActivity() {
    
    private lateinit var gameView: GameView
    private val gameState = GameState()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Enable edge-to-edge immersive mode (hide system bars)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        val sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        if (sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) == null) {
            Toast.makeText(this, R.string.str_no_sensor, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        
        setContentView(R.layout.activity_game)
        
        // Hide system bars for immersive full-screen experience
        hideSystemBars()
        
        gameView = findViewById(R.id.gameView)
        gameView.keepScreenOn = true
        
        // Setup game callbacks
        gameView.onLevelComplete = {
            onLevelComplete()
        }
        
        gameView.onFallInHole = {
            gameState.retry()
        }
        
        // DEBUG: Long press on GameView to skip to last level
        // gameView.setOnLongClickListener {
        //     AlertDialog.Builder(this)
        //         .setTitle("Debug")
        //         .setMessage("Skip to last level (32)?")
        //         .setPositiveButton("Yes") { _, _ ->
        //             GamePreferences.saveCurrentLevel(this, 32)
        //             loadLevel(32)
        //             Toast.makeText(this, "Jumped to Level 32", Toast.LENGTH_SHORT).show()
        //         }
        //         .setNegativeButton("Cancel", null)
        //         .show()
        //     true
        // }
        
        // Load last played level or start from level 1
        val savedLevel = GamePreferences.getCurrentLevel(this)
        gameState.totalTime = GamePreferences.getTotalTime(this)
        gameState.totalAttempts = GamePreferences.getTotalAttempts(this)
        loadLevel(savedLevel)
        
        // Handle back press
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                AlertDialog.Builder(this@GameActivity)
                    .setTitle(R.string.menu_title)
                    .setMessage(R.string.str_msg_quit)
                    .setPositiveButton(R.string.str_btn_yes) { _, _ ->
                        finish()
                    }
                    .setNegativeButton(R.string.str_btn_no, null)
                    .setNeutralButton(R.string.reset_progress_button) { _, _ ->
                        AlertDialog.Builder(this@GameActivity)
                            .setTitle(R.string.reset_progress_title)
                            .setMessage(R.string.reset_progress_message)
                            .setPositiveButton(R.string.yes) { _, _ ->
                                GamePreferences.resetProgress(this@GameActivity)
                                gameState.currentLevel = 0
                                gameState.totalTime = 0
                                gameState.totalAttempts = 0
                                loadLevel(1)
                                Toast.makeText(this@GameActivity, R.string.progress_reset_toast, Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton(R.string.cancel, null)
                            .show()
                    }
                    .show()
            }
        })
    }
    
    private fun loadLevel(levelNumber: Int) {
        val level = LevelParser.loadLevel(this, levelNumber)
        if (level != null) {
            gameState.startLevel(levelNumber)
            gameView.setLevel(level)
        } else {
            Toast.makeText(this, getString(R.string.level_not_found, levelNumber), Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun showLevelTransition() {
        runOnUiThread {
            val dialogView = layoutInflater.inflate(R.layout.dialog_level_complete, null)
            
            dialogView.findViewById<TextView>(R.id.levelCompletedTitle).text = 
                getString(R.string.level_completed, gameState.currentLevel)
            
            val levelTime = gameState.getLevelTime()
            val levelSeconds = (levelTime / 1000) % 60
            val levelMinutes = (levelTime / 60000) % 60
            val levelHours = (levelTime / 3600000)
            val levelTimeStr = String.format(Locale.getDefault(), "%d:%02d:%02d", levelHours, levelMinutes, levelSeconds)
            dialogView.findViewById<TextView>(R.id.levelTimeText).text = levelTimeStr
            dialogView.findViewById<TextView>(R.id.levelAttemptsText).text = 
                gameState.levelAttempts.toString()
            
            // Set total stats
            val totalTime = gameState.totalTime
            val totalSeconds = (totalTime / 1000) % 60
            val totalMinutes = (totalTime / 60000) % 60
            val totalHours = (totalTime / 3600000)
            val totalTimeStr = String.format(Locale.getDefault(), "%d:%02d:%02d", totalHours, totalMinutes, totalSeconds)
            dialogView.findViewById<TextView>(R.id.totalTimeText).text = totalTimeStr
            dialogView.findViewById<TextView>(R.id.totalAttemptsText).text = 
                gameState.totalAttempts.toString()
            
            val dialog = AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(false)
                .create()
            
            dialog.show()
            
            dialog.window?.apply {
                setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT
                )
                setBackgroundDrawableResource(android.R.color.black)
                decorView.setPadding(0, 0, 0, 0)
            }
            
            dialogView.postDelayed({
                if (dialog.isShowing) {
                    dialog.dismiss()
                    val nextLevel = gameState.currentLevel + 1
                    GamePreferences.saveCurrentLevel(this, nextLevel)
                    loadLevel(nextLevel)
                }
            }, 3000)
        }
    }
    
    private fun onLevelComplete() {
        gameState.completeLevel()
        
        GamePreferences.saveLevelScore(
            this,
            gameState.currentLevel,
            gameState.getLevelTime(),
            gameState.levelAttempts
        )
        
        GamePreferences.saveTotalTime(this, gameState.totalTime)
        GamePreferences.saveTotalAttempts(this, gameState.totalAttempts)
        
        if (gameState.currentLevel >= LevelParser.getTotalLevels()) {
            showGameCompleteDialog()
        } else {
            showLevelTransition()
        }
    }
    
    private fun showGameCompleteDialog() {
        runOnUiThread {
            try {
                val intent = Intent(this, ScoreActivity::class.java)
                intent.putExtra("totalTime", gameState.totalTime)
                intent.putExtra("totalAttempts", gameState.totalAttempts)
                startActivity(intent)
                finish()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, getString(R.string.error_loading_score, e.message ?: "Unknown error"), Toast.LENGTH_LONG).show()
            }
        }
    }
    
    override fun onPause() {
        super.onPause()
        gameView.stopSensors()
        
        GamePreferences.saveCurrentLevel(this, gameState.currentLevel)
        GamePreferences.saveTotalTime(this, gameState.totalTime)
        GamePreferences.saveTotalAttempts(this, gameState.totalAttempts)
    }
    
    override fun onResume() {
        super.onResume()
        hideSystemBars()
        gameView.startSensors()
    }
    
    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
    }
}
