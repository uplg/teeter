package com.htc.android.teeter

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Bundle
import android.os.PowerManager
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.htc.android.teeter.game.GameView
import com.htc.android.teeter.models.GameState
import com.htc.android.teeter.utils.GamePreferences
import com.htc.android.teeter.utils.LevelParser

class GameActivity : AppCompatActivity() {
    
    private lateinit var gameView: GameView
    private val gameState = GameState()
    private lateinit var wakeLock: PowerManager.WakeLock
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // Acquire wake lock
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
            "Teeter::GameWakeLock"
        )
        
        // Check for accelerometer
        val sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        if (sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) == null) {
            Toast.makeText(this, R.string.str_no_sensor, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        
        setContentView(R.layout.activity_game)
        
        gameView = findViewById(R.id.gameView)
        
        // Setup game callbacks
        gameView.onLevelComplete = {
            onLevelComplete()
        }
        
        gameView.onFallInHole = {
            gameState.retry()
        }
        
        // Load last played level or start from level 1
        val savedLevel = GamePreferences.getCurrentLevel(this)
        gameState.totalTime = GamePreferences.getTotalTime(this)
        gameState.totalAttempts = GamePreferences.getTotalAttempts(this)
        loadLevel(savedLevel)
    }
    
    private fun loadLevel(levelNumber: Int) {
        val level = LevelParser.loadLevel(this, levelNumber)
        if (level != null) {
            gameState.startLevel(levelNumber)
            gameView.setLevel(level)
        } else {
            Toast.makeText(this, "Level $levelNumber not found", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun showLevelTransition() {
        runOnUiThread {
            val dialogView = layoutInflater.inflate(R.layout.dialog_level_complete, null)
            
            // Set title
            dialogView.findViewById<TextView>(R.id.levelCompletedTitle).text = 
                "Level ${gameState.currentLevel} Completed"
            
            // Set level stats
            val levelTime = gameState.getLevelTime()
            val levelSeconds = (levelTime / 1000) % 60
            val levelMinutes = (levelTime / 60000) % 60
            val levelHours = (levelTime / 3600000)
            val levelTimeStr = if (levelHours > 0) {
                String.format("%d:%02d:%02d", levelHours, levelMinutes, levelSeconds)
            } else {
                String.format("%d:%02d:%02d", levelMinutes / 60, levelMinutes % 60, levelSeconds)
            }
            dialogView.findViewById<TextView>(R.id.levelTimeText).text = levelTimeStr
            dialogView.findViewById<TextView>(R.id.levelAttemptsText).text = 
                gameState.levelAttempts.toString()
            
            // Set total stats
            val totalTime = gameState.totalTime
            val totalSeconds = (totalTime / 1000) % 60
            val totalMinutes = (totalTime / 60000) % 60
            val totalHours = (totalTime / 3600000)
            val totalTimeStr = if (totalHours > 0) {
                String.format("%d:%02d:%02d", totalHours, totalMinutes, totalSeconds)
            } else {
                String.format("%d:%02d:%02d", totalMinutes / 60, totalMinutes % 60, totalSeconds)
            }
            dialogView.findViewById<TextView>(R.id.totalTimeText).text = totalTimeStr
            dialogView.findViewById<TextView>(R.id.totalAttemptsText).text = 
                gameState.totalAttempts.toString()
            
            val dialog = AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(false)
                .create()
            
            dialog.show()
            
            // Make dialog fullscreen - must be called AFTER show()
            dialog.window?.apply {
                setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT
                )
                setBackgroundDrawableResource(android.R.color.black)
                decorView.setPadding(0, 0, 0, 0)
            }
            
            // Auto-continue après 3 secondes
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
        
        // Save level score
        GamePreferences.saveLevelScore(
            this,
            gameState.currentLevel,
            gameState.getLevelTime(),
            gameState.levelAttempts
        )
        
        // Save total progress
        GamePreferences.saveTotalTime(this, gameState.totalTime)
        GamePreferences.saveTotalAttempts(this, gameState.totalAttempts)
        
        if (gameState.currentLevel >= LevelParser.getTotalLevels()) {
            // Game complete
            showGameCompleteDialog()
        } else {
            // Show transition screen
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
                Toast.makeText(this, "Error loading score screen: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    override fun onPause() {
        super.onPause()
        gameView.stopSensors()
        if (wakeLock.isHeld) {
            wakeLock.release()
        }
        
        // Save current progress when pausing
        GamePreferences.saveCurrentLevel(this, gameState.currentLevel)
        GamePreferences.saveTotalTime(this, gameState.totalTime)
        GamePreferences.saveTotalAttempts(this, gameState.totalAttempts)
    }
    
    override fun onResume() {
        super.onResume()
        gameView.startSensors()
        if (!wakeLock.isHeld) {
            wakeLock.acquire()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        if (wakeLock.isHeld) {
            wakeLock.release()
        }
    }
    
    override fun onBackPressed() {
        AlertDialog.Builder(this)
            .setTitle("Menu")
            .setMessage(R.string.str_msg_quit)
            .setPositiveButton(R.string.str_btn_yes) { _, _ ->
                super.onBackPressed()
            }
            .setNegativeButton(R.string.str_btn_no, null)
            .setNeutralButton("Reset Progress") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("Reset Progress")
                    .setMessage("Are you sure you want to reset all progress and start from Level 1?")
                    .setPositiveButton("Yes") { _, _ ->
                        GamePreferences.resetProgress(this)
                        gameState.currentLevel = 0
                        gameState.totalTime = 0
                        gameState.totalAttempts = 0
                        loadLevel(1)
                        Toast.makeText(this, "Progress reset to Level 1", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .show()
    }
}
