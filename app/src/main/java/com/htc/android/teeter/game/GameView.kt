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
import androidx.core.graphics.get
import androidx.core.graphics.scale
import androidx.core.graphics.withSave
import com.htc.android.teeter.R
import com.htc.android.teeter.models.Level
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Physics constants derived from analysis of game assets and level geometry.
 */
private const val BALL_COLLISION_RATIO = 1.152f  // Ball uses 115% of visual radius for collision (includes solid core)
private const val BALL_HOLE_COLLISION_RATIO = 0.5f  // Ball uses 50% of visual radius for hole collision (must be well-centered)
private const val HOLE_DETECTION_RATIO = 0.300f  // Hole catches at 30% of visual radius
private const val GOAL_DETECTION_RATIO = 0.300f  // Goal catches at 30% of visual radius
private const val GRAVITY_ZONE_MULTIPLIER = 3.89f  // Gravity affects ball within 3.89× ball visual radius
private const val GRAVITY_BASE_STRENGTH = 0.4f  // Base gravitational pull strength
private const val MAX_TILT_MAGNITUDE = 10.0f  // Maximum device tilt in m/s² for normalization

// Haptic feedback constants for wall collisions
private const val MIN_IMPACT_VELOCITY = 2.0f  // Minimum velocity to trigger haptic feedback
private const val MAX_IMPACT_VELOCITY = 20.0f  // Maximum velocity for haptic scaling
private const val MIN_VIBRATION_MS = 10L  // Minimum vibration duration (light tap)
private const val MAX_VIBRATION_MS = 100L  // Maximum vibration duration (hard impact)
private const val MIN_VIBRATION_AMPLITUDE = 20  // Minimum vibration intensity (0-255)
private const val MAX_VIBRATION_AMPLITUDE = 200  // Maximum vibration intensity

// Wall rendering constants
private const val WALL_THICKNESS_MULTIPLIER = 1.5f  // Multiplier for wall thickness (1.0 = original size, 2.0 = double thickness)
private const val WALL_TEXTURE_BRIGHTNESS = 1f  // Brightness multiplier for wall texture (0.0 to 1.0)
private const val WALL_SHADOW_OPACITY = 100  // Shadow opacity (0-255)
private const val WALL_SHADOW_OFFSET = 2f  // Shadow offset in scaled pixels

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
    // Current device tilt (from accelerometer)
    private var currentAccelX = 0f
    private var currentAccelY = 0f
    // Radius will be calculated from bitmap dimensions (ball.png)
    private var baseBallRadius = 40f  // Initial value, updated when bitmap loads
    private var ballRadius = 40f

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
    
    // Haptic feedback state
    private var lastHapticTime = 0L
    private val hapticCooldown = 150L // Cooldown between wall impacts (ms)
    
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
    private var scaledWallTexture: Bitmap? = null // Cached scaled wall texture
    private var wallShader: BitmapShader? = null // Cached shader for walls
    private var mazeBitmap: Bitmap? = null
    private var scaledMazeBitmap: Bitmap? = null // Cached scaled version
    private var shadowBitmap: Bitmap? = null
    private val holeAnimFrames = mutableListOf<Bitmap>()
    private val endAnimFrames = mutableListOf<Bitmap>()
    
    // Bitmap-based radii (will be set after loading resources)
    private var baseHoleRadius = 33f  // hole.png is 66x66
    private var baseEndRadius = 40f   // end.png is 80x80
    
    // Detection ratios (calculated based on bitmap sizes)
    private var holeDetectionRatio = 1f
    private var goalDetectionRatio = 1f
    
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
    
    /**
     * Calculate the visible radius of a bitmap by detecting non-transparent pixels
     * from the center outward. This ensures collision detection matches visual appearance.
     */
    private fun calculateVisibleRadius(bitmap: Bitmap): Float {
        val centerX = bitmap.width / 2
        val centerY = bitmap.height / 2
        var maxOpaqueRadius = 0f
        
        // Check pixels in a circle from center, find furthest opaque pixel
        val maxCheckRadius = minOf(bitmap.width, bitmap.height) / 2
        
        for (angle in 0 until 360 step 5) {
            val radians = Math.toRadians(angle.toDouble())
            
            for (r in 0..maxCheckRadius) {
                val x = centerX + (r * kotlin.math.cos(radians)).toInt()
                val y = centerY + (r * kotlin.math.sin(radians)).toInt()
                
                if (x !in 0 until bitmap.width || y !in 0 until bitmap.height) break
                
                val pixel = bitmap[x, y]
                val alpha = (pixel shr 24) and 0xff
                
                // If mostly opaque (alpha > 128), update max radius
                if (alpha > 128) {
                    val distance = sqrt(((x - centerX) * (x - centerX) + (y - centerY) * (y - centerY)).toFloat())
                    maxOpaqueRadius = maxOf(maxOpaqueRadius, distance)
                }
            }
        }
        
        // Return calculated radius, or default to 30% of bitmap width as safe fallback
        return if (maxOpaqueRadius > 0) maxOpaqueRadius else bitmap.width * 0.3f
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
            
            // Calculate base radii by detecting actual visible (non-transparent) pixels
            ballBitmap?.let { baseBallRadius = calculateVisibleRadius(it) }
            holeBitmap?.let { baseHoleRadius = calculateVisibleRadius(it) }
            endBitmap?.let { baseEndRadius = calculateVisibleRadius(it) }
            
            holeDetectionRatio = HOLE_DETECTION_RATIO
            goalDetectionRatio = GOAL_DETECTION_RATIO
            
            wallBitmap = BitmapFactory.decodeResource(resources, R.drawable.wall, options)
            mazeBitmap = BitmapFactory.decodeResource(resources, R.drawable.maze, options)
            shadowBitmap = BitmapFactory.decodeResource(resources, R.drawable.shadow, options)
            
            // Load hole animation frames (001 to 020)
            val holeAnimResIds = listOf(
                R.drawable.hole_anim_001, R.drawable.hole_anim_002, R.drawable.hole_anim_003,
                R.drawable.hole_anim_004, R.drawable.hole_anim_005, R.drawable.hole_anim_006,
                R.drawable.hole_anim_007, R.drawable.hole_anim_008, R.drawable.hole_anim_009,
                R.drawable.hole_anim_010, R.drawable.hole_anim_011, R.drawable.hole_anim_012,
                R.drawable.hole_anim_013, R.drawable.hole_anim_014, R.drawable.hole_anim_015,
                R.drawable.hole_anim_016, R.drawable.hole_anim_017, R.drawable.hole_anim_018,
                R.drawable.hole_anim_019, R.drawable.hole_anim_020
            )
            holeAnimResIds.forEach { resId ->
                holeAnimFrames.add(BitmapFactory.decodeResource(resources, resId, options))
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
        // Use actual drawing area dimensions from the holder
        // This accounts for system bars and gives us the real playable area
        val actualWidth = holder.surfaceFrame.width().toFloat()
        val actualHeight = holder.surfaceFrame.height().toFloat()
        
        scaleX = actualWidth / originalWidth
        scaleY = actualHeight / originalHeight
        // Scale ball radius proportionally to screen size
        val avgScale = (scaleX + scaleY) / 2f
        ballRadius = baseBallRadius * avgScale
        // Pre-scale maze bitmap once to avoid doing it every frame
        mazeBitmap?.let {
            scaledMazeBitmap = it.scale(actualWidth.toInt(), actualHeight.toInt())
        }
        // Pre-scale wall texture to match screen resolution
        wallBitmap?.let { wall ->
            // Scale texture with careful handling to minimize JPEG artifacts
            val scaledSize = (wall.width * avgScale).toInt().coerceAtLeast(1)
            scaledWallTexture?.recycle()
            
            // Use high quality scaling to preserve texture details
            val options = BitmapFactory.Options().apply {
                inScaled = false
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inDither = false
            }
            
            scaledWallTexture = Bitmap.createScaledBitmap(wall, scaledSize, scaledSize, true)
            
            // Create shader - use MIRROR mode to hide repetition seams from JPEG artifacts
            wallShader = BitmapShader(scaledWallTexture!!, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR)
        }
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
            currentAccelX = event.values[1]
            currentAccelY = event.values[0]
            
            ballVelocityX += currentAccelX * 0.5f
            ballVelocityY += currentAccelY * 0.5f
            
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
        
        // Apply hole gravity (attracts ball when nearby)
        // applyHoleGravity()
        
        // Update ball position
        val oldBallX = ballX
        val oldBallY = ballY
        ballX += ballVelocityX
        ballY += ballVelocityY
        
        // Check wall collisions
        checkWallCollisions()
        
        // Check holes (use continuous collision detection to prevent tunneling)
        val holeResult = checkHoleCollisions(oldBallX, oldBallY)
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
    
    /**
     * Trigger haptic feedback based on impact velocity.
     * Implements Touch Diamond-style vibration that varies by impact force.
     * Uses cooldown to prevent multiple vibrations from composite walls.
     */
    private fun triggerImpactHaptic(impactVelocity: Float) {
        // Only vibrate if impact is strong enough
        if (impactVelocity < MIN_IMPACT_VELOCITY) return
        
        // Check cooldown to prevent continuous vibration from composite walls
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastHapticTime < hapticCooldown) return
        lastHapticTime = currentTime
        
        // Calculate impact strength (0.0 to 1.0)
        val normalizedImpact = ((impactVelocity - MIN_IMPACT_VELOCITY) / 
            (MAX_IMPACT_VELOCITY - MIN_IMPACT_VELOCITY)).coerceIn(0f, 1f)
        
        // Scale vibration duration and intensity based on impact
        val duration = (MIN_VIBRATION_MS + (MAX_VIBRATION_MS - MIN_VIBRATION_MS) * normalizedImpact).toLong()
        val amplitude = (MIN_VIBRATION_AMPLITUDE + (MAX_VIBRATION_AMPLITUDE - MIN_VIBRATION_AMPLITUDE) * normalizedImpact).toInt()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(duration)
        }
    }
    
    private fun checkWallCollisions() {
        level?.walls?.forEach { wall ->
            // Scale wall coordinates from XML
            val scaledLeft = wall.left * scaleX
            val scaledTop = wall.top * scaleY
            val scaledRight = wall.right * scaleX
            val scaledBottom = wall.bottom * scaleY
            
            // Add fixed extra thickness on all sides (multiplier applies to the 10px original wall size)
            val avgScale = (scaleX + scaleY) / 2f
            val extraThickness = (5f * avgScale) * (WALL_THICKNESS_MULTIPLIER - 1f)
            
            val left = scaledLeft - extraThickness
            val top = scaledTop - extraThickness
            val right = scaledRight + extraThickness
            val bottom = scaledBottom + extraThickness
            
            // Circle-rectangle collision detection
            // Find the closest point on the rectangle to the circle center
            val closestX = ballX.coerceIn(left, right)
            val closestY = ballY.coerceIn(top, bottom)
            
            // Calculate distance from circle center to this closest point
            val distanceX = ballX - closestX
            val distanceY = ballY - closestY
            val distanceSquared = distanceX * distanceX + distanceY * distanceY
            
            // Check if circle intersects rectangle
            if (distanceSquared < ballRadius * ballRadius) {
                // Collision detected - push ball out in the direction of least penetration
                val distance = sqrt(distanceSquared)
                
                if (distance > 0) {
                    // Push ball away from the closest point
                    val penetration = ballRadius - distance
                    val pushX = (distanceX / distance) * penetration
                    val pushY = (distanceY / distance) * penetration
                    
                    ballX += pushX
                    ballY += pushY
                    
                    // Calculate impact velocity (perpendicular component to wall)
                    // This is the actual collision force, not rolling velocity
                    val normalX = distanceX / distance
                    val normalY = distanceY / distance
                    val impactVelocity = abs(ballVelocityX * normalX + ballVelocityY * normalY)
                    
                    // Apply velocity dampening based on collision angle
                    // If hitting mostly horizontally, reduce horizontal velocity
                    if (abs(distanceX) > abs(distanceY)) {
                        ballVelocityX = -ballVelocityX * 0.5f
                    } else {
                        ballVelocityY = -ballVelocityY * 0.5f
                    }
                    
                    // Trigger haptic feedback (cooldown handles composite walls)
                    triggerImpactHaptic(impactVelocity)
                } else {
                    // Ball center is exactly on closest point (edge case)
                    // Determine which edge and push out
                    val distToLeft = ballX - left
                    val distToRight = right - ballX
                    val distToTop = ballY - top
                    val distToBottom = bottom - ballY
                    
                    val minDist = minOf(distToLeft, distToRight, distToTop, distToBottom)
                    
                    when (minDist) {
                        distToLeft -> {
                            val impactVelocity = abs(ballVelocityX)
                            ballX = left - ballRadius
                            ballVelocityX = -ballVelocityX * 0.5f
                            triggerImpactHaptic(impactVelocity)
                        }
                        distToRight -> {
                            val impactVelocity = abs(ballVelocityX)
                            ballX = right + ballRadius
                            ballVelocityX = -ballVelocityX * 0.5f
                            triggerImpactHaptic(impactVelocity)
                        }
                        distToTop -> {
                            val impactVelocity = abs(ballVelocityY)
                            ballY = top - ballRadius
                            ballVelocityY = -ballVelocityY * 0.5f
                            triggerImpactHaptic(impactVelocity)
                        }
                        distToBottom -> {
                            val impactVelocity = abs(ballVelocityY)
                            ballY = bottom + ballRadius
                            ballVelocityY = -ballVelocityY * 0.5f
                            triggerImpactHaptic(impactVelocity)
                        }
                    }
                }
            }
        }
        
        // Screen boundaries with haptic feedback
        if (ballX - ballRadius < 0) {
            val impactVelocity = abs(ballVelocityX)
            ballX = ballRadius
            ballVelocityX = -ballVelocityX * 0.5f
            triggerImpactHaptic(impactVelocity)
        }
        if (ballX + ballRadius > width) {
            val impactVelocity = abs(ballVelocityX)
            ballX = width - ballRadius
            ballVelocityX = -ballVelocityX * 0.5f
            triggerImpactHaptic(impactVelocity)
        }
        if (ballY - ballRadius < 0) {
            val impactVelocity = abs(ballVelocityY)
            ballY = ballRadius
            ballVelocityY = -ballVelocityY * 0.5f
            triggerImpactHaptic(impactVelocity)
        }
        if (ballY + ballRadius > height) {
            val impactVelocity = abs(ballVelocityY)
            ballY = height - ballRadius
            ballVelocityY = -ballVelocityY * 0.5f
            triggerImpactHaptic(impactVelocity)
        }
    }
    
    private fun checkHoleCollisions(oldBallX: Float = ballX, oldBallY: Float = ballY): Pair<Boolean, Pair<Float, Float>> {
        level?.holes?.forEach { hole ->
            val holeX = hole.x * scaleX
            val holeY = hole.y * scaleY
            val avgScale = (scaleX + scaleY) / 2f
            val holeEffectiveRadius = baseHoleRadius * avgScale * holeDetectionRatio
            val ballCollisionRadius = ballRadius * BALL_HOLE_COLLISION_RATIO
            
            // Check collision at current position
            val distance = sqrt((ballX - holeX) * (ballX - holeX) + (ballY - holeY) * (ballY - holeY))
            if (distance < ballCollisionRadius + holeEffectiveRadius) {
                return Pair(true, Pair(holeX, holeY))
            }
            
            // Also check if ball path crossed the hole (continuous collision detection)
            // This prevents fast-moving balls from tunneling through holes
            if (oldBallX != ballX || oldBallY != ballY) {
                val closestPoint = getClosestPointOnSegment(oldBallX, oldBallY, ballX, ballY, holeX, holeY)
                val distanceToPath = sqrt(
                    (closestPoint.first - holeX) * (closestPoint.first - holeX) + 
                    (closestPoint.second - holeY) * (closestPoint.second - holeY)
                )
                if (distanceToPath < ballCollisionRadius + holeEffectiveRadius) {
                    return Pair(true, Pair(holeX, holeY))
                }
            }
        }
        return Pair(false, Pair(0f, 0f))
    }
    
    // Helper function to find closest point on a line segment to a point
    private fun getClosestPointOnSegment(x1: Float, y1: Float, x2: Float, y2: Float, px: Float, py: Float): Pair<Float, Float> {
        val dx = x2 - x1
        val dy = y2 - y1
        val lengthSquared = dx * dx + dy * dy
        
        if (lengthSquared == 0f) {
            return Pair(x1, y1)
        }
        
        val t = ((px - x1) * dx + (py - y1) * dy) / lengthSquared
        val clampedT = t.coerceIn(0f, 1f)
        
        return Pair(x1 + clampedT * dx, y1 + clampedT * dy)
    }
    
    private fun applyHoleGravity() {
        level?.holes?.forEach { hole ->
            val holeX = hole.x * scaleX
            val holeY = hole.y * scaleY
            val dx = holeX - ballX
            val dy = holeY - ballY
            val distance = sqrt(dx * dx + dy * dy)
            
            // Apply gravity when ball is near hole (zone size from physics analysis)
            val gravityRadius = ballRadius * GRAVITY_ZONE_MULTIPLIER
            
            if (distance > 0 && distance < gravityRadius) {
                // Calculate device tilt magnitude (how much the device is tilted)
                val tiltMagnitude = sqrt(currentAccelX * currentAccelX + currentAccelY * currentAccelY)
                
                // Gravity is stronger when device is flat (less tilt), weaker when tilted
                // Normalize using MAX_TILT_MAGNITUDE (accelerometer max at full tilt)
                val tiltFactor = (1f - (tiltMagnitude / MAX_TILT_MAGNITUDE).coerceIn(0f, 1f))
                
                // Base gravity strength modulated by distance and tilt
                // Uses quadratic falloff for natural feel
                val normalizedDistance = distance / gravityRadius
                val baseStrength = GRAVITY_BASE_STRENGTH * (1f - normalizedDistance) * (1f - normalizedDistance)
                val gravityStrength = baseStrength * tiltFactor
                
                // Apply force towards hole center
                val normalizedDx = dx / distance
                val normalizedDy = dy / distance
                
                ballVelocityX += normalizedDx * gravityStrength
                ballVelocityY += normalizedDy * gravityStrength
            }
        }
    }
    
    private fun checkGoal(): Pair<Boolean, Pair<Float, Float>> {
        level?.let {
            val endX = it.endX * scaleX
            val endY = it.endY * scaleY
            val distance = sqrt((ballX - endX) * (ballX - endX) + (ballY - endY) * (ballY - endY))
            val avgScale = (scaleX + scaleY) / 2f
            // Circle-circle collision: goal reached when edges touch/overlap
            val goalEffectiveRadius = baseEndRadius * avgScale * goalDetectionRatio
            // Ball collision radius: 115% of visual radius (same as hole collision for consistency)
            val ballCollisionRadius = ballRadius * BALL_COLLISION_RATIO
            // Goal reached when: distance between centers < (ballCollisionRadius + goalEffectiveRadius)
            if (distance < ballCollisionRadius + goalEffectiveRadius) {
                return Pair(true, Pair(endX, endY))
            }
        }
        return Pair(false, Pair(0f, 0f))
    }
    
    private fun drawGame(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        
        level ?: return
        
        // Draw maze background
        scaledMazeBitmap?.let {
            val mazePaint = Paint().apply {
                isAntiAlias = true
                isFilterBitmap = true
            }
            canvas.drawBitmap(it, 0f, 0f, mazePaint)
        }
        
        // Draw holes BEFORE walls so they blend naturally underneath
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
        
        // Draw walls with metallic texture (on top of holes)
        wallShader?.let { shader ->
            val avgScale = (scaleX + scaleY) / 2f
            
            // Shadow paint with blur for natural depth effect
            val shadowPaint = Paint().apply {
                color = Color.argb(180, 0, 0, 0)  // Darker for visibility through blur
                style = Paint.Style.FILL
                isAntiAlias = true
                maskFilter = BlurMaskFilter(8f * avgScale, BlurMaskFilter.Blur.NORMAL)  // Wider blurred shadow
            }
            
            // Wall paint with texture
            val wallPaint = Paint().apply {
                this.shader = shader
                isAntiAlias = false
                isFilterBitmap = false
                isDither = false
            }
            
            shader.setLocalMatrix(null)
            
            // FIRST PASS: Draw ALL blurred shadows for natural 3D relief
            level?.walls?.forEach { wall ->
                val scaledLeft = wall.left * scaleX
                val scaledTop = wall.top * scaleY
                val scaledRight = wall.right * scaleX
                val scaledBottom = wall.bottom * scaleY
                
                val extraThickness = (5f * avgScale) * (WALL_THICKNESS_MULTIPLIER - 1f)
                
                val left = scaledLeft - extraThickness
                val top = scaledTop - extraThickness
                val right = scaledRight + extraThickness
                val bottom = scaledBottom + extraThickness
                
                // Blurred shadow creates natural dispersed depth effect
                canvas.drawRect(left, top, right, bottom, shadowPaint)
            }
            
            // SECOND PASS: Draw ALL walls on top (they merge seamlessly)
            level?.walls?.forEach { wall ->
                val scaledLeft = wall.left * scaleX
                val scaledTop = wall.top * scaleY
                val scaledRight = wall.right * scaleX
                val scaledBottom = wall.bottom * scaleY
                
                val extraThickness = (5f * avgScale) * (WALL_THICKNESS_MULTIPLIER - 1f)
                
                val left = scaledLeft - extraThickness
                val top = scaledTop - extraThickness
                val right = scaledRight + extraThickness
                val bottom = scaledBottom + extraThickness
                
                canvas.drawRect(left, top, right, bottom, wallPaint)
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
                    // Draw hole animation frames at the hole position
                    if (holeAnimFrames.isNotEmpty()) {
                        val frameIndex = (animationProgress * holeAnimFrames.size).toInt()
                            .coerceIn(0, holeAnimFrames.size - 1)
                        val frame = holeAnimFrames[frameIndex]
                        
                        val framePaint = Paint().apply {
                            isAntiAlias = true
                            isFilterBitmap = true
                        }
                        val avgScale = (scaleX + scaleY) / 2f
                        // Animation frame should match the hole bitmap size (66px)
                        // holeBitmap is 66x66, animation frames are larger, so scale down
                        val targetSize = (holeBitmap?.width?.toFloat() ?: 66f) * avgScale
                        val animScale = targetSize / frame.width
                        canvas.withSave {
                            translate(animationTargetX, animationTargetY)
                            scale(animScale, animScale)
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
