package com.htc.android.teeter.game

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.core.graphics.get
import androidx.core.graphics.scale
import androidx.core.graphics.withSave
import com.htc.android.teeter.R
import com.htc.android.teeter.models.Level
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Main game view for the teeter game (ball rolling maze game)
 * Handles game rendering, physics simulation, sensor input, and animations
 */
class GameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback, SensorEventListener {

    /**
     * Physics constants for ball movement, collisions and gravity calculations
     */
    private object PhysicsConstants {
        const val BALL_COLLISION_RATIO = 1.152f          // Collision detection ratio for ball
        const val BALL_HOLE_COLLISION_RATIO = 0.5f        // Collision ratio for ball and hole detection
        const val HOLE_DETECTION_RATIO = 0.300f           // Effective radius ratio for hole detection
        const val GOAL_DETECTION_RATIO = 0.300f           // Effective radius ratio for goal detection
        const val GRAVITY_ZONE_MULTIPLIER = 3.89f         // Multiplier for hole gravity influence zone
        const val GRAVITY_BASE_STRENGTH = 0.4f            // Base strength of hole gravity pull
        const val MAX_TILT_MAGNITUDE = 10.0f              // Maximum tilt magnitude from accelerometer
        const val MAX_VELOCITY = 20f                      // Maximum ball velocity limit
        const val FRICTION_FACTOR = 0.98f                 // Friction coefficient (slows down ball)
        const val ACCELERATION_FACTOR = 0.5f              // Acceleration multiplier for sensor input
        const val WALL_RESTITUTION = 0.5f                 // Bounce factor for wall collisions (0=no bounce, 1=full bounce)
        const val BOUNDARY_RESTITUTION = 0.5f             // Bounce factor for screen boundary collisions
        const val MIN_EFFECTIVE_IMPACT_VELOCITY = 3.0f    // Minimum velocity to trigger collision effects
    }

    /**
     * Haptic feedback constants for vibration effects on collisions
     */
    private object HapticConstants {
        const val MIN_IMPACT_VELOCITY = 2.0f              // Minimum velocity to trigger vibration
        const val MAX_IMPACT_VELOCITY = 20.0f             // Maximum velocity for vibration scaling
        const val MIN_VIBRATION_MS = 10L                  // Minimum vibration duration in milliseconds
        const val MAX_VIBRATION_MS = 100L                 // Maximum vibration duration in milliseconds
        const val MIN_VIBRATION_AMPLITUDE = 20            // Minimum vibration strength (0-255)
        const val MAX_VIBRATION_AMPLITUDE = 200           // Maximum vibration strength (0-255)
        const val HAPTIC_COOLDOWN = 150L                  // Cooldown time between vibration triggers
        const val BOUNDARY_MIN_IMPACT_VELOCITY = 3.0f     // Minimum velocity for boundary vibration
        const val BOUNDARY_HAPTIC_COOLDOWN = 150L         // Cooldown for boundary vibrations
    }

    /**
     * Rendering constants for graphics, animations and screen scaling
     */
    private object RenderConstants {
        const val WALL_THICKNESS_MULTIPLIER = 1.5f        // Multiplier for wall rendering thickness
        const val WALL_EXTRA_THICKNESS_BASE = 5f           // Base extra thickness for wall collision detection
        const val SHADOW_BLUR_RADIUS = 8f                 // Blur radius for wall shadows
        const val ORIGINAL_WIDTH = 1280f                  // Original design width for scaling
        const val ORIGINAL_HEIGHT = 720f                  // Original design height for scaling
        const val ANIMATION_DURATION = 500L               // Default animation duration in milliseconds
        const val TARGET_FPS = 60                         // Target frames per second for game loop
        const val BLUR_BACKGROUND_RADIUS = 25f            // Blur radius for background effects
        const val BLUR_BACKGROUND_ALPHA = 1f              // Alpha value for blurred background
        const val BLUR_BACKGROUND_DARKEN_ALPHA = 0.3f     // Alpha for darkening overlay on blurred background
    }

    /**
     * Resource constants for bitmap sizes and animation frames
     */
    private object ResourceConstants {
        const val DEFAULT_BALL_RADIUS = 40f               // Default ball radius if bitmap not available
        const val DEFAULT_HOLE_RADIUS = 40f               // Default hole radius if bitmap not available
        const val DEFAULT_END_RADIUS = 40f                // Default goal radius if bitmap not available
        const val END_ANIM_FRAME_WIDTH = 100              // Width of each end animation frame
        const val END_ANIM_FRAME_HEIGHT = 100             // Height of each end animation frame
    }

    /**
     * Types of animations supported in the game
     */
    enum class AnimationType {
        NONE,               // No animation
        HOLE_FALL,          // Ball falling into hole animation
        GOAL_SUCCESS        // Level completion animation
    }

    /**
     * Sound effects types for game events
     */
    enum class SoundType {
        HOLE,               // Sound when ball falls into hole
        LEVEL_COMPLETE      // Sound when level is completed
    }

    private var gameThread: GameThread? = null            // Game loop thread
    private var level: Level? = null                      // Current game level data

    /**
     * Data class to hold ball's physical state (position and velocity)
     */
    private data class BallState(
        var x: Float = 0f,          // Current X position
        var y: Float = 0f,          // Current Y position
        var velocityX: Float = 0f,  // Horizontal velocity
        var velocityY: Float = 0f   // Vertical velocity
    )

    private val currentBallState = BallState()            // Current ball state
    private val savedBallState = BallState()              // Saved state for pause/resume
    private val persistentBallState = BallState()         // Persistent state across view recreation

    // Collision tracking variables
    private var lastCollisionNormalX = 0f                // X component of last collision normal
    private var lastCollisionNormalY = 0f                // Y component of last collision normal
    private var lastCollisionTime = 0L                   // Timestamp of last collision
    private var lastCollisionPositionX = 0f              // X position of last collision
    private var lastCollisionPositionY = 0f              // Y position of last collision
    private var consecutiveCollisions = 0                // Count of consecutive collisions
    private val MAX_CONSECUTIVE_COLLISIONS = 2           // Max consecutive collisions before haptic cooldown
    private val COLLISION_DISTANCE_THRESHOLD = 5f        // Distance threshold for same-position collisions
    private var isRollingOnWall = false                  // Flag for ball rolling along wall
    private var rollingWallNormalX = 0f                  // Normal vector X for rolling wall
    private var rollingWallNormalY = 0f                  // Normal vector Y for rolling wall
    private var rollingStartTime = 0L                    // Timestamp when rolling started
    private val ROLLING_DETECTION_TIME = 100L            // Time to detect rolling state
    private val ROLLING_CONFIRM_TIME = 50L               // Time to confirm rolling state

    // Game state flags
    private var isStateSaved = false                     // Flag if ball state is saved
    private var isFirstLoadCompleted = false             // Flag for first load completion
    private var isBallPositionInitialized = false        // Flag if ball position is initialized
    private var isSurfaceSizeCalculated = false          // Flag if surface dimensions are calculated
    var isHoleDisplayed = true                           // Flag to show/hide holes
        private set
    var isEndDisplayed = true                            // Flag to show/hide goal
        private set
    private var isAnimating = false                      // Flag if animation is in progress
    private var animationType = AnimationType.NONE       // Current animation type
    private var animationProgress = 0f                   // Progress of current animation (0-1)
    private var animationStartX = 0f                     // Animation start X position
    private var animationStartY = 0f                     // Animation start Y position
    private var animationTargetX = 0f                    // Animation target X position
    private var animationTargetY = 0f                    // Animation target Y position
    private var animationStartTime = 0L                  // Animation start timestamp
    private var levelCompleted = false                   // Flag if current level is completed
    private var levelCompletedTriggered = false          // Flag if level complete callback was triggered
    private var lastHapticTime = 0L                      // Timestamp of last haptic feedback
    private var lastBoundaryHapticTime = 0L              // Timestamp of last boundary haptic feedback
    private var isGamePaused = false                     // Flag if game is paused
    private var isResettingGame = false                  // Flag if game is being reset

    // Sensor related variables
    private var currentAccelX = 0f                       // Filtered accelerometer X value
    private var currentAccelY = 0f                       // Filtered accelerometer Y value
    private val sensorManager: SensorManager by lazy {    // System sensor manager
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }
    private val accelerometer: Sensor? by lazy {         // Device accelerometer sensor
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }

    // Scaling and positioning variables
    private var scaleFactor = 1f                         // Scale factor for screen adaptation
    private var offsetX = 0f                             // X offset for centered rendering
    private var offsetY = 0f                             // Y offset for centered rendering
    private var ballRadius = ResourceConstants.DEFAULT_BALL_RADIUS  // Scaled ball radius
    private var baseBallRadius = ResourceConstants.DEFAULT_BALL_RADIUS  // Original ball radius
    private var baseHoleRadius = ResourceConstants.DEFAULT_HOLE_RADIUS  // Original hole radius
    private var baseEndRadius = ResourceConstants.DEFAULT_END_RADIUS    // Original goal radius
    private var holeDetectionRatio = PhysicsConstants.HOLE_DETECTION_RATIO  // Scaled hole detection ratio
    private var goalDetectionRatio = PhysicsConstants.GOAL_DETECTION_RATIO  // Scaled goal detection ratio

    // Bitmap and rendering locks
    private val bitmapLock = ReentrantLock()             // Lock for bitmap operations
    private var currentIsFacetBackground = false         // Flag for current background type
    private var scaledFacetBitmap: Bitmap? = null        // Scaled facet background bitmap
    private var scaledMazeBitmap: Bitmap? = null         // Scaled maze background bitmap
    private var blurredFacetBitmap: Bitmap? = null       // Blurred facet background bitmap
    private var blurredMazeBitmap: Bitmap? = null        // Blurred maze background bitmap

    // Paint objects for rendering
    private val blurBackgroundPaint by lazy {            // Paint for blurred background
        Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
            alpha = (255 * RenderConstants.BLUR_BACKGROUND_ALPHA).toInt()
        }
    }
    private val darkenMaskPaint by lazy {                // Paint for darkening overlay
        Paint().apply {
            color = Color.BLACK
            alpha = (255 * RenderConstants.BLUR_BACKGROUND_DARKEN_ALPHA).toInt()
            isAntiAlias = true
        }
    }
    private val backgroundPaint by lazy {                // Paint for game background
        Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
        }
    }
    private val holePaint by lazy {                      // Paint for hole rendering
        Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
        }
    }
    private val goalPaint by lazy {                      // Paint for goal rendering
        Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
        }
    }
    private val ballPaint by lazy {                      // Paint for ball rendering
        Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
        }
    }
    private val animPaint by lazy {                      // Paint for animation frames
        Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
        }
    }
    private val shadowPaint by lazy {                    // Paint for wall shadows
        Paint().apply {
            color = Color.argb(180, 0, 0, 0)
            style = Paint.Style.FILL
            isAntiAlias = true
        }
    }
    private val wallPaint by lazy {                      // Paint for wall rendering
        Paint().apply {
            isAntiAlias = false
            isFilterBitmap = false
            isDither = false
        }
    }

    // System handlers and services
    private val mainHandler by lazy {                    // Main thread handler for UI operations
        Handler(Looper.getMainLooper())
    }
    private lateinit var resourceLoader: ResourceLoader  // Resource loading helper
    private val vibrator: Vibrator by lazy { getVibrator(context) }  // Device vibrator service
    private val renderScript: RenderScript by lazy {     // RenderScript for image processing
        RenderScript.create(context)
    }

    // Callback listeners
    var onLevelComplete: (() -> Unit)? = null            // Callback for level completion
    var onFallInHole: (() -> Unit)? = null               // Callback for ball falling into hole

    /**
     * Initialize game view and set up basic configurations
     */
    init {
        holder.addCallback(this)
        setZOrderOnTop(false)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        isFocusable = true
        resourceLoader = ResourceLoader(context)
    }

    /**
     * Set current game level and reset game state
     * @param newLevel The level to load and display
     */
    fun setLevel(newLevel: Level) {
        bitmapLock.withLock {
            level = newLevel
            resetGameState()

            val backgroundChanged = currentIsFacetBackground != newLevel.isFacetBackground
            currentIsFacetBackground = newLevel.isFacetBackground

            isBallPositionInitialized = false

            if (backgroundChanged && width > 0 && height > 0) {
                adjustBackgroundBitmapsAsync(width.toFloat(), height.toFloat())
            }

            if (isSurfaceSizeCalculated) {
                initializeBallPosition()
            }
        }
    }

    /**
     * Reset all game state variables to initial values
     */
    private fun resetGameState() {
        levelCompleted = false
        levelCompletedTriggered = false
        isAnimating = false
        isStateSaved = false
        isFirstLoadCompleted = false
        isBallPositionInitialized = false
        lastCollisionNormalX = 0f
        lastCollisionNormalY = 0f
        lastCollisionTime = 0L
        lastCollisionPositionX = 0f
        lastCollisionPositionY = 0f
        consecutiveCollisions = 0
        isRollingOnWall = false
        rollingWallNormalX = 0f
        rollingWallNormalY = 0f
        rollingStartTime = 0L
        resetBall()
    }

    /**
     * Initialize ball position from saved state or level start position
     */
    private fun initializeBallPosition() {
        bitmapLock.withLock {
            if (level != null && isSurfaceSizeCalculated) {
                if (persistentBallState.x != 0f || persistentBallState.y != 0f) {
                    currentBallState.apply {
                        x = persistentBallState.x
                        y = persistentBallState.y
                        velocityX = persistentBallState.velocityX
                        velocityY = persistentBallState.velocityY
                    }
                    savedBallState.apply {
                        x = currentBallState.x
                        y = currentBallState.y
                        velocityX = currentBallState.velocityX
                        velocityY = currentBallState.velocityY
                    }
                } else {
                    resetBall()
                }
                isBallPositionInitialized = true
            }
        }
    }

    /**
     * Reset ball to level start position with zero velocity
     */
    private fun resetBall() {
        level?.let {
            currentBallState.apply {
                x = calculateScaledX(it.beginX)
                y = calculateScaledY(it.beginY)
                velocityX = 0f
                velocityY = 0f
            }
            savedBallState.apply {
                x = currentBallState.x
                y = currentBallState.y
                velocityX = currentBallState.velocityX
                velocityY = currentBallState.velocityY
            }
            persistentBallState.apply {
                x = currentBallState.x
                y = currentBallState.y
                velocityX = currentBallState.velocityX
                velocityY = currentBallState.velocityY
            }
            if (isSurfaceSizeCalculated) {
                isBallPositionInitialized = true
            }
        }
    }

    /**
     * Force reset ball position and clear collision state
     */
    fun forceResetBall() {
        bitmapLock.withLock {
            resetBall()
            isStateSaved = false
            lastCollisionNormalX = 0f
            lastCollisionNormalY = 0f
            lastCollisionTime = 0L
            lastCollisionPositionX = 0f
            lastCollisionPositionY = 0f
            consecutiveCollisions = 0
            isRollingOnWall = false
            rollingWallNormalX = 0f
            rollingWallNormalY = 0f
            rollingStartTime = 0L
            isBallPositionInitialized = true
        }
    }

    /**
     * Save current ball state for pause/resume functionality
     */
    fun saveBallState() {
        bitmapLock.withLock {
            if (!isAnimating && !levelCompleted) {
                savedBallState.apply {
                    x = currentBallState.x
                    y = currentBallState.y
                    velocityX = currentBallState.velocityX
                    velocityY = currentBallState.velocityY
                }
                persistentBallState.apply {
                    x = currentBallState.x
                    y = currentBallState.y
                    velocityX = currentBallState.velocityX
                    velocityY = currentBallState.velocityY
                }
                isStateSaved = true
            }
        }
    }

    /**
     * Restore ball state from saved values
     */
    private fun restoreBallState() {
        bitmapLock.withLock {
            currentBallState.apply {
                x = savedBallState.x
                y = savedBallState.y
                velocityX = savedBallState.velocityX
                velocityY = savedBallState.velocityY
            }
        }
    }

    /**
     * Start listening to accelerometer sensor events
     */
    fun startSensors() {
        bitmapLock.withLock {
            if (!isGamePaused) {
                accelerometer?.let {
                    sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
                }
            }
        }
    }

    /**
     * Stop listening to accelerometer sensor events
     */
    fun stopSensors() {
        bitmapLock.withLock {
            sensorManager.unregisterListener(this)
        }
    }

    /**
     * Pause game play and save current state
     */
    fun pauseGame() {
        bitmapLock.withLock {
            if (!isGamePaused) {
                isGamePaused = true
                stopSensors()
                saveBallState()
                currentBallState.velocityX = 0f
                currentBallState.velocityY = 0f
                isResettingGame = false
            }
        }
    }

    /**
     * Resume game play from paused state
     */
    fun resumeGame() {
        bitmapLock.withLock {
            if (isGamePaused) {
                isGamePaused = false
                currentBallState.velocityX = savedBallState.velocityX
                currentBallState.velocityY = savedBallState.velocityY
                isBallPositionInitialized = true
                startSensors()
                isResettingGame = false
            }
        }
    }

    /**
     * Resume game play with zero ball velocity
     */
    fun resumeGameWithZeroVelocity() {
        bitmapLock.withLock {
            isGamePaused = false
            currentBallState.velocityX = 0f
            currentBallState.velocityY = 0f
            isBallPositionInitialized = true
            startSensors()
            isResettingGame = false
        }
    }

    /**
     * Toggle visibility of holes in the game
     */
    fun toggleHoleDisplay() {
        bitmapLock.withLock {
            isHoleDisplayed = !isHoleDisplayed
            invalidate()
        }
    }

    /**
     * Toggle visibility of goal in the game
     */
    fun toggleEndDisplay() {
        bitmapLock.withLock {
            isEndDisplayed = !isEndDisplayed
            invalidate()
        }
    }

    /**
     * Set game resetting state
     * @param resetting True if game is being reset
     */
    fun setResettingGame(resetting: Boolean) {
        bitmapLock.withLock {
            isResettingGame = resetting
            if (resetting) {
                forceResetBall()
            }
        }
    }

    /**
     * Called when surface is created - initialize game thread and sensors
     */
    override fun surfaceCreated(holder: SurfaceHolder) {
        bitmapLock.withLock {
            if (::resourceLoader.isInitialized) {
                resourceLoader.release()
            }
            resourceLoader = ResourceLoader(context)

            gameThread = GameThread(holder).apply {
                running = true
                start()
            }
            if (!isGamePaused) {
                startSensors()
            }
            if (level != null && isSurfaceSizeCalculated) {
                initializeBallPosition()
            }
        }
    }

    /**
     * Called when surface size changes - recalculate scaling and adjust resources
     */
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        bitmapLock.withLock {
            calculateScaleAndOffset(width.toFloat(), height.toFloat())
            isSurfaceSizeCalculated = true

            ballRadius = baseBallRadius * scaleFactor

            adjustBackgroundBitmapsAsync(width.toFloat(), height.toFloat())
            adjustWallTexture()

            shadowPaint.maskFilter = BlurMaskFilter(
                RenderConstants.SHADOW_BLUR_RADIUS * scaleFactor,
                BlurMaskFilter.Blur.NORMAL
            )

            when {
                isResettingGame -> {
                    resetBall()
                    isStateSaved = false
                    isResettingGame = false
                }
                isStateSaved -> {
                    restoreBallState()
                    currentBallState.velocityX = 0f
                    currentBallState.velocityY = 0f
                    isBallPositionInitialized = true
                }
                !isFirstLoadCompleted -> {
                    initializeBallPosition()
                    isFirstLoadCompleted = true
                }
                else -> {
                    initializeBallPosition()
                }
            }
        }
    }

    /**
     * Called when surface is destroyed - clean up resources and save state
     */
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        bitmapLock.withLock {
            gameThread?.running = false
            stopSensors()

            persistentBallState.apply {
                x = currentBallState.x
                y = currentBallState.y
                velocityX = currentBallState.velocityX
                velocityY = currentBallState.velocityY
            }

            releaseScaledBitmaps()
            releaseBlurredBitmaps()
            try {
                renderScript.destroy()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            if (::resourceLoader.isInitialized) {
                resourceLoader.release()
            }
            isSurfaceSizeCalculated = false
            isBallPositionInitialized = false
        }

        gameThread?.joinSafely()
        gameThread = null
    }

    /**
     * Calculate scaling factor and offset for different screen sizes
     * @param surfaceWidth Width of the surface view
     * @param surfaceHeight Height of the surface view
     */
    private fun calculateScaleAndOffset(surfaceWidth: Float, surfaceHeight: Float) {
        val designRatio = RenderConstants.ORIGINAL_WIDTH / RenderConstants.ORIGINAL_HEIGHT
        val surfaceRatio = surfaceWidth / surfaceHeight

        scaleFactor = if (surfaceRatio > designRatio) {
            surfaceHeight / RenderConstants.ORIGINAL_HEIGHT
        } else {
            surfaceWidth / RenderConstants.ORIGINAL_WIDTH
        }

        val scaledDesignWidth = RenderConstants.ORIGINAL_WIDTH * scaleFactor
        val scaledDesignHeight = RenderConstants.ORIGINAL_HEIGHT * scaleFactor

        offsetX = (surfaceWidth - scaledDesignWidth) / 2f
        offsetY = (surfaceHeight - scaledDesignHeight) / 2f
    }

    /**
     * Calculate scale factor to fill entire screen
     * @param surfaceWidth Width of the surface view
     * @param surfaceHeight Height of the surface view
     * @return Scale factor for fill screen
     */
    private fun calculateFillScreenScale(surfaceWidth: Float, surfaceHeight: Float): Float {
        val designRatio = RenderConstants.ORIGINAL_WIDTH / RenderConstants.ORIGINAL_HEIGHT
        val surfaceRatio = surfaceWidth / surfaceHeight

        return if (surfaceRatio > designRatio) {
            surfaceWidth / RenderConstants.ORIGINAL_WIDTH
        } else {
            surfaceHeight / RenderConstants.ORIGINAL_HEIGHT
        }
    }

    /**
     * Convert design X coordinate to screen coordinates
     * @param designX X coordinate in original design
     * @return Scaled X coordinate
     */
    private fun calculateScaledX(designX: Float): Float {
        return offsetX + designX * scaleFactor
    }

    /**
     * Convert design Y coordinate to screen coordinates
     * @param designY Y coordinate in original design
     * @return Scaled Y coordinate
     */
    private fun calculateScaledY(designY: Float): Float {
        return offsetY + designY * scaleFactor
    }

    /**
     * Process accelerometer sensor data with low-pass filter
     */
    override fun onSensorChanged(event: SensorEvent) {
        bitmapLock.withLock {
            if (isGamePaused || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

            val alpha = 0.8f
            currentAccelX = alpha * currentAccelX + (1 - alpha) * event.values[1]
            currentAccelY = alpha * currentAccelY + (1 - alpha) * event.values[0]
        }
    }

    /**
     * Not used - required for SensorEventListener interface
     */
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /**
     * Update game physics - called each frame
     */
    private fun updatePhysics() {
        bitmapLock.withLock {
            if (!isBallPositionInitialized) return

            if (isAnimating) {
                updateAnimation()
                return
            }

            val oldX = currentBallState.x
            val oldY = currentBallState.y

            // Apply acceleration from sensor input
            currentBallState.velocityX += currentAccelX * PhysicsConstants.ACCELERATION_FACTOR
            currentBallState.velocityY += currentAccelY * PhysicsConstants.ACCELERATION_FACTOR

            // Apply friction to slow down the ball
            currentBallState.velocityX *= PhysicsConstants.FRICTION_FACTOR
            currentBallState.velocityY *= PhysicsConstants.FRICTION_FACTOR

            // Limit velocity to maximum value
            currentBallState.velocityX = currentBallState.velocityX.coerceIn(-PhysicsConstants.MAX_VELOCITY, PhysicsConstants.MAX_VELOCITY)
            currentBallState.velocityY = currentBallState.velocityY.coerceIn(-PhysicsConstants.MAX_VELOCITY, PhysicsConstants.MAX_VELOCITY)

            // Apply hole gravity (attracts ball when nearby) - currently commented out
            //applyHoleGravity()

            // Update ball position based on velocity
            currentBallState.x += currentBallState.velocityX
            currentBallState.y += currentBallState.velocityY

            // Save state for persistence
            persistentBallState.apply {
                x = currentBallState.x
                y = currentBallState.y
                velocityX = currentBallState.velocityX
                velocityY = currentBallState.velocityY
            }

            // Check for collisions with walls and boundaries
            checkWallCollisions()
            checkGameBoundaries()

            // Check if ball fell into hole
            val holeCollision = checkHoleCollisions(oldX, oldY)
            if (holeCollision != null) {
                startAnimation(AnimationType.HOLE_FALL, holeCollision.first, holeCollision.second)
                return
            }

            // Check if ball reached goal
            val goalCollision = checkGoal()
            if (goalCollision != null) {
                startAnimation(AnimationType.GOAL_SUCCESS, goalCollision.first, goalCollision.second)
                return
            }
        }
    }

    /**
     * Apply gravitational pull from holes to the ball
     */
    private fun applyHoleGravity() {
        bitmapLock.withLock {
            level?.holes?.forEach { hole ->
                val holeX = calculateScaledX(hole.x)
                val holeY = calculateScaledY(hole.y)

                val dx = holeX - currentBallState.x
                val dy = holeY - currentBallState.y
                val distance = sqrt(dx * dx + dy * dy)
                val gravityRadius = ballRadius * PhysicsConstants.GRAVITY_ZONE_MULTIPLIER

                if (distance > 0 && distance < gravityRadius) {
                    val tiltMagnitude = sqrt(currentAccelX * currentAccelX + currentAccelY * currentAccelY)
                    val tiltFactor = (1f - (tiltMagnitude / PhysicsConstants.MAX_TILT_MAGNITUDE).coerceIn(0f, 1f))

                    val normalizedDistance = distance / gravityRadius
                    val strength = PhysicsConstants.GRAVITY_BASE_STRENGTH *
                            (1f - normalizedDistance) * (1f - normalizedDistance) * tiltFactor

                    currentBallState.velocityX += (dx / distance) * strength
                    currentBallState.velocityY += (dy / distance) * strength
                }
            }
        }
    }

    /**
     * Check for collisions between ball and walls
     */
    private fun checkWallCollisions() {
        bitmapLock.withLock {
            val currentTime = System.currentTimeMillis()
            // Reset collision tracking after cooldown period
            if (currentTime - lastCollisionTime > 50) {
                consecutiveCollisions = 0
                if (isRollingOnWall && currentTime - rollingStartTime > ROLLING_DETECTION_TIME) {
                    isRollingOnWall = false
                    rollingWallNormalX = 0f
                    rollingWallNormalY = 0f
                }
            }

            // Check collision with each wall in the level
            level?.walls?.forEach { wall ->
                val extraThickness = RenderConstants.WALL_EXTRA_THICKNESS_BASE * scaleFactor *
                        (RenderConstants.WALL_THICKNESS_MULTIPLIER - 1f)
                val left = calculateScaledX(wall.left) - extraThickness
                val top = calculateScaledY(wall.top) - extraThickness
                val right = calculateScaledX(wall.right) + extraThickness
                val bottom = calculateScaledY(wall.bottom) + extraThickness

                // Find closest point on wall rectangle to ball
                val closestX = currentBallState.x.coerceIn(left, right)
                val closestY = currentBallState.y.coerceIn(top, bottom)
                val dx = currentBallState.x - closestX
                val dy = currentBallState.y - closestY
                val distanceSquared = dx * dx + dy * dy

                // Check if ball is colliding with wall
                if (distanceSquared < ballRadius * ballRadius) {
                    handleWallCollision(dx, dy, distanceSquared, left, top, right, bottom)
                }
            }
        }
    }

    /**
     * Check and handle ball collisions with game boundaries (screen edges)
     */
    private fun checkGameBoundaries() {
        bitmapLock.withLock {
            val gameLeft = offsetX + ballRadius
            val gameRight = offsetX + RenderConstants.ORIGINAL_WIDTH * scaleFactor - ballRadius
            val gameTop = offsetY + ballRadius
            val gameBottom = offsetY + RenderConstants.ORIGINAL_HEIGHT * scaleFactor - ballRadius

            with(currentBallState) {
                var shouldTriggerHaptic = false
                var impactVelocity = 0f

                // Left boundary collision
                if (x < gameLeft) {
                    val previousVelocityX = velocityX
                    x = gameLeft
                    velocityX = -velocityX * PhysicsConstants.BOUNDARY_RESTITUTION

                    if (previousVelocityX < 0 && abs(previousVelocityX) > HapticConstants.BOUNDARY_MIN_IMPACT_VELOCITY) {
                        impactVelocity = abs(previousVelocityX)
                        shouldTriggerHaptic = true
                    }
                }

                // Right boundary collision
                if (x > gameRight) {
                    val previousVelocityX = velocityX
                    x = gameRight
                    velocityX = -velocityX * PhysicsConstants.BOUNDARY_RESTITUTION

                    if (previousVelocityX > 0 && abs(previousVelocityX) > HapticConstants.BOUNDARY_MIN_IMPACT_VELOCITY) {
                        impactVelocity = abs(previousVelocityX)
                        shouldTriggerHaptic = true
                    }
                }

                // Top boundary collision
                if (y < gameTop) {
                    val previousVelocityY = velocityY
                    y = gameTop
                    velocityY = -velocityY * PhysicsConstants.BOUNDARY_RESTITUTION

                    if (previousVelocityY < 0 && abs(previousVelocityY) > HapticConstants.BOUNDARY_MIN_IMPACT_VELOCITY) {
                        impactVelocity = abs(previousVelocityY)
                        shouldTriggerHaptic = true
                    }
                }

                // Bottom boundary collision
                if (y > gameBottom) {
                    val previousVelocityY = velocityY
                    y = gameBottom
                    velocityY = -velocityY * PhysicsConstants.BOUNDARY_RESTITUTION

                    if (previousVelocityY > 0 && abs(previousVelocityY) > HapticConstants.BOUNDARY_MIN_IMPACT_VELOCITY) {
                        impactVelocity = abs(previousVelocityY)
                        shouldTriggerHaptic = true
                    }
                }

                // Trigger haptic feedback if needed
                if (shouldTriggerHaptic) {
                    triggerBoundaryHaptic(impactVelocity)
                }
            }
        }
    }

    /**
     * Handle collision between ball and wall
     * @param dx X distance from ball to closest wall point
     * @param dy Y distance from ball to closest wall point
     * @param distanceSquared Squared distance from ball to wall
     * @param left Left coordinate of wall
     * @param top Top coordinate of wall
     * @param right Right coordinate of wall
     * @param bottom Bottom coordinate of wall
     */
    private fun handleWallCollision(
        dx: Float,
        dy: Float,
        distanceSquared: Float,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ) {
        bitmapLock.withLock {
            val distance = sqrt(distanceSquared)
            if (distance > 0) {
                // Calculate penetration depth and resolve collision
                val penetration = ballRadius - distance
                currentBallState.x += (dx / distance) * penetration
                currentBallState.y += (dy / distance) * penetration

                // Calculate collision normal vector
                val normalX = dx / distance
                val normalY = dy / distance

                // Calculate collision point
                val collisionX = currentBallState.x - normalX * ballRadius
                val collisionY = currentBallState.y - normalY * ballRadius

                // Calculate distance from previous collision position
                val positionDiff = sqrt(
                    (collisionX - lastCollisionPositionX) * (collisionX - lastCollisionPositionX) +
                            (collisionY - lastCollisionPositionY) * (collisionY - lastCollisionPositionY)
                )

                // Check if collision is at same position as previous
                val isSamePosition = positionDiff < COLLISION_DISTANCE_THRESHOLD
                // Check if collision is in same direction as previous
                val isSameDirection = if (lastCollisionNormalX != 0f || lastCollisionNormalY != 0f) {
                    val dotProduct = normalX * lastCollisionNormalX + normalY * lastCollisionNormalY
                    dotProduct > 0.8f
                } else {
                    false
                }

                // Calculate impact velocity (speed in collision direction)
                val impactVelocity = abs(currentBallState.velocityX * normalX + currentBallState.velocityY * normalY)

                // Reflect velocity based on collision axis
                if (abs(dx) > abs(dy)) {
                    currentBallState.velocityX = -currentBallState.velocityX * PhysicsConstants.WALL_RESTITUTION
                } else {
                    currentBallState.velocityY = -currentBallState.velocityY * PhysicsConstants.WALL_RESTITUTION
                }

                // Update collision tracking variables
                lastCollisionNormalX = normalX
                lastCollisionNormalY = normalY
                lastCollisionPositionX = collisionX
                lastCollisionPositionY = collisionY
                lastCollisionTime = System.currentTimeMillis()

                val currentTime = System.currentTimeMillis()

                // Detect rolling along wall
                if (isSamePosition && isSameDirection) {
                    if (!isRollingOnWall) {
                        isRollingOnWall = true
                        rollingWallNormalX = normalX
                        rollingWallNormalY = normalY
                        rollingStartTime = currentTime
                    } else if (currentTime - rollingStartTime > ROLLING_CONFIRM_TIME) {
                        consecutiveCollisions = 0
                    }
                } else {
                    isRollingOnWall = false
                    rollingWallNormalX = 0f
                    rollingWallNormalY = 0f
                    rollingStartTime = 0L
                }

                // Check if rolling is confirmed
                val isRollingConfirmed = isRollingOnWall && (currentTime - rollingStartTime > ROLLING_CONFIRM_TIME)
                // Determine if haptic feedback should be triggered
                val shouldTriggerHaptic = !isRollingConfirmed &&
                        impactVelocity >= PhysicsConstants.MIN_EFFECTIVE_IMPACT_VELOCITY &&
                        (!isSamePosition || consecutiveCollisions < MAX_CONSECUTIVE_COLLISIONS)

                // Trigger haptic feedback if needed
                if (shouldTriggerHaptic) {
                    triggerImpactHaptic(impactVelocity)
                    consecutiveCollisions++
                }
            } else {
                // Handle edge case where ball is exactly at wall position
                handleEdgeCollision(left, top, right, bottom)
            }
        }
    }

    /**
     * Handle edge collision (ball is exactly at wall position)
     * @param left Left coordinate of wall
     * @param top Top coordinate of wall
     * @param right Right coordinate of wall
     * @param bottom Bottom coordinate of wall
     */
    private fun handleEdgeCollision(left: Float, top: Float, right: Float, bottom: Float) {
        bitmapLock.withLock {
            with(currentBallState) {
                // Calculate distance to each wall edge
                val distToLeft = x - left
                val distToRight = right - x
                val distToTop = y - top
                val distToBottom = bottom - y

                // Find closest edge
                val minDist = minOf(distToLeft, distToRight, distToTop, distToBottom)

                // Handle collision with closest edge
                when (minDist) {
                    distToLeft -> {
                        val previousVelocityX = velocityX
                        x = left - ballRadius
                        velocityX = -velocityX * PhysicsConstants.WALL_RESTITUTION

                        val impactVelocity = abs(previousVelocityX)
                        if (previousVelocityX > 0 && impactVelocity > HapticConstants.BOUNDARY_MIN_IMPACT_VELOCITY) {
                            triggerBoundaryHaptic(impactVelocity)
                        }
                    }
                    distToRight -> {
                        val previousVelocityX = velocityX
                        x = right + ballRadius
                        velocityX = -velocityX * PhysicsConstants.WALL_RESTITUTION

                        val impactVelocity = abs(previousVelocityX)
                        if (previousVelocityX < 0 && impactVelocity > HapticConstants.BOUNDARY_MIN_IMPACT_VELOCITY) {
                            triggerBoundaryHaptic(impactVelocity)
                        }
                    }
                    distToTop -> {
                        val previousVelocityY = velocityY
                        y = top - ballRadius
                        velocityY = -velocityY * PhysicsConstants.WALL_RESTITUTION

                        val impactVelocity = abs(previousVelocityY)
                        if (previousVelocityY > 0 && impactVelocity > HapticConstants.BOUNDARY_MIN_IMPACT_VELOCITY) {
                            triggerBoundaryHaptic(impactVelocity)
                        }
                    }
                    distToBottom -> {
                        val previousVelocityY = velocityY
                        y = bottom + ballRadius
                        velocityY = -velocityY * PhysicsConstants.WALL_RESTITUTION

                        val impactVelocity = abs(previousVelocityY)
                        if (previousVelocityY < 0 && impactVelocity > HapticConstants.BOUNDARY_MIN_IMPACT_VELOCITY) {
                            triggerBoundaryHaptic(impactVelocity)
                        }
                    }
                }
            }
        }
    }

    /**
     * Check if ball collided with any hole
     * @param oldX Previous X position of ball
     * @param oldY Previous Y position of ball
     * @return Pair of hole coordinates if collision detected, null otherwise
     */
    private fun checkHoleCollisions(oldX: Float, oldY: Float): Pair<Float, Float>? {
        bitmapLock.withLock {
            if (!isHoleDisplayed) return null

            // Check collision with each hole
            level?.holes?.forEach { hole ->
                val holeX = calculateScaledX(hole.x)
                val holeY = calculateScaledY(hole.y)

                val holeEffectiveRadius = baseHoleRadius * scaleFactor * holeDetectionRatio
                val ballCollisionRadius = ballRadius * PhysicsConstants.BALL_HOLE_COLLISION_RATIO

                // Check distance from ball center to hole center
                val distance = sqrt((currentBallState.x - holeX) * (currentBallState.x - holeX) +
                        (currentBallState.y - holeY) * (currentBallState.y - holeY))
                if (distance < ballCollisionRadius + holeEffectiveRadius) {
                    return Pair(holeX, holeY)
                }

                // Check if ball path intersects with hole (for fast moving ball)
                if (oldX != currentBallState.x || oldY != currentBallState.y) {
                    val closestPoint = getClosestPointOnSegment(
                        oldX, oldY, currentBallState.x, currentBallState.y, holeX, holeY
                    )
                    val distanceToPath = sqrt(
                        (closestPoint.first - holeX) * (closestPoint.first - holeX) +
                                (closestPoint.second - holeY) * (closestPoint.second - holeY)
                    )
                    if (distanceToPath < ballCollisionRadius + holeEffectiveRadius) {
                        return Pair(holeX, holeY)
                    }
                }
            }
            return null
        }
    }

    /**
     * Check if ball reached the goal
     * @return Pair of goal coordinates if reached, null otherwise
     */
    private fun checkGoal(): Pair<Float, Float>? {
        bitmapLock.withLock {
            if (!isEndDisplayed) return null

            level?.let {
                val endX = calculateScaledX(it.endX)
                val endY = calculateScaledY(it.endY)

                // Calculate distance from ball to goal
                val distance = sqrt((currentBallState.x - endX) * (currentBallState.x - endX) +
                        (currentBallState.y - endY) * (currentBallState.y - endY))

                // Calculate effective collision radii
                val goalEffectiveRadius = baseEndRadius * scaleFactor * goalDetectionRatio
                val ballCollisionRadius = ballRadius * PhysicsConstants.BALL_COLLISION_RATIO

                // Check if ball is within goal detection area
                if (distance < ballCollisionRadius + goalEffectiveRadius) {
                    return Pair(endX, endY)
                }
            }
            return null
        }
    }

    /**
     * Start animation for game events
     * @param type Type of animation to start
     * @param targetX Target X position for animation
     * @param targetY Target Y position for animation
     */
    private fun startAnimation(type: AnimationType, targetX: Float, targetY: Float) {
        bitmapLock.withLock {
            if (isAnimating) return

            // Initialize animation parameters
            isAnimating = true
            animationType = type
            animationStartX = currentBallState.x
            animationStartY = currentBallState.y
            animationTargetX = targetX
            animationTargetY = targetY
            animationStartTime = System.currentTimeMillis()
            animationProgress = 0f

            // Stop ball movement during animation
            currentBallState.velocityX = 0f
            currentBallState.velocityY = 0f

            // Play sound and vibration effects based on animation type
            when (type) {
                AnimationType.HOLE_FALL -> {
                    triggerVibration(300)
                    resourceLoader.playSound(SoundType.HOLE)
                }
                AnimationType.GOAL_SUCCESS -> {
                    resourceLoader.playSound(SoundType.LEVEL_COMPLETE)
                }
                AnimationType.NONE -> {}
            }
        }
    }

    /**
     * Update animation progress each frame
     */
    private fun updateAnimation() {
        val currentTime = System.currentTimeMillis()
        val elapsed = currentTime - animationStartTime
        // Calculate animation progress (0-1)
        animationProgress = (elapsed.toFloat() / RenderConstants.ANIMATION_DURATION).coerceIn(0f, 1f)

        // Update ball position based on animation type
        when (animationType) {
            AnimationType.HOLE_FALL -> {
                // Eased progress for smooth fall animation
                val easedProgress = animationProgress * animationProgress
                currentBallState.x = animationStartX + (animationTargetX - animationStartX) * easedProgress
                currentBallState.y = animationStartY + (animationTargetY - animationStartY) * easedProgress
            }
            AnimationType.GOAL_SUCCESS -> {
                // Snap ball to goal position for success animation
                currentBallState.x = animationTargetX
                currentBallState.y = animationTargetY
            }
            AnimationType.NONE -> {}
        }

        // Finish animation when complete
        if (animationProgress >= 1f) {
            finishAnimation()
        }
    }

    /**
     * Complete animation and trigger appropriate actions
     */
    private fun finishAnimation() {
        bitmapLock.withLock {
            val completedType = animationType
            isAnimating = false
            animationProgress = 0f
            animationType = AnimationType.NONE

            // Handle post-animation actions
            when (completedType) {
                AnimationType.HOLE_FALL -> {
                    // Reset ball position after falling into hole
                    resetBall()
                    mainHandler.postAtFrontOfQueue { onFallInHole?.invoke() }
                }
                AnimationType.GOAL_SUCCESS -> {
                    // Trigger level completion
                    if (!levelCompletedTriggered) {
                        levelCompletedTriggered = true
                        levelCompleted = true
                        gameThread?.running = false
                        mainHandler.postAtFrontOfQueue {
                            onLevelComplete?.invoke()
                            Thread {
                                bitmapLock.withLock {
                                    releaseScaledBitmaps()
                                    releaseBlurredBitmaps()
                                }
                            }.start()
                        }
                    }
                }
                AnimationType.NONE -> {}
            }
        }
    }

    /**
     * Trigger haptic feedback for wall impacts
     * @param impactVelocity Velocity at impact (for scaling vibration strength)
     */
    private fun triggerImpactHaptic(impactVelocity: Float) {
        bitmapLock.withLock {
            val currentTime = System.currentTimeMillis()
            // Check cooldown to prevent excessive vibration
            if (currentTime - lastHapticTime < HapticConstants.HAPTIC_COOLDOWN) return

            lastHapticTime = currentTime

            // Normalize impact velocity for vibration scaling
            val normalizedImpact = ((impactVelocity - HapticConstants.MIN_IMPACT_VELOCITY) /
                    (HapticConstants.MAX_IMPACT_VELOCITY - HapticConstants.MIN_IMPACT_VELOCITY)).coerceIn(0f, 1f)

            // Calculate vibration parameters based on impact strength
            val duration = (HapticConstants.MIN_VIBRATION_MS +
                    (HapticConstants.MAX_VIBRATION_MS - HapticConstants.MIN_VIBRATION_MS) * normalizedImpact).toLong()
            val amplitude = (HapticConstants.MIN_VIBRATION_AMPLITUDE +
                    (HapticConstants.MAX_VIBRATION_AMPLITUDE - HapticConstants.MIN_VIBRATION_AMPLITUDE) * normalizedImpact).toInt()

            // Ensure minimum values
            val finalDuration = maxOf(duration, HapticConstants.MIN_VIBRATION_MS)
            val finalAmplitude = maxOf(amplitude, HapticConstants.MIN_VIBRATION_AMPLITUDE)

            // Trigger vibration
            triggerVibration(finalDuration, finalAmplitude)
        }
    }

    /**
     * Trigger haptic feedback for boundary impacts
     * @param impactVelocity Velocity at impact (for scaling vibration strength)
     */
    private fun triggerBoundaryHaptic(impactVelocity: Float) {
        bitmapLock.withLock {
            val currentTime = System.currentTimeMillis()
            // Check cooldown to prevent excessive vibration
            if (currentTime - lastBoundaryHapticTime < HapticConstants.BOUNDARY_HAPTIC_COOLDOWN) return

            lastBoundaryHapticTime = currentTime

            // Normalize impact velocity for vibration scaling
            val normalizedImpact = ((impactVelocity - HapticConstants.BOUNDARY_MIN_IMPACT_VELOCITY) /
                    (HapticConstants.MAX_IMPACT_VELOCITY - HapticConstants.BOUNDARY_MIN_IMPACT_VELOCITY)).coerceIn(0f, 1f)

            // Calculate vibration parameters based on impact strength
            val duration = (HapticConstants.MIN_VIBRATION_MS +
                    (HapticConstants.MAX_VIBRATION_MS - HapticConstants.MIN_VIBRATION_MS) * normalizedImpact).toLong()
            val amplitude = (HapticConstants.MIN_VIBRATION_AMPLITUDE +
                    (HapticConstants.MAX_VIBRATION_AMPLITUDE - HapticConstants.MIN_VIBRATION_AMPLITUDE) * normalizedImpact).toInt()

            // Ensure minimum values
            val finalDuration = maxOf(duration, HapticConstants.MIN_VIBRATION_MS)
            val finalAmplitude = maxOf(amplitude, HapticConstants.MIN_VIBRATION_AMPLITUDE)

            // Trigger vibration
            triggerVibration(finalDuration, finalAmplitude)
        }
    }

    /**
     * Trigger device vibration with specified parameters
     * @param duration Vibration duration in milliseconds
     * @param amplitude Vibration strength (0-255)
     */
    private fun triggerVibration(duration: Long, amplitude: Int = VibrationEffect.DEFAULT_AMPLITUDE) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(duration)
        }
    }

    /**
     * Main rendering function - draws all game elements
     * @param canvas Canvas to draw on
     */
    private fun drawGame(canvas: Canvas) {
        // Clear canvas with black background
        canvas.drawColor(Color.BLACK)

        // Draw background layers
        bitmapLock.withLock {
            drawBlurBackground(canvas)
            drawBackground(canvas)
        }

        // Draw holes if enabled
        if (isHoleDisplayed) {
            bitmapLock.withLock {
                drawHoles(canvas)
            }
        }

        // Draw walls
        bitmapLock.withLock {
            drawWalls(canvas)
        }

        // Draw goal if enabled
        if (isEndDisplayed) {
            bitmapLock.withLock {
                drawGoal(canvas)
            }
        }

        // Draw ball or animation
        bitmapLock.withLock {
            drawBallOrAnimation(canvas)
        }
    }

    /**
     * Draw blurred background effect
     * @param canvas Canvas to draw on
     */
    private fun drawBlurBackground(canvas: Canvas) {
        val blurBitmap = if (currentIsFacetBackground) blurredFacetBitmap else blurredMazeBitmap

        if (blurBitmap != null && !blurBitmap.isRecycled) {
            // Draw blurred background
            canvas.drawBitmap(blurBitmap, 0f, 0f, blurBackgroundPaint)

            // Draw darkening overlay
            canvas.drawRect(
                0f,
                0f,
                width.toFloat(),
                height.toFloat(),
                darkenMaskPaint
            )
        }
    }

    /**
     * Draw main game background
     * @param canvas Canvas to draw on
     */
    private fun drawBackground(canvas: Canvas) {
        val targetBitmap = if (currentIsFacetBackground) scaledFacetBitmap else scaledMazeBitmap

        if (targetBitmap != null && !targetBitmap.isRecycled) {
            // Draw scaled background
            canvas.drawBitmap(targetBitmap, offsetX, offsetY, backgroundPaint)
        } else {
            // Rebuild background bitmaps if missing
            post {
                adjustBackgroundBitmapsAsync(width.toFloat(), height.toFloat())
            }
        }
    }

    /**
     * Draw all holes in the level
     * @param canvas Canvas to draw on
     */
    private fun drawHoles(canvas: Canvas) {
        level?.holes?.forEach { hole ->
            val holeX = calculateScaledX(hole.x)
            val holeY = calculateScaledY(hole.y)

            resourceLoader.holeBitmap?.let { bmp ->
                if (!bmp.isRecycled) {
                    // Draw hole bitmap centered at hole position
                    canvas.withSave {
                        translate(holeX, holeY)
                        scale(scaleFactor, scaleFactor)
                        drawBitmap(bmp, -bmp.width / 2f, -bmp.height / 2f, holePaint)
                    }
                }
            }
        }
    }

    /**
     * Draw all walls in the level (shadow first, then wall)
     * @param canvas Canvas to draw on
     */
    private fun drawWalls(canvas: Canvas) {
        resourceLoader.wallShader?.let { shader ->
            wallPaint.shader = shader

            // Draw wall shadows first
            level?.walls?.forEach { wall ->
                drawWallElement(canvas, wall, shadowPaint)
            }

            // Draw walls on top of shadows
            level?.walls?.forEach { wall ->
                drawWallElement(canvas, wall, wallPaint)
            }
        }
    }

    /**
     * Draw single wall element
     * @param canvas Canvas to draw on
     * @param wall Wall data to draw
     * @param paint Paint to use for drawing
     */
    private fun drawWallElement(canvas: Canvas, wall: Level.Wall, paint: Paint) {
        val extraThickness = RenderConstants.WALL_EXTRA_THICKNESS_BASE * scaleFactor *
                (RenderConstants.WALL_THICKNESS_MULTIPLIER - 1f)
        val left = calculateScaledX(wall.left) - extraThickness
        val top = calculateScaledY(wall.top) - extraThickness
        val right = calculateScaledX(wall.right) + extraThickness
        val bottom = calculateScaledY(wall.bottom) + extraThickness

        // Draw wall rectangle
        canvas.drawRect(left, top, right, bottom, paint)
    }

    /**
     * Draw goal/end point
     * @param canvas Canvas to draw on
     */
    private fun drawGoal(canvas: Canvas) {
        level?.let { level ->
            val endX = calculateScaledX(level.endX)
            val endY = calculateScaledY(level.endY)

            // Draw goal bitmap
            resourceLoader.endBitmap?.let { bmp ->
                if (!bmp.isRecycled) {
                    canvas.withSave {
                        translate(endX, endY)
                        scale(scaleFactor, scaleFactor)
                        drawBitmap(bmp, -bmp.width / 2f, -bmp.height / 2f, goalPaint)
                    }
                }
            }

            // Draw goal animation if active
            if (isAnimating && animationType == AnimationType.GOAL_SUCCESS) {
                drawGoalAnimation(canvas, endX, endY)
            }
        }
    }

    /**
     * Draw ball or active animation
     * @param canvas Canvas to draw on
     */
    private fun drawBallOrAnimation(canvas: Canvas) {
        if (!isBallPositionInitialized) return

        when {
            isAnimating -> {
                // Draw appropriate animation based on type
                when (animationType) {
                    AnimationType.HOLE_FALL -> drawHoleAnimation(canvas)
                    AnimationType.GOAL_SUCCESS -> {}
                    AnimationType.NONE -> drawBall(canvas)
                }
            }
            !levelCompleted -> drawBall(canvas)  // Draw normal ball if game is active
        }
    }

    /**
     * Draw ball at current position
     * @param canvas Canvas to draw on
     */
    private fun drawBall(canvas: Canvas) {
        resourceLoader.ballBitmap?.let { bmp ->
            if (!bmp.isRecycled) {
                // Draw ball bitmap centered at current position
                canvas.withSave {
                    translate(currentBallState.x, currentBallState.y)
                    scale(scaleFactor, scaleFactor)
                    drawBitmap(bmp, -bmp.width / 2f, -bmp.height / 2f, ballPaint)
                }
            }
        }
    }

    /**
     * Draw hole fall animation
     * @param canvas Canvas to draw on
     */
    private fun drawHoleAnimation(canvas: Canvas) {
        resourceLoader.getHoleAnimFrame(animationProgress)?.let { frame ->
            if (!frame.isRecycled) {
                // Calculate animation scale
                val targetSize = (resourceLoader.holeBitmap?.width?.toFloat() ?: 66f) * scaleFactor
                val animScale = targetSize / frame.width.toFloat()

                // Draw animation frame centered at hole position
                canvas.withSave {
                    translate(animationTargetX, animationTargetY)
                    scale(animScale, animScale)
                    drawBitmap(frame, -frame.width / 2f, -frame.height / 2f, animPaint)
                }
            }
        }
    }

    /**
     * Draw goal success animation
     * @param canvas Canvas to draw on
     * @param endX Goal X position
     * @param endY Goal Y position
     */
    private fun drawGoalAnimation(canvas: Canvas, endX: Float, endY: Float) {
        resourceLoader.getEndAnimFrame(animationProgress)?.let { frame ->
            if (!frame.isRecycled) {
                // Calculate animation scale
                val animScale = (resourceLoader.endBitmap?.width?.toFloat() ?: 100f) / frame.width.toFloat() * scaleFactor

                // Draw animation frame centered at goal position
                canvas.withSave {
                    translate(endX, endY)
                    scale(animScale, animScale)
                    drawBitmap(frame, -frame.width / 2f, -frame.height / 2f, animPaint)
                }
            }
        }
    }

    /**
     * Calculate closest point on line segment to given point
     * Used for detecting fast moving ball collisions with holes
     * @param x1 Start X of segment
     * @param y1 Start Y of segment
     * @param x2 End X of segment
     * @param y2 End Y of segment
     * @param px Point X to check
     * @param py Point Y to check
     * @return Closest point on segment as Pair(x, y)
     */
    private fun getClosestPointOnSegment(
        x1: Float, y1: Float, x2: Float, y2: Float, px: Float, py: Float
    ): Pair<Float, Float> {
        val dx = x2 - x1
        val dy = y2 - y1
        val lengthSquared = dx * dx + dy * dy

        // If segment is a point, return the point
        if (lengthSquared == 0f) return Pair(x1, y1)

        // Calculate projection factor
        val t = ((px - x1) * dx + (py - y1) * dy) / lengthSquared
        // Clamp to segment range [0,1]
        val clampedT = t.coerceIn(0f, 1f)

        // Calculate closest point on segment
        return Pair(x1 + clampedT * dx, y1 + clampedT * dy)
    }

    /**
     * Apply Gaussian blur to bitmap using RenderScript
     * @param bitmap Source bitmap to blur
     * @return Blurred bitmap or null if failed
     */
    private fun blurBitmap(bitmap: Bitmap): Bitmap? {
        return try {
            // Create output bitmap with same configuration
            val outputBitmap = bitmap.config?.let { bitmap.copy(it, true) } ?: return null

            // Create RenderScript allocations
            val input = Allocation.createFromBitmap(renderScript, bitmap)
            val output = Allocation.createFromBitmap(renderScript, outputBitmap)

            // Create and configure blur script
            val blurScript = ScriptIntrinsicBlur.create(renderScript, Element.U8_4(renderScript))
            blurScript.setRadius(RenderConstants.BLUR_BACKGROUND_RADIUS.coerceIn(0.1f, 25f))
            blurScript.setInput(input)
            blurScript.forEach(output)

            // Copy blurred result to output bitmap
            output.copyTo(outputBitmap)

            // Clean up RenderScript resources
            input.destroy()
            output.destroy()
            blurScript.destroy()

            outputBitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Asynchronously create and scale background bitmaps
     * @param surfaceWidth Width of the surface
     * @param surfaceHeight Height of the surface
     */
    private fun adjustBackgroundBitmapsAsync(surfaceWidth: Float, surfaceHeight: Float) {
        Thread {
            bitmapLock.withLock {
                try {
                    // Release old bitmaps
                    releaseScaledBitmaps()
                    releaseBlurredBitmaps()

                    // Ensure resource loader is initialized
                    if (!::resourceLoader.isInitialized) {
                        resourceLoader = ResourceLoader(context)
                    }

                    // Get appropriate source bitmap based on background type
                    val sourceBitmap = if (currentIsFacetBackground) {
                        resourceLoader.facetBitmap
                    } else {
                        resourceLoader.mazeBitmap
                    }

                    if (sourceBitmap != null && !sourceBitmap.isRecycled) {
                        // Calculate scaling parameters
                        calculateScaleAndOffset(surfaceWidth, surfaceHeight)
                        val scaledWidth = (RenderConstants.ORIGINAL_WIDTH * scaleFactor).toInt()
                        val scaledHeight = (RenderConstants.ORIGINAL_HEIGHT * scaleFactor).toInt()

                        // Create scaled bitmap for game area
                        val scaledBitmap = try {
                            Bitmap.createScaledBitmap(sourceBitmap, scaledWidth, scaledHeight, true)
                        } catch (e: Exception) {
                            e.printStackTrace()
                            null
                        }

                        // Store scaled bitmap
                        if (scaledBitmap != null) {
                            if (currentIsFacetBackground) {
                                scaledFacetBitmap = scaledBitmap
                            } else {
                                scaledMazeBitmap = scaledBitmap
                            }
                        }

                        // Create full-screen blurred background
                        val fillScale = calculateFillScreenScale(surfaceWidth, surfaceHeight)
                        val fillWidth = (RenderConstants.ORIGINAL_WIDTH * fillScale).toInt()
                        val fillHeight = (RenderConstants.ORIGINAL_HEIGHT * fillScale).toInt()
                        val fillBitmap = Bitmap.createScaledBitmap(sourceBitmap, fillWidth, fillHeight, true)

                        // Crop to exact screen size
                        val cropX = maxOf(0, (fillWidth - surfaceWidth.toInt()) / 2)
                        val cropY = maxOf(0, (fillHeight - surfaceHeight.toInt()) / 2)
                        val cropWidth = minOf(surfaceWidth.toInt(), fillWidth - cropX)
                        val cropHeight = minOf(surfaceHeight.toInt(), fillHeight - cropY)

                        val croppedBitmap = Bitmap.createBitmap(
                            fillBitmap,
                            cropX,
                            cropY,
                            cropWidth,
                            cropHeight
                        )

                        // Apply blur effect
                        val blurredBitmap = blurBitmap(croppedBitmap)

                        // Store blurred bitmap
                        if (blurredBitmap != null) {
                            if (currentIsFacetBackground) {
                                blurredFacetBitmap = blurredBitmap
                            } else {
                                blurredMazeBitmap = blurredBitmap
                            }
                        }

                        // Clean up temporary bitmaps
                        fillBitmap.safeRecycle()
                        croppedBitmap.safeRecycle()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            // Trigger view redraw
            postInvalidate()
        }.start()
    }

    /**
     * Adjust wall texture for current scale factor
     */
    private fun adjustWallTexture() {
        bitmapLock.withLock {
            try {
                resourceLoader.wallBitmap?.let { wall ->
                    if (!wall.isRecycled) {
                        // Scale wall texture to current scale factor
                        val scaledSize = (wall.width * scaleFactor).toInt().coerceAtLeast(1)
                        val scaledWall = wall.scale(scaledSize, scaledSize, true)
                        // Update wall shader with scaled texture
                        resourceLoader.updateWallShader(scaledWall)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Release scaled background bitmaps to free memory
     */
    private fun releaseScaledBitmaps() {
        try {
            scaledMazeBitmap?.safeRecycle()
            scaledMazeBitmap = null

            scaledFacetBitmap?.safeRecycle()
            scaledFacetBitmap = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Release blurred background bitmaps to free memory
     */
    private fun releaseBlurredBitmaps() {
        try {
            blurredMazeBitmap?.safeRecycle()
            blurredMazeBitmap = null

            blurredFacetBitmap?.safeRecycle()
            blurredFacetBitmap = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Get vibrator service with backward compatibility
     * @param context Application context
     * @return Vibrator service instance
     */
    private fun getVibrator(context: Context): Vibrator {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    /**
     * Game loop thread - handles physics updates and rendering
     */
    inner class GameThread(private val surfaceHolder: SurfaceHolder) : Thread() {
        var running = false                                  // Thread running flag
        private val targetFrameTime = 1000_000_000 / RenderConstants.TARGET_FPS  // Target frame duration in nanoseconds
        private var lastFrameTime = System.nanoTime()        // Timestamp of last frame

        /**
         * Main game loop
         */
        override fun run() {
            while (running) {
                val currentTime = System.nanoTime()
                lastFrameTime = currentTime

                var canvas: Canvas? = null
                try {
                    // Lock canvas for drawing
                    canvas = surfaceHolder.lockCanvas()
                    if (canvas != null) {
                        synchronized(surfaceHolder) {
                            // Update physics if game is active
                            if (!isGamePaused && !levelCompleted) {
                                updatePhysics()
                            }
                            // Draw game elements
                            drawGame(canvas)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    // Release canvas after drawing
                    canvas?.let { surfaceHolder.unlockCanvasAndPost(it) }
                }

                // Calculate frame timing and sleep to maintain target FPS
                val frameTime = (System.nanoTime() - currentTime) / 1_000_000
                val sleepTime = targetFrameTime / 1_000_000 - frameTime
                if (sleepTime > 0) {
                    try {
                        // Sleep to cap frame rate at target FPS
                        sleep(sleepTime)
                    } catch (_: InterruptedException) {
                        currentThread().interrupt()
                    }
                }
            }
        }

        /**
         * Safely join the thread with exception handling
         */
        fun joinSafely() {
            try {
                join()
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Resource loading helper class for managing game assets
     * Handles bitmaps, sound effects, and animation frames
     */
    private inner class ResourceLoader(private val context: Context) {
        var ballBitmap: Bitmap? = null          // Bitmap for ball sprite
        var holeBitmap: Bitmap? = null          // Bitmap for hole sprite
        var endBitmap: Bitmap? = null           // Bitmap for goal/end sprite
        var wallBitmap: Bitmap? = null          // Bitmap for wall texture
        var mazeBitmap: Bitmap? = null          // Bitmap for maze background
        var facetBitmap: Bitmap? = null         // Bitmap for facet background

        private val holeAnimFrames = mutableListOf<Bitmap>()  // Animation frames for hole fall effect
        private val endAnimFrames = mutableListOf<Bitmap>()   // Animation frames for goal success effect

        private val soundPool: SoundPool        // SoundPool for playing short sound effects
        private var holeSoundId = 0             // Sound ID for hole fall effect
        private var levelCompleteSoundId = 0    // Sound ID for level completion

        var wallShader: BitmapShader? = null    // Shader for textured wall rendering
            private set

        /**
         * Initialize SoundPool and load all game resources
         */
        init {
            soundPool = createSoundPool()
            loadAllResources()
        }

        /**
         * Create and configure SoundPool instance with game audio attributes
         * @return Configured SoundPool instance
         */
        private fun createSoundPool(): SoundPool {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            return SoundPool.Builder()
                .setMaxStreams(5)                // Maximum concurrent sounds
                .setAudioAttributes(audioAttributes)
                .build()
        }

        /**
         * Load all game resources (bitmaps, sounds, animations)
         */
        private fun loadAllResources() {
            try {
                val options = BitmapFactory.Options().apply {
                    inScaled = false            // Disable automatic scaling
                    inPreferredConfig = Bitmap.Config.ARGB_8888  // High quality bitmap format
                    inPremultiplied = true      // Premultiply alpha for better blending
                }

                // Load main game bitmaps
                ballBitmap = BitmapFactory.decodeResource(resources, R.drawable.ball, options)
                holeBitmap = BitmapFactory.decodeResource(resources, R.drawable.hole, options)
                endBitmap = BitmapFactory.decodeResource(resources, R.drawable.end, options)
                wallBitmap = BitmapFactory.decodeResource(resources, R.drawable.wall, options)
                mazeBitmap = BitmapFactory.decodeResource(resources, R.drawable.maze, options)
                facetBitmap = BitmapFactory.decodeResource(resources, R.drawable.facet, options)

                // Calculate actual visible radii from bitmaps (accounting for transparency)
                baseBallRadius = ballBitmap?.calculateVisibleRadius() ?: ResourceConstants.DEFAULT_BALL_RADIUS
                baseHoleRadius = holeBitmap?.calculateVisibleRadius() ?: ResourceConstants.DEFAULT_HOLE_RADIUS
                baseEndRadius = endBitmap?.calculateVisibleRadius() ?: ResourceConstants.DEFAULT_END_RADIUS

                // Load animation frames
                loadHoleAnimationFrames(options)
                loadEndAnimationFrames(options)

                // Load sound effects
                holeSoundId = soundPool.load(context, R.raw.hole, 1)
                levelCompleteSoundId = soundPool.load(context, R.raw.level_complete, 1)

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        /**
         * Load individual animation frames for hole fall effect
         * @param options Bitmap decoding options
         */
        private fun loadHoleAnimationFrames(options: BitmapFactory.Options) {
            val frameCount = 20                 // Total number of animation frames
            val holeAnimResIds = mutableListOf<Int>()

            // Collect resource IDs for animation frames
            for (i in 1..frameCount) {
                val frameNumber = String.format("%03d", i)  // Format as 001, 002, etc.
                val resId = resources.getIdentifier(
                    "hole_anim_$frameNumber",
                    "drawable",
                    context.packageName
                )
                if (resId != 0) {
                    holeAnimResIds.add(resId)
                }
            }

            // Load each animation frame bitmap
            holeAnimResIds.forEach { resId ->
                try {
                    val frame = BitmapFactory.decodeResource(resources, resId, options)
                    holeAnimFrames.add(frame)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        /**
         * Load animation frames for goal success effect from sprite sheet
         * @param options Bitmap decoding options
         */
        private fun loadEndAnimationFrames(options: BitmapFactory.Options) {
            try {
                // Load sprite sheet containing all animation frames
                val endAnimSprite = BitmapFactory.decodeResource(resources, R.drawable.end_anim, options)
                // Calculate number of frames in sprite sheet
                val frameCount = endAnimSprite.width / ResourceConstants.END_ANIM_FRAME_WIDTH

                // Extract individual frames from sprite sheet
                for (i in 0 until frameCount) {
                    val frameBitmap = Bitmap.createBitmap(
                        endAnimSprite,
                        i * ResourceConstants.END_ANIM_FRAME_WIDTH,
                        0,
                        ResourceConstants.END_ANIM_FRAME_WIDTH,
                        ResourceConstants.END_ANIM_FRAME_HEIGHT
                    )
                    endAnimFrames.add(frameBitmap)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        /**
         * Play sound effect for game events
         * @param type Type of sound to play
         */
        fun playSound(type: SoundType) {
            val soundId = when (type) {
                SoundType.HOLE -> holeSoundId
                SoundType.LEVEL_COMPLETE -> levelCompleteSoundId
            }
            if (soundId != 0) {
                try {
                    // Play sound with full volume, normal pitch
                    soundPool.play(soundId, 1f, 1f, 1, 0, 1f)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        /**
         * Get current frame for hole fall animation based on progress
         * @param progress Animation progress (0-1)
         * @return Current animation frame bitmap or null if unavailable
         */
        fun getHoleAnimFrame(progress: Float): Bitmap? {
            if (holeAnimFrames.isEmpty()) return null
            // Calculate frame index based on animation progress
            val frameIndex = (progress * holeAnimFrames.size).toInt()
                .coerceIn(0, holeAnimFrames.size - 1)  // Ensure index is within valid range
            val frame = holeAnimFrames[frameIndex]
            return if (frame.isRecycled) null else frame
        }

        /**
         * Get current frame for goal success animation based on progress
         * @param progress Animation progress (0-1)
         * @return Current animation frame bitmap or null if unavailable
         */
        fun getEndAnimFrame(progress: Float): Bitmap? {
            if (endAnimFrames.isEmpty()) return null
            // Calculate frame index based on animation progress
            val frameIndex = (progress * endAnimFrames.size).toInt()
                .coerceIn(0, endAnimFrames.size - 1)  // Ensure index is within valid range
            val frame = endAnimFrames[frameIndex]
            return if (frame.isRecycled) null else frame
        }

        /**
         * Update wall shader with scaled wall texture
         * @param scaledWall Scaled wall texture bitmap
         */
        fun updateWallShader(scaledWall: Bitmap) {
            if (!scaledWall.isRecycled) {
                // Create tiled shader for wall texture
                wallShader = BitmapShader(scaledWall, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR)
            }
        }

        /**
         * Release all loaded resources to free memory
         */
        fun release() {
            try {
                // Release SoundPool resources
                soundPool.release()

                // Recycle all main bitmaps
                listOf(ballBitmap, holeBitmap, endBitmap, wallBitmap, mazeBitmap, facetBitmap).forEach { bitmap ->
                    bitmap?.safeRecycle()
                }

                // Null references to help garbage collection
                ballBitmap = null
                holeBitmap = null
                endBitmap = null
                wallBitmap = null
                mazeBitmap = null
                facetBitmap = null

                // Recycle animation frames
                holeAnimFrames.forEach { it.safeRecycle() }
                holeAnimFrames.clear()

                endAnimFrames.forEach { it.safeRecycle() }
                endAnimFrames.clear()

                // Release shader reference
                wallShader = null
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Safely recycle bitmap with exception handling
     * Prevents crashes from trying to recycle already recycled or mutable bitmaps
     */
    private fun Bitmap.safeRecycle() {
        try {
            // Only recycle if not already recycled and not mutable
            if (!isRecycled && !this.isMutable) {
                recycle()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Calculate visible radius of bitmap by checking opaque pixels
     * Used to get actual collision radius for circular sprites with transparent backgrounds
     * @return Visible radius of the bitmap
     */
    private fun Bitmap.calculateVisibleRadius(): Float {
        val centerX = width / 2          // Center X coordinate of bitmap
        val centerY = height / 2         // Center Y coordinate of bitmap
        var maxOpaqueRadius = 0f         // Maximum radius with opaque pixels
        val maxCheckRadius = minOf(width, height) / 2  // Maximum radius to check

        // Check pixels in radial pattern to find maximum visible radius
        for (angle in 0 until 360 step 5) {
            val radians = Math.toRadians(angle.toDouble()).toFloat()
            for (r in 0..maxCheckRadius) {
                // Calculate pixel position for current angle and radius
                val x = centerX + (r * cos(radians)).toInt()
                val y = centerY + (r * sin(radians)).toInt()

                // Stop if outside bitmap bounds
                if (x !in 0 until width || y !in 0 until height) break

                // Get pixel alpha value (transparency)
                val pixel = this[x, y]
                val alpha = (pixel shr 24) and 0xff

                // Consider pixel as visible if alpha > 128 (50% opaque)
                if (alpha > 128) {
                    val distance = sqrt(((x - centerX) * (x - centerX) + (y - centerY) * (y - centerY)).toFloat())
                    maxOpaqueRadius = maxOf(maxOpaqueRadius, distance)
                }
            }
        }

        // Return calculated radius or default value if no opaque pixels found
        return if (maxOpaqueRadius > 0) maxOpaqueRadius else width * 0.3f
    }

    /**
     * Clean up resources when view is detached from window
     * Prevents memory leaks and resource leaks
     */
    override fun onDetachedFromWindow() {
        bitmapLock.withLock {
            super.onDetachedFromWindow()
            // Release resource loader if initialized
            if (::resourceLoader.isInitialized) {
                resourceLoader.release()
            }
            // Stop sensor updates
            stopSensors()

            // Release blurred bitmaps
            releaseBlurredBitmaps()

            // Clean up RenderScript resources
            try {
                renderScript.destroy()
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Null bitmap references to help garbage collection
            scaledMazeBitmap = null
            scaledFacetBitmap = null
            wallPaint.shader = null

            // Reset state flags
            isSurfaceSizeCalculated = false
            isBallPositionInitialized = false
        }
    }

    /**
     * Handle view visibility changes
     * Pauses/resumes game and saves/restores state as needed
     */
    override fun onVisibilityChanged(changedView: android.view.View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        bitmapLock.withLock {
            if (visibility == VISIBLE) {
                // Re-initialize ball position if needed when view becomes visible
                if (level != null && isSurfaceSizeCalculated && !isBallPositionInitialized) {
                    initializeBallPosition()
                }
                // Restart sensors if game is not paused
                if (!isGamePaused) {
                    startSensors()
                }
            } else {
                // Save ball state when view is hidden
                persistentBallState.apply {
                    x = currentBallState.x
                    y = currentBallState.y
                    velocityX = currentBallState.velocityX
                    velocityY = currentBallState.velocityY
                }
                // Stop sensor updates to save battery
                stopSensors()
            }
        }
    }
}