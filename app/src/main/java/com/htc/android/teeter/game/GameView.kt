package com.htc.android.teeter.game

import android.content.Context
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.core.graphics.scale
import androidx.core.graphics.withSave
import com.htc.android.teeter.R
import com.htc.android.teeter.models.Level
import kotlin.math.abs
import kotlin.math.sqrt

class GameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback, SensorEventListener {

    private var gameThread: GameThread? = null
    
    // Game state
    private var level: Level? = null
    private var ballX = 0f
    private var ballY = 0f
    private var ballVelocityX = 0f
    private var ballVelocityY = 0f
    private val baseBallRadius = 12f
    private var ballRadius = 12f
    
    // Animation state
    private var isAnimating = false
    private var animationType = AnimationType.NONE
    private var animationProgress = 0f
    private var animationStartX = 0f
    private var animationStartY = 0f
    private var animationTargetX = 0f
    private var animationTargetY = 0f
    private var animationStartTime = 0L
    private val animationDuration = 500L // ms
    private var levelCompleted = false
    private var levelCompletedTriggered = false
    
    enum class AnimationType {
        NONE, HOLE_FALL, GOAL_SUCCESS
    }
    
    // Sensors
    private val sensorManager: SensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    
    // Sound & Vibration
    private val soundPool: SoundPool
    private var holeSound = 0
    private var levelCompleteSound = 0
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vibratorManager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    
    // Bitmaps
    private var ballBitmap: Bitmap? = null
    private var holeBitmap: Bitmap? = null
    private var endBitmap: Bitmap? = null
    private var wallBitmap: Bitmap? = null
    private var mazeBitmap: Bitmap? = null
    private var shadowBitmap: Bitmap? = null
    private val holeAnimFrames = mutableListOf<Bitmap>()
    private val endAnimFrames = mutableListOf<Bitmap>()
    
    // Game callbacks
    var onLevelComplete: (() -> Unit)? = null
    var onFallInHole: (() -> Unit)? = null
    
    // Scale factor for adapting original coordinates
    private var scaleX = 1f
    private var scaleY = 1f
    private val originalWidth = 800f
    private val originalHeight = 480f
    
    init {
        holder.addCallback(this)
        setZOrderOnTop(false)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        isFocusable = true
        
        // Initialize sound pool
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder()
            .setMaxStreams(5)
            .setAudioAttributes(audioAttributes)
            .build()
            
        loadResources()
    }
    
    private fun loadResources() {
        try {
            val options = BitmapFactory.Options().apply {
                inScaled = false
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inPremultiplied = true
            }
            ballBitmap = BitmapFactory.decodeResource(resources, R.drawable.ball, options)
            holeBitmap = BitmapFactory.decodeResource(resources, R.drawable.hole, options)
            endBitmap = BitmapFactory.decodeResource(resources, R.drawable.end, options)
            wallBitmap = BitmapFactory.decodeResource(resources, R.drawable.wall, options)
            mazeBitmap = BitmapFactory.decodeResource(resources, R.drawable.maze, options)
            shadowBitmap = BitmapFactory.decodeResource(resources, R.drawable.shadow, options)
            
            // Load hole animation frames (001 to 020)
            for (i in 1..20) {
                val resName = "hole_anim_%03d".format(i)
                val resId = resources.getIdentifier(resName, "drawable", context.packageName)
                if (resId != 0) {
                    holeAnimFrames.add(BitmapFactory.decodeResource(resources, resId, options))
                }
            }
            
            // Load and split end animation sprite (3200x100 = 32 frames of 100x100)
            val endAnimSprite = BitmapFactory.decodeResource(resources, R.drawable.end_anim, options)
            val frameWidth = 100
            val frameHeight = 100
            val frameCount = endAnimSprite.width / frameWidth
            for (i in 0 until frameCount) {
                val frameBitmap = Bitmap.createBitmap(endAnimSprite, i * frameWidth, 0, frameWidth, frameHeight)
                endAnimFrames.add(frameBitmap)
            }
            
            holeSound = soundPool.load(context, R.raw.hole, 1)
            levelCompleteSound = soundPool.load(context, R.raw.level_complete, 1)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    fun setLevel(newLevel: Level) {
        level = newLevel
        levelCompleted = false
        levelCompletedTriggered = false
        isAnimating = false
        resetBall()
    }
    
    private fun resetBall() {
        level?.let {
            ballX = it.beginX * scaleX
            ballY = it.beginY * scaleY
            ballVelocityX = 0f
            ballVelocityY = 0f
        }
    }
    
    fun startSensors() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }
    
    fun stopSensors() {
        sensorManager.unregisterListener(this)
    }
    
    override fun surfaceCreated(holder: SurfaceHolder) {
        gameThread = GameThread(holder)
        gameThread?.running = true
        gameThread?.start()
        startSensors()
    }
    
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        scaleX = width / originalWidth
        scaleY = height / originalHeight
        // Scale ball radius proportionally to screen size
        val avgScale = (scaleX + scaleY) / 2f
        ballRadius = baseBallRadius * avgScale
        resetBall()
    }
    
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        var retry = true
        gameThread?.running = false
        stopSensors()
        while (retry) {
            try {
                gameThread?.join()
                retry = false
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
        }
    }
    
    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            // Apply acceleration for landscape orientation
            val accelX = event.values[1]
            val accelY = event.values[0]
            
            ballVelocityX += accelX * 0.5f
            ballVelocityY += accelY * 0.5f
            
            // Apply friction
            ballVelocityX *= 0.98f
            ballVelocityY *= 0.98f
            
            // Limit velocity
            val maxVelocity = 20f
            if (abs(ballVelocityX) > maxVelocity) ballVelocityX = maxVelocity * ballVelocityX / abs(ballVelocityX)
            if (abs(ballVelocityY) > maxVelocity) ballVelocityY = maxVelocity * ballVelocityY / abs(ballVelocityY)
        }
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    
    private fun update() {
        level ?: return
        
        // Stop all updates if level is completed
        if (levelCompleted) return
        
        // Handle animations
        if (isAnimating) {
            updateAnimation()
            return
        }
        
        // Update ball position
        ballX += ballVelocityX
        ballY += ballVelocityY
        
        // Check wall collisions
        checkWallCollisions()
        
        // Check holes
        val holeResult = checkHoleCollisions()
        if (holeResult.first) {
            startAnimation(AnimationType.HOLE_FALL, holeResult.second.first, holeResult.second.second)
            return
        }
        
        // Check goal
        val goalResult = checkGoal()
        if (goalResult.first) {
            startAnimation(AnimationType.GOAL_SUCCESS, goalResult.second.first, goalResult.second.second)
            return
        }
    }
    
    private fun startAnimation(type: AnimationType, targetX: Float, targetY: Float) {
        // Prevent starting a new animation if one is already running
        if (isAnimating) return
        
        isAnimating = true
        animationType = type
        animationStartX = ballX
        animationStartY = ballY
        animationTargetX = targetX
        animationTargetY = targetY
        animationStartTime = System.currentTimeMillis()
        animationProgress = 0f
        
        // Stop physics updates during animation
        ballVelocityX = 0f
        ballVelocityY = 0f
        
        // Play sound
        when (type) {
            AnimationType.HOLE_FALL -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(300)
                }
                soundPool.play(holeSound, 1f, 1f, 1, 0, 1f)
            }
            AnimationType.GOAL_SUCCESS -> {
                soundPool.play(levelCompleteSound, 1f, 1f, 1, 0, 1f)
            }
            else -> {}
        }
    }
    
    private fun updateAnimation() {
        val elapsed = System.currentTimeMillis() - animationStartTime
        animationProgress = (elapsed.toFloat() / animationDuration).coerceIn(0f, 1f)
        
        // Move ball toward target only for HOLE_FALL
        if (animationType == AnimationType.HOLE_FALL) {
            val easedProgress = animationProgress * animationProgress
            ballX = animationStartX + (animationTargetX - animationStartX) * easedProgress
            ballY = animationStartY + (animationTargetY - animationStartY) * easedProgress
        } else if (animationType == AnimationType.GOAL_SUCCESS) {
            // Keep ball at goal position and hidden
            ballX = animationTargetX
            ballY = animationTargetY
        }
        
        // Animation complete
        if (animationProgress >= 1f) {
            isAnimating = false
            animationProgress = 0f
            val completedType = animationType
            animationType = AnimationType.NONE
            
            // Execute callbacks AFTER resetting animation state
            when (completedType) {
                AnimationType.HOLE_FALL -> {
                    resetBall()
                    post { onFallInHole?.invoke() }
                }
                AnimationType.GOAL_SUCCESS -> {
                    if (!levelCompletedTriggered) {
                        levelCompletedTriggered = true
                        levelCompleted = true
                        post { onLevelComplete?.invoke() }
                    }
                }
                else -> {}
            }
        }
    }
    
    private fun checkWallCollisions() {
        level?.walls?.forEach { wall ->
            val left = wall.left * scaleX
            val top = wall.top * scaleY
            val right = wall.right * scaleX
            val bottom = wall.bottom * scaleY
            
            // Simple rectangle collision
            if (ballX + ballRadius > left && ballX - ballRadius < right &&
                ballY + ballRadius > top && ballY - ballRadius < bottom) {
                
                // Determine collision side and adjust
                val overlapLeft = ballX + ballRadius - left
                val overlapRight = right - (ballX - ballRadius)
                val overlapTop = ballY + ballRadius - top
                val overlapBottom = bottom - (ballY - ballRadius)
                
                val minOverlap = minOf(overlapLeft, overlapRight, overlapTop, overlapBottom)
                
                when (minOverlap) {
                    overlapLeft -> {
                        ballX = left - ballRadius
                        ballVelocityX = -ballVelocityX * 0.5f
                    }
                    overlapRight -> {
                        ballX = right + ballRadius
                        ballVelocityX = -ballVelocityX * 0.5f
                    }
                    overlapTop -> {
                        ballY = top - ballRadius
                        ballVelocityY = -ballVelocityY * 0.5f
                    }
                    overlapBottom -> {
                        ballY = bottom + ballRadius
                        ballVelocityY = -ballVelocityY * 0.5f
                    }
                }
            }
        }
        
        // Screen boundaries
        if (ballX - ballRadius < 0) {
            ballX = ballRadius
            ballVelocityX = -ballVelocityX * 0.5f
        }
        if (ballX + ballRadius > width) {
            ballX = width - ballRadius
            ballVelocityX = -ballVelocityX * 0.5f
        }
        if (ballY - ballRadius < 0) {
            ballY = ballRadius
            ballVelocityY = -ballVelocityY * 0.5f
        }
        if (ballY + ballRadius > height) {
            ballY = height - ballRadius
            ballVelocityY = -ballVelocityY * 0.5f
        }
    }
    
    private fun checkHoleCollisions(): Pair<Boolean, Pair<Float, Float>> {
        level?.holes?.forEach { hole ->
            val holeX = hole.x * scaleX
            val holeY = hole.y * scaleY
            val distance = sqrt((ballX - holeX) * (ballX - holeX) + (ballY - holeY) * (ballY - holeY))
            val avgScale = (scaleX + scaleY) / 2f
            val holeRadius = 20f * avgScale // Scaled hole detection radius
            if (distance < ballRadius + holeRadius) {
                return Pair(true, Pair(holeX, holeY))
            }
        }
        return Pair(false, Pair(0f, 0f))
    }
    
    private fun checkGoal(): Pair<Boolean, Pair<Float, Float>> {
        level?.let {
            val endX = it.endX * scaleX
            val endY = it.endY * scaleY
            val distance = sqrt((ballX - endX) * (ballX - endX) + (ballY - endY) * (ballY - endY))
            val avgScale = (scaleX + scaleY) / 2f
            val goalRadius = 25f * avgScale // Scaled goal detection radius
            if (distance < ballRadius + goalRadius) {
                return Pair(true, Pair(endX, endY))
            }
        }
        return Pair(false, Pair(0f, 0f))
    }
    
    private fun drawGame(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        
        level ?: return
        
        // Draw maze background
        mazeBitmap?.let {
            val scaledBitmap = it.scale(width, height)
            val mazePaint = Paint().apply {
                isAntiAlias = true
                isFilterBitmap = true
            }
            canvas.drawBitmap(scaledBitmap, 0f, 0f, mazePaint)
        }
        
        // Draw walls with 3D beveled effect
        val wallPaint = Paint().apply {
            color = Color.rgb(80, 80, 80)
            style = Paint.Style.FILL
            isAntiAlias = true
            setShadowLayer(6f, 3f, 3f, Color.argb(120, 0, 0, 0))
        }
        val highlightPaint = Paint().apply {
            color = Color.argb(100, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }
        val shadowPaint = Paint().apply {
            color = Color.argb(100, 0, 0, 0)
            style = Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }
        
        level?.walls?.forEach { wall ->
            val left = wall.left * scaleX
            val top = wall.top * scaleY
            val right = wall.right * scaleX
            val bottom = wall.bottom * scaleY
            
            // Draw main wall surface
            canvas.drawRect(left, top, right, bottom, wallPaint)
            
            // Draw highlight on top and left edges
            canvas.drawLine(left, top, right, top, highlightPaint)
            canvas.drawLine(left, top, left, bottom, highlightPaint)
            
            // Draw shadow on bottom and right edges
            canvas.drawLine(left, bottom, right, bottom, shadowPaint)
            canvas.drawLine(right, top, right, bottom, shadowPaint)
        }
        
        // Draw holes
        level?.holes?.forEach { hole ->
            val holeX = hole.x * scaleX
            val holeY = hole.y * scaleY
            holeBitmap?.let {
                val holePaint = Paint().apply {
                    isAntiAlias = true
                    isFilterBitmap = true
                }
                val avgScale = (scaleX + scaleY) / 2f
                canvas.withSave {
                    translate(holeX, holeY)
                    scale(avgScale, avgScale)
                    drawBitmap(it, -it.width / 2f, -it.height / 2f, holePaint)
                }
            }
        }
        
        // Draw goal
        level?.let {
            val endX = it.endX * scaleX
            val endY = it.endY * scaleY
            
            // Draw goal bitmap
            endBitmap?.let { bitmap ->
                val goalPaint = Paint().apply {
                    isAntiAlias = true
                    isFilterBitmap = true
                }
                val avgScale = (scaleX + scaleY) / 2f
                canvas.withSave {
                    translate(endX, endY)
                    scale(avgScale, avgScale)
                    drawBitmap(bitmap, -bitmap.width / 2f, -bitmap.height / 2f, goalPaint)
                }
            }
            
            // Draw goal animation on top if active
            if (isAnimating && animationType == AnimationType.GOAL_SUCCESS && endAnimFrames.isNotEmpty()) {
                val frameIndex = (animationProgress * endAnimFrames.size).toInt()
                    .coerceIn(0, endAnimFrames.size - 1)
                val frame = endAnimFrames[frameIndex]
                
                val animPaint = Paint().apply {
                    isAntiAlias = true
                    isFilterBitmap = true
                }
                val avgScale = (scaleX + scaleY) / 2f
                // Match the size of the goal/end bitmap
                val animScale = (endBitmap?.width?.toFloat() ?: 100f) / frame.width
                canvas.withSave {
                    translate(endX, endY)
                    scale(avgScale * animScale, avgScale * animScale)
                    drawBitmap(frame, -frame.width / 2f, -frame.height / 2f, animPaint)
                }
            }
        }
        
        // Draw ball or animation
        if (isAnimating) {
            when (animationType) {
                AnimationType.HOLE_FALL -> {
                    // Draw hole animation frames (scaled)
                    if (holeAnimFrames.isNotEmpty()) {
                        val frameIndex = (animationProgress * holeAnimFrames.size).toInt()
                            .coerceIn(0, holeAnimFrames.size - 1)
                        val frame = holeAnimFrames[frameIndex]
                        
                        val framePaint = Paint().apply {
                            isAntiAlias = true
                            isFilterBitmap = true
                        }
                        val avgScale = (scaleX + scaleY) / 2f
                        // Scale to match ball size approximately
                        val animScale = (ballRadius * 2f) / frame.width
                        canvas.withSave {
                            translate(ballX, ballY)
                            scale(avgScale * animScale, avgScale * animScale)
                            drawBitmap(frame, -frame.width / 2f, -frame.height / 2f, framePaint)
                        }
                    }
                }
                AnimationType.GOAL_SUCCESS -> {
                    // Ball is hidden during goal animation (animation plays at goal position)
                }
                else -> {
                    // Draw normal ball if animation type is NONE (shouldn't happen)
                    ballBitmap?.let { ball ->
                        val ballPaint = Paint().apply {
                            isAntiAlias = true
                            isFilterBitmap = true
                        }
                        val avgScale = (scaleX + scaleY) / 2f
                        canvas.withSave {
                            translate(ballX, ballY)
                            scale(avgScale, avgScale)
                            drawBitmap(ball, -ball.width / 2f, -ball.height / 2f, ballPaint)
                        }
                    }
                }
            }
        } else {
            // Draw normal ball
            ballBitmap?.let { ball ->
                val ballPaint = Paint().apply {
                    isAntiAlias = true
                    isFilterBitmap = true
                }
                val avgScale = (scaleX + scaleY) / 2f
                canvas.withSave {
                    translate(ballX, ballY)
                    scale(avgScale, avgScale)
                    drawBitmap(ball, -ball.width / 2f, -ball.height / 2f, ballPaint)
                }
            }
        }
    }
    
    inner class GameThread(private val surfaceHolder: SurfaceHolder) : Thread() {
        var running = false
        private val targetFPS = 60
        private val targetTime = 1000 / targetFPS
        
        override fun run() {
            while (running) {
                val startTime = System.currentTimeMillis()
                var canvas: Canvas? = null
                
                try {
                    canvas = surfaceHolder.lockCanvas()
                    synchronized(surfaceHolder) {
                        canvas?.let {
                            update()
                            drawGame(it)
                        }
                    }
                } finally {
                    canvas?.let {
                        surfaceHolder.unlockCanvasAndPost(it)
                    }
                }
                
                val elapsed = System.currentTimeMillis() - startTime
                val waitTime = targetTime - elapsed
                
                if (waitTime > 0) {
                    try {
                        sleep(waitTime)
                    } catch (e: InterruptedException) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }
    
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        soundPool.release()
    }
}
