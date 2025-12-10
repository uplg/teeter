package com.htc.android.teeter

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.htc.android.teeter.utils.GamePreferences
import java.util.Locale

class ScoreActivity : AppCompatActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_score)
        
        // Ensure ScrollView starts at top and is focusable/scrollable immediately
        findViewById<android.widget.ScrollView>(R.id.score_scroll_view)?.apply {
            post {
                scrollTo(0, 0)
                requestFocus()
            }
            isFocusable = true
            isFocusableInTouchMode = true
        }
        
        val totalTime = intent.getLongExtra("totalTime", 0)
        val totalAttempts = intent.getIntExtra("totalAttempts", 0)
        
        val rank = GamePreferences.getRank(this)
        
        findViewById<TextView>(R.id.rank_caption).text = getString(R.string.congratulations_rank, rank)
        findViewById<TextView>(R.id.total_time_score).text = formatTime(totalTime)
        findViewById<TextView>(R.id.total_attempt_score).text = totalAttempts.toString()
        
        findViewById<Button>(R.id.btn_restart).setOnClickListener {
            GamePreferences.resetProgress(this)
            val intent = Intent(this, GameActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            finishAffinity()
        }
        
        findViewById<Button>(R.id.btn_quit).setOnClickListener {
            finishAffinity()
        }
        
        // Handle back press
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                AlertDialog.Builder(this@ScoreActivity)
                    .setTitle(R.string.menu_title)
                    .setMessage(R.string.menu_message)
                    .setPositiveButton(R.string.str_btn_quit) { _, _ ->
                        finishAffinity()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .setNeutralButton(R.string.reset_progress_button) { _, _ ->
                        AlertDialog.Builder(this@ScoreActivity)
                            .setTitle(R.string.reset_progress_title)
                            .setMessage(R.string.reset_progress_message)
                            .setPositiveButton(R.string.str_btn_yes) { _, _ ->
                                GamePreferences.resetProgress(this@ScoreActivity)
                                Toast.makeText(this@ScoreActivity, R.string.progress_reset_toast, Toast.LENGTH_SHORT).show()
                                val intent = Intent(this@ScoreActivity, GameActivity::class.java)
                                intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                                startActivity(intent)
                                finishAffinity()
                            }
                            .setNegativeButton(R.string.cancel, null)
                            .show()
                    }
                    .show()
            }
        })
    }
    
    private fun formatTime(millis: Long): String {
        val seconds = (millis / 1000) % 60
        val minutes = (millis / 60000) % 60
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
}
