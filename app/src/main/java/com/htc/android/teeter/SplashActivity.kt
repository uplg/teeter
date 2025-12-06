package com.htc.android.teeter

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {
    
    private val splashDelay = 2000L
    private var keepSplashOnScreen = true
    
    override fun onCreate(savedInstanceState: Bundle?) {
        // Install splash screen for Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val splashScreen = installSplashScreen()
            splashScreen.setKeepOnScreenCondition { keepSplashOnScreen }
        }
        
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        
        // Start game after delay
        Handler(Looper.getMainLooper()).postDelayed({
            keepSplashOnScreen = false
            startActivity(Intent(this, GameActivity::class.java))
            finish()
        }, splashDelay)
    }
}
