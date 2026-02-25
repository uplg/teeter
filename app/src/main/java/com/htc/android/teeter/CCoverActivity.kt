package com.htc.android.teeter

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.BitmapDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.util.Locale

/**
 * CCoverActivity serves as the splash/cover screen activity for the Teeter game application.
 * It handles animation playback, sensor availability checks, debug mode activation,
 * and transitions to the main game activity (CTeeterActivity).
 *
 * Key responsibilities:
 * - Check for accelerometer sensor availability (required for game functionality)
 * - Play splash animation and associated sound effects
 * - Handle debug mode activation through sequential clicks
 * - Manage smooth transition to main game activity
 * - Clean up resources properly to prevent memory leaks
 */
class CCoverActivity : AppCompatActivity() {
    companion object {
        // Log tag for debugging and logging purposes
        private const val TAG = "CCoverActivity"

        // Intent extra key for passing debug mode status to main activity
        const val EXTRA_DEBUG_MODE = "debug_mode"

        // Animation frame configuration constants
        private const val ANIM_FRAME_COUNT = 60                // Total number of animation frames
        private const val ANIM_FRAME_PREFIX = "splash_"       // Prefix for animation frame resource names
        private const val ANIM_FRAME_FORMAT = "%04d"          // Format string for frame numbering (4-digit with leading zeros)
        private const val ANIM_FRAME_DURATION_FIRST_HALF = 25 // Frame duration (ms) for first 30 frames
        private const val ANIM_FRAME_DURATION_SECOND_HALF = 50// Frame duration (ms) for last 30 frames
        private const val ANIM_TRIGGER_SOUND_FRAME = 30       // Frame index to trigger sound playback
        private const val ANIM_TRIGGER_FADE_FRAME = 59        // Frame index to trigger fade-out animation

        // Time delay constants (in milliseconds)
        private const val DELAY_GAME_EXIT_NO_SENSOR = 5000L   // Delay before exiting if no accelerometer
        private const val DELAY_GAME_START = 500L             // Delay before starting game animation
        private const val DELAY_GAME_ENTER = 200L             // Delay before launching main activity
        private const val DELAY_FADE_OUT_OFFSET = 500L        // Offset before starting fade-out animation
        private const val DURATION_FADE_OUT = 1000L           // Duration of fade-out animation
        private const val DELAY_FORCE_JUMP = 3500L            // Fallback delay to force transition to main activity

        // Debug mode configuration
        private const val DEBUG_CLICK_THRESHOLD = 3           // Number of clicks needed to activate debug mode
        private const val TOAST_DURATION = Toast.LENGTH_SHORT // Duration for debug toast messages
        private const val DEBUG_CLICK_RESET_DELAY = 2000L     // Delay before resetting click counter
    }

    // UI Components
    private lateinit var mMainView: ConstraintLayout // Root layout of the activity
    private lateinit var ivBall: ImageView           // ImageView for displaying the ball animation

    // Sensor-related variables
    private var sensorManager: SensorManager? = null  // System service for accessing device sensors

    // Media and animation components
    private var levelCompletePlayer: MediaPlayer? = null        // MediaPlayer for level completion sound
    private var holeAnimation: CustomAnimationDrawable? = null  // Custom animation for the ball/hole effect

    // Debug UI components
    private var debugToast: Toast? = null  // Toast for displaying debug mode messages

    // State management variables
    private var hasAccelerometer = false           // Flag indicating if accelerometer is available
    private var isActivityPaused = false           // Flag indicating if activity is in paused state
    private var isGameStartPending = false         // Flag for pending game start when activity is paused
    private var hasAnimationCompleted = false      // Flag indicating if splash animation completed
    private var debugClickCount = 0                // Counter for debug mode activation clicks
    private var isDebugModeEnabled = false         // Flag if debug mode is enabled
    private var isDebugModeActivated = false       // Flag if debug mode is currently active

    // Coroutine management
    private val coroutineJob = Job()                                   // Parent job for coroutine cancellation
    private val coroutineScope = CoroutineScope(Dispatchers.Main + coroutineJob)  // Coroutine scope tied to main thread

    // Handler instances for async operations
    private val clickHandler = ClickHandler(this)  // Handler for debug click reset operations
    private val gameHandler = GameHandler(this)    // Handler for game state messages

    /**
     * Enum representing different game messages for the GameHandler
     * Each message type triggers a specific action in the activity
     */
    private enum class GameMessage(val value: Int) {
        GAME_START(1),    // Trigger game animation start
        GAME_EXIT(2),     // Trigger activity exit
        GAME_ENTER(3),    // Launch main game activity
        GAME_FORCE_JUMP(4);// Force transition to main activity (fallback)

        companion object {
            /**
             * Converts integer value to corresponding GameMessage enum
             * @param value Integer message code
             * @return Corresponding GameMessage or null if not found
             */
            fun fromInt(value: Int): GameMessage? = entries.find { it.value == value }
        }
    }

    /**
     * Custom Handler for processing game-related messages
     * Uses WeakReference to avoid memory leaks with the activity
     *
     * @param activity Reference to the parent CCoverActivity
     */
    private class GameHandler(activity: CCoverActivity) : Handler(Looper.getMainLooper()) {
        private val activityRef = WeakReference(activity)  // Weak reference to avoid memory leaks

        /**
         * Processes incoming messages and dispatches to appropriate handlers
         * @param msg Message object containing what/arg1/arg2 data
         */
        override fun handleMessage(msg: Message) {
            val activity = activityRef.get() ?: return  // Get activity reference or return if null
            Log.i(TAG, "GameHandler received message: ${msg.what}")

            val gameMessage = GameMessage.fromInt(msg.what)
            when (gameMessage) {
                GameMessage.GAME_START -> {
                    Log.d(TAG, "Processing GAME_START message")
                    activity.handleGameStart()
                }
                GameMessage.GAME_EXIT -> {
                    Log.d(TAG, "Processing GAME_EXIT message, finishing activity")
                    activity.finish()
                }
                GameMessage.GAME_ENTER -> {
                    Log.d(TAG, "Processing GAME_ENTER message, launching main game activity")
                    activity.launchGameMainActivity()
                }
                GameMessage.GAME_FORCE_JUMP -> {
                    Log.w(TAG, "Processing GAME_FORCE_JUMP message (animation timeout fallback)")
                    if (!activity.hasAnimationCompleted) {
                        Log.w(TAG, "Animation not completed, force jump to main game activity")
                        activity.launchGameMainActivity()
                    }
                }
                null -> Log.e(TAG, "Received unknown message type: ${msg.what}")
            }
        }
    }

    /**
     * Custom Handler for managing debug click counter reset
     * Uses WeakReference to avoid memory leaks with the activity
     *
     * @param activity Reference to the parent CCoverActivity
     */
    private class ClickHandler(activity: CCoverActivity) : Handler(Looper.getMainLooper()) {
        private val activityRef = WeakReference(activity)  // Weak reference to avoid memory leaks

        /**
         * Posts a delayed action to reset the debug click counter
         * Cancels any existing pending reset actions first
         */
        fun postResetClickCount() {
            Log.d(TAG, "Posting reset debug click count with delay: $DEBUG_CLICK_RESET_DELAY ms")
            removeCallbacksAndMessages(null)  // Clear any existing pending resets
            postDelayed({
                activityRef.get()?.resetDebugClickCount()
                Log.d(TAG, "Debug click count reset completed")
            }, DEBUG_CLICK_RESET_DELAY)
        }
    }

    /**
     * Custom AnimationDrawable subclass that tracks frame progress and triggers
     * specific actions at predefined frame indices (sound playback, fade-out)
     */
    private inner class CustomAnimationDrawable : AnimationDrawable() {
        private var currentFrameIndex = 0      // Current animation frame index
        private var animationFinished = false  // Flag if animation has completed

        /**
         * Overridden run method to track frame progress and trigger actions
         * Called automatically by the AnimationDrawable for each frame
         */
        override fun run() {
            if (animationFinished) {
                Log.d(TAG, "Animation already finished, skip frame processing")
                super.run()
                return
            }

            super.run()
            Log.v(TAG, "Animation frame updated, current index: $currentFrameIndex")

            // Trigger sound playback at specified frame
            if (currentFrameIndex == ANIM_TRIGGER_SOUND_FRAME && !isSoundPlaying()) {
                Log.d(TAG, "Reached sound trigger frame ($ANIM_TRIGGER_SOUND_FRAME), playing sound")
                playLevelCompleteSound()
            }

            // Trigger fade-out animation at specified frame
            if (currentFrameIndex == ANIM_TRIGGER_FADE_FRAME && !animationFinished) {
                Log.d(TAG, "Reached fade out trigger frame ($ANIM_TRIGGER_FADE_FRAME), starting fade out")
                animationFinished = true
                hasAnimationCompleted = true
                startFadeOutAnimation()
            }

            currentFrameIndex++

            // Safety check for frame index bounds
            if (currentFrameIndex >= ANIM_FRAME_COUNT) {
                Log.w(TAG, "Animation frame index out of bounds ($currentFrameIndex >= $ANIM_FRAME_COUNT), mark as finished")
                animationFinished = true
                hasAnimationCompleted = true
            }
        }

        /**
         * Resets animation state to initial values
         * Call this before restarting the animation
         */
        fun resetFrameIndex() {
            Log.d(TAG, "Resetting animation frame index to 0")
            currentFrameIndex = 0
            animationFinished = false
            hasAnimationCompleted = false
        }
    }

    /**
     * SensorEventListener implementation for accelerometer monitoring
     * Currently only tracks accuracy changes (no active sensor processing needed for splash screen)
     */
    private val sensorListener = object : SensorEventListener {
        /**
         * Called when sensor accuracy changes
         * @param sensor The sensor whose accuracy changed
         * @param accuracy New accuracy value (SENSOR_STATUS_*)
         */
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            Log.v(TAG, "Sensor accuracy changed: sensor=${sensor?.name}, accuracy=$accuracy")
        }

        /**
         * Called when sensor values change (not used in this activity)
         * @param event SensorEvent containing new sensor data
         */
        override fun onSensorChanged(event: SensorEvent?) {
            // No implementation needed for splash screen
        }
    }

    /**
     * Called when the activity is first created
     * Initializes UI, checks sensor availability, sets up handlers and async operations
     *
     * @param savedInstanceState Saved instance state bundle (null if first creation)
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "CCoverActivity onCreate started")

        setupWindowFlags()          // Configure window properties
        setContentView(R.layout.activity_cover)  // Set layout
        initViews()                 // Initialize UI components
        setupBallClickListeners()   // Setup click handlers for debug mode
        checkAccelerometerSensor()  // Check for required accelerometer sensor

        // Post fallback message to force transition if animation gets stuck
        gameHandler.sendEmptyMessageDelayed(GameMessage.GAME_FORCE_JUMP.value, DELAY_FORCE_JUMP)
        Log.d(TAG, "Force jump message posted with delay: $DELAY_FORCE_JUMP ms")

        // Handle case where accelerometer is not available
        if (!hasAccelerometer) {
            Log.e(TAG, "Accelerometer sensor not available, handling no sensor scenario")
            handleNoSensorScenario()
        } else {
            Log.i(TAG, "Accelerometer sensor available, starting async resource loading")
            loadAnimationResourcesAsync()  // Load animation and sound resources in background
        }

        Log.i(TAG, "CCoverActivity onCreate completed")
    }

    /**
     * Called when activity resumes from paused state
     * Restores UI state and re-enables interactivity
     */
    override fun onResume() {
        super.onResume()
        Log.i(TAG, "CCoverActivity onResume")

        restoreBackground()         // Restore activity background
        isActivityPaused = false    // Update pause state
        ivBall.isClickable = true   // Re-enable ball clickability

        Log.d(TAG, "Activity resumed, clickable state of ball image: ${ivBall.isClickable}")
    }

    /**
     * Called when window focus changes
     * Manages pending game start when activity regains focus
     *
     * @param hasFocus True if window has focus, false otherwise
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        Log.d(TAG, "Window focus changed: hasFocus=$hasFocus, isGameStartPending=$isGameStartPending")

        // Process pending game start when window regains focus
        if (hasFocus && isGameStartPending) {
            Log.d(TAG, "Window gained focus, processing pending game start")
            isGameStartPending = false
            gameHandler.sendEmptyMessageDelayed(GameMessage.GAME_ENTER.value, DELAY_GAME_ENTER)
        } else if (!hasFocus) {
            Log.d(TAG, "Window lost focus, removing GAME_ENTER message")
            gameHandler.removeMessages(GameMessage.GAME_ENTER.value)
        }
    }

    /**
     * Called when activity is paused
     * Updates pause state flag
     */
    override fun onPause() {
        super.onPause()
        Log.i(TAG, "CCoverActivity onPause")

        isActivityPaused = true     // Update pause state

        Log.d(TAG, "Activity paused, isActivityPaused set to true")
    }

    /**
     * Called when activity is being destroyed
     * Cleans up all resources to prevent memory leaks
     */
    @SuppressLint("Recycle")
    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "CCoverActivity onDestroy started")

        cleanupResources()  // Release all media, animation and sensor resources
        coroutineJob.cancel()  // Cancel all coroutines
        Log.d(TAG, "Coroutine job cancelled")

        Log.i(TAG, "CCoverActivity onDestroy completed")
    }

    /**
     * Overridden to handle key events
     * Allows system handling for core keys (back, volume) but intercepts others
     *
     * @param event KeyEvent containing key press/release information
     * @return True if event was handled, false to pass to system
     */
    @SuppressLint("GestureBackNavigation")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        Log.v(TAG, "Dispatch key event: keyCode=${event.keyCode}, action=${event.action}")

        return when (event.keyCode) {
            // Allow system to handle core navigation/volume keys
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                Log.v(TAG, "Allowing system to handle key event: ${event.keyCode}")
                super.dispatchKeyEvent(event)
            }
            // Intercept all other key events
            else -> {
                Log.v(TAG, "Intercepting non-core key event: ${event.keyCode}")
                true
            }
        }
    }

    /**
     * Overridden to handle touch events
     * Ensures touch events are processed but consumed by the activity
     *
     * @param ev MotionEvent containing touch information
     * @return Always returns true to consume touch events
     */
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        Log.v(TAG, "Dispatch touch event: action=${ev?.action}")
        super.dispatchTouchEvent(ev)  // Let super handle first
        return true  // Consume all touch events
    }

    /**
     * Configures window flags for the activity
     * Sets FLAG_KEEP_SCREEN_ON to prevent screen from sleeping
     */
    private fun setupWindowFlags() {
        Log.d(TAG, "Setting up window flags: FLAG_KEEP_SCREEN_ON")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /**
     * Initializes UI components by finding views from layout
     * Throws exception if views are not found (lateinit properties)
     */
    private fun initViews() {
        Log.d(TAG, "Initializing view components")
        mMainView = findViewById(R.id.cl_splash_root)  // Root constraint layout
        ivBall = findViewById(R.id.iv_splash_ball)     // Ball animation ImageView
        Log.d(TAG, "View components initialized successfully")
    }

    /**
     * Checks for accelerometer sensor availability and registers listener
     * Updates hasAccelerometer flag based on sensor availability
     */
    private fun checkAccelerometerSensor() {
        Log.d(TAG, "Checking accelerometer sensor availability")

        // Get sensor manager system service
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager?

        // Check for default accelerometer sensor
        val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: run {
            Log.e(TAG, "Accelerometer sensor not found in system")
            return
        }

        // Try to register sensor listener to confirm availability
        hasAccelerometer = try {
            val registered = sensorManager?.registerListener(sensorListener, accelerometer, SensorManager.SENSOR_DELAY_NORMAL) ?: false
            Log.d(TAG, "Accelerometer sensor listener registered: $registered")
            registered
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register sensor listener", e)
            false
        }

        Log.i(TAG, "Accelerometer sensor check completed: hasAccelerometer=$hasAccelerometer")
    }

    /**
     * Sets up click and touch listeners for the ball ImageView
     * Used for debug mode activation through sequential clicks
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupBallClickListeners() {
        Log.d(TAG, "Setting up ball click listeners")

        ivBall.apply {
            isClickable = true   // Enable click handling
            isFocusable = true   // Enable focus for accessibility

            // Click listener for debug mode activation
            setOnClickListener {
                Log.v(TAG, "Ball image view clicked")
                handleBallClick()
            }

            // Touch listener to handle UP events (additional click detection)
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP) {
                    Log.v(TAG, "Ball image view touch up event")
                    handleBallClick()
                    true
                } else {
                    false
                }
            }
        }

        Log.d(TAG, "Ball click listeners setup completed")
    }

    /**
     * Handles scenario where accelerometer sensor is not available
     * Shows alert dialog, sets landscape orientation, and schedules activity exit
     */
    private fun handleNoSensorScenario() {
        Log.w(TAG, "Handling no accelerometer sensor scenario")

        // Force landscape orientation (game requirement)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        // Schedule activity exit after delay
        gameHandler.sendEmptyMessageDelayed(GameMessage.GAME_EXIT.value, DELAY_GAME_EXIT_NO_SENSOR)
        Log.d(TAG, "GAME_EXIT message posted with delay: $DELAY_GAME_EXIT_NO_SENSOR ms")

        // Show user dialog about missing sensor
        showNoSensorAlertDialog()
        Log.d(TAG, "No sensor alert dialog shown")
    }

    /**
     * Loads animation frames and sound resources asynchronously
     * Uses coroutines to perform IO operations on background thread
     */
    private fun loadAnimationResourcesAsync() {
        Log.d(TAG, "Starting async animation resource loading")

        coroutineScope.launch(Dispatchers.IO) {
            Log.d(TAG, "Loading animation frames in IO thread")
            prepareAnimationFrames()  // Load animation frames from resources

            Log.d(TAG, "Loading sound resources in IO thread")
            prepareSoundResources()   // Initialize media player for sound effects

            // If accelerometer is available, delay then start game animation
            if (hasAccelerometer) {
                Log.d(TAG, "Delaying game start by $DELAY_GAME_START ms")
                delay(DELAY_GAME_START)

                // Switch back to main thread to update UI
                withContext(Dispatchers.Main) {
                    Log.d(TAG, "Sending GAME_START message to handler")
                    gameHandler.sendEmptyMessage(GameMessage.GAME_START.value)
                }
            }
        }
    }

    /**
     * Prepares animation frames by loading them from drawable resources
     * Optimizes memory usage with BitmapFactory options
     * Creates CustomAnimationDrawable with loaded frames
     */
    private fun prepareAnimationFrames() {
        Log.d(TAG, "Preparing animation frames, total frames: $ANIM_FRAME_COUNT")

        try {
            System.gc()  // Hint to garbage collector before loading bitmaps
            Log.v(TAG, "System GC called before loading animation frames")

            // Create custom animation drawable (one-shot disabled for potential restarts)
            holeAnimation = CustomAnimationDrawable().apply {
                isOneShot = false
                Log.v(TAG, "CustomAnimationDrawable created with oneShot=false")
            }

            // Configure bitmap options for memory optimization
            val bitmapOptions = BitmapFactory.Options().apply {
                inScaled = false              // Disable automatic scaling
                inPreferredConfig = Bitmap.Config.RGB_565  // Use memory-efficient bitmap format
                inPurgeable = true            // Allow bitmap to be purged when memory is low
                inInputShareable = true       // Allow sharing of input data
                Log.v(TAG, "Bitmap options configured for memory optimization")
            }

            // Load each animation frame from resources
            for (frameIndex in 0 until ANIM_FRAME_COUNT) {
                val frameNumber = frameIndex + 1
                // Create resource name from prefix and formatted frame number
                val resourceName = "$ANIM_FRAME_PREFIX${String.format(Locale.US,ANIM_FRAME_FORMAT, frameNumber)}"
                // Get resource ID from name
                val resourceId = resources.getIdentifier(resourceName, "drawable", packageName)

                // If resource exists, load and add to animation
                if (resourceId != 0) {
                    val bitmap = BitmapFactory.decodeResource(resources, resourceId, bitmapOptions)
                    // Set different frame duration for first/second halves
                    val frameDuration = if (frameIndex <= 29) ANIM_FRAME_DURATION_FIRST_HALF else ANIM_FRAME_DURATION_SECOND_HALF
                    holeAnimation?.addFrame(BitmapDrawable(resources, bitmap), frameDuration)
                    Log.v(TAG, "Loaded animation frame $frameNumber: $resourceName, duration: $frameDuration ms")
                } else {
                    Log.w(TAG, "Animation frame resource not found: $resourceName")
                }
            }

            Log.d(TAG, "Animation frames preparation completed, loaded frames: ${holeAnimation?.numberOfFrames}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare animation frames", e)
        }
    }

    /**
     * Prepares sound resources by initializing MediaPlayer
     * Configures volume and looping settings for level completion sound
     */
    private fun prepareSoundResources() {
        Log.d(TAG, "Preparing sound resources")

        try {
            // Create MediaPlayer for level complete sound effect
            levelCompletePlayer = MediaPlayer.create(this, R.raw.level_complete).apply {
                setVolume(1.0f, 1.0f)  // Set maximum volume
                isLooping = false       // Disable looping
                Log.v(TAG, "Level complete media player created, volume: 1.0, looping: false")
            }
            Log.d(TAG, "Sound resources preparation completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare sound resources", e)
            levelCompletePlayer = null  // Set to null on failure
        }
    }

    /**
     * Handles game start by attaching animation to ball ImageView and starting playback
     * Falls back to direct main activity launch if views are not initialized
     */
    private fun handleGameStart() {
        Log.d(TAG, "Handling game start logic")

        // Check if views are properly initialized
        if (::mMainView.isInitialized && ::ivBall.isInitialized) {
            // Attach animation to ball ImageView and start playback
            ivBall.background = holeAnimation
            holeAnimation?.resetFrameIndex()
            holeAnimation?.start()
            Log.i(TAG, "Animation started on ball image view successfully")
        } else {
            Log.w(TAG, "Views not initialized, skipping animation start, launching main activity directly")
            launchGameMainActivity()  // Fallback to direct launch
        }
    }

    /**
     * Handles ball ImageView clicks for debug mode activation
     * Tracks click count, shows progress toasts, and activates debug mode when threshold is reached
     */
    private fun handleBallClick() {
        // Ignore clicks if debug mode is already active
        if (isDebugModeActivated) {
            Log.v(TAG, "Debug mode already activated, ignoring ball click")
            return
        }

        debugClickCount++  // Increment click counter
        Log.v(TAG, "Debug click count increased to: $debugClickCount")

        // Schedule click counter reset (prevents accidental activation)
        clickHandler.postResetClickCount()

        val remainingClicks = DEBUG_CLICK_THRESHOLD - debugClickCount
        when (debugClickCount) {
            // Show progress toast for partial clicks
            in 1 until DEBUG_CLICK_THRESHOLD -> {
                Log.d(TAG, "Debug mode activation pending, remaining clicks: $remainingClicks")
                showDebugToast("$remainingClicks more clicks to enable debug mode")
            }
            // Activate debug mode when threshold is reached
            DEBUG_CLICK_THRESHOLD -> {
                Log.i(TAG, "Debug click threshold reached, enabling debug mode")
                isDebugModeEnabled = true
                isDebugModeActivated = true
                showDebugToast("Debug mode enabled")
                resetDebugClickCount()  // Reset counter after activation
            }
            // Reset counter if it exceeds threshold
            else -> {
                Log.v(TAG, "Debug click count exceeded threshold, resetting")
                resetDebugClickCount()
            }
        }
    }

    /**
     * Plays the level completion sound effect
     * Checks if MediaPlayer is available and not already playing
     */
    private fun playLevelCompleteSound() {
        Log.d(TAG, "Attempting to play level complete sound")

        try {
            levelCompletePlayer?.let { player ->
                // Only play if not already playing
                if (!player.isPlaying) {
                    player.start()
                    Log.d(TAG, "Level complete sound started playing")
                } else {
                    Log.v(TAG, "Level complete sound is already playing, skip")
                }
            } ?: Log.w(TAG, "Level complete media player is null, cannot play sound")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play level complete sound", e)
        }
    }

    /**
     * Starts the fade-out animation for smooth transition to main activity
     * Ensures animation runs on main UI thread
     */
    private fun startFadeOutAnimation() {
        Log.d(TAG, "Starting fade out animation, checking looper thread")

        // Check if we're on main thread, post to UI thread if not
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Log.d(TAG, "Not in main looper, posting fade out animation to UI thread")
            runOnUiThread { doStartFadeOutAnimation() }
            return
        }

        doStartFadeOutAnimation()  // Execute animation on main thread
    }

    /**
     * Executes the fade-out animation logic on main thread
     * Configures animation properties and sets up completion listener
     * Falls back to direct launch if animation fails
     */
    private fun doStartFadeOutAnimation() {
        Log.d(TAG, "Executing fade out animation logic")

        try {
            // Load standard fade-out animation from Android resources
            val fadeOutAnim = AnimationUtils.loadAnimation(this, android.R.anim.fade_out).apply {
                duration = DURATION_FADE_OUT          // Set animation duration
                startOffset = DELAY_FADE_OUT_OFFSET  // Set delay before animation starts
                fillAfter = true                     // Keep final animation state after completion

                // Setup animation listener for completion handling
                setAnimationListener(object : Animation.AnimationListener {
                    override fun onAnimationStart(animation: Animation?) {
                        Log.d(TAG, "Fade out animation started, duration: $DURATION_FADE_OUT ms, offset: $DELAY_FADE_OUT_OFFSET ms")
                    }

                    override fun onAnimationRepeat(animation: Animation?) = Unit  // No repeat handling needed

                    override fun onAnimationEnd(animation: Animation?) {
                        Log.d(TAG, "Fade out animation ended, isActivityPaused: $isActivityPaused")
                        // Launch main activity if not paused, otherwise mark as pending
                        if (!isActivityPaused) {
                            launchGameMainActivity()
                        } else {
                            isGameStartPending = true
                            Log.d(TAG, "Activity is paused, marking game start as pending")
                        }
                    }
                })
            }

            // Apply animation to root layout
            mMainView.startAnimation(fadeOutAnim)
            Log.d(TAG, "Fade out animation applied to main view")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start fade out animation", e)
            launchGameMainActivity()  // Fallback to direct launch on failure
        }
    }

    /**
     * Launches the main game activity (CTeeterActivity)
     * Passes debug mode status as intent extra and configures transition animation
     * Cleans up pending handler messages before launch
     */
    private fun launchGameMainActivity() {
        Log.d(TAG, "Launching main game activity, debug mode: $isDebugModeEnabled")

        // Remove fallback force jump message (animation completed successfully)
        gameHandler.removeMessages(GameMessage.GAME_FORCE_JUMP.value)
        Log.v(TAG, "Removed GAME_FORCE_JUMP message from handler")

        try {
            // Create intent for main game activity with debug mode extra
            val intent = Intent(this, CTeeterActivity::class.java).apply {
                putExtra(EXTRA_DEBUG_MODE, isDebugModeEnabled)
                Log.v(TAG, "Intent created with debug mode extra: $isDebugModeEnabled")
            }
            startActivity(intent)  // Launch main activity

            // Disable transition animation for seamless experience (deprecated but functional)
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
            Log.v(TAG, "Activity transition set to no animation")

            finish()  // Finish cover activity
            Log.d(TAG, "CCoverActivity finished after launching main game activity")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch CTeeterActivity", e)
            finish()  // Ensure activity finishes even on launch failure
        }
    }

    /**
     * Shows alert dialog informing user about missing accelerometer sensor
     * Provides quit button to exit application
     */
    private fun showNoSensorAlertDialog() {
        Log.d(TAG, "Showing no sensor alert dialog")

        AlertDialog.Builder(this)
            .setTitle(R.string.str_app_name)          // App name as dialog title
            .setCancelable(false)                     // Prevent dismissal by back button
            .setMessage(R.string.str_no_sensor)       // Message about missing sensor
            .setPositiveButton(R.string.str_btn_quit) { _, _ ->
                // Finish activity when quit button is clicked
                Log.d(TAG, "Quit button clicked in no sensor dialog, finishing activity")
                finish()
            }
            .create()
            .show()  // Display dialog

        Log.d(TAG, "No sensor alert dialog displayed to user")
    }

    /**
     * Shows debug mode toast message to user
     * Cancels any existing debug toast before showing new one
     *
     * @param message Text message to display
     * @param duration Toast duration (default: TOAST_DURATION/short)
     */
    private fun showDebugToast(message: String, duration: Int = TOAST_DURATION) {
        Log.v(TAG, "Showing debug toast: '$message', duration: $duration")

        // Cancel existing toast to prevent stacking
        debugToast?.cancel()
        Log.v(TAG, "Cancelled previous debug toast if exists")

        // Create and show new toast
        debugToast = Toast.makeText(applicationContext, message, duration).apply {
            show()
            Log.v(TAG, "New debug toast shown")
        }
    }

    /**
     * Resets debug click counter to zero
     * Called after successful debug mode activation or timeout
     */
    fun resetDebugClickCount() {
        Log.d(TAG, "Resetting debug click count from $debugClickCount to 0")
        debugClickCount = 0
    }

    /**
     * Restores main view background to default splash background
     * Called when activity resumes
     */
    private fun restoreBackground() {
        Log.d(TAG, "Restoring main view background to splash_bg")
        mMainView.setBackgroundResource(R.drawable.splash_bg)
    }

    /**
     * Checks if level completion sound is currently playing
     * Handles potential exceptions to prevent crashes
     *
     * @return True if sound is playing, false otherwise (or on error)
     */
    private fun isSoundPlaying(): Boolean {
        return try {
            val isPlaying = levelCompletePlayer?.isPlaying ?: false
            Log.v(TAG, "Checking sound playing status: $isPlaying")
            isPlaying
        } catch (_: Exception) {
            Log.w(TAG, "Exception while checking sound playing status, returning false")
            false
        }
    }

    /**
     * Cleans up all resources to prevent memory leaks
     * Releases media players, animations, sensors, and handlers
     * Called from onDestroy to ensure proper cleanup
     */
    private fun cleanupResources() {
        Log.d(TAG, "Cleaning up all resources to prevent memory leaks")

        // Remove all pending handler messages/callbacks
        gameHandler.removeCallbacksAndMessages(null)
        clickHandler.removeCallbacksAndMessages(null)
        Log.v(TAG, "Removed all callbacks and messages from handlers")

        // Unregister sensor listener and release sensor manager
        sensorManager?.unregisterListener(sensorListener)
        sensorManager = null
        Log.v(TAG, "Sensor listener unregistered and sensorManager set to null")

        // Cancel and release debug toast
        debugToast?.cancel()
        debugToast = null
        Log.v(TAG, "Debug toast cancelled and set to null")

        // Release media player resources
        levelCompletePlayer?.apply {
            try {
                if (isPlaying) {
                    stop()  // Stop playback if active
                    Log.v(TAG, "Level complete media player stopped")
                }
                release()  // Release media player resources
                Log.v(TAG, "Level complete media player released")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to release MediaPlayer", e)
            }
        }
        levelCompletePlayer = null  // Null reference to allow GC
        Log.v(TAG, "Level complete media player set to null")

        // Release animation resources on background thread
        coroutineScope.launch(Dispatchers.IO) {
            Log.v(TAG, "Releasing animation resources in IO thread")

            holeAnimation?.apply {
                try {
                    stop()              // Stop animation playback
                    resetFrameIndex()   // Reset animation state
                    Log.v(TAG, "Animation stopped and frame index reset")

                    // Recycle all bitmap frames to free memory
                    for (i in 0 until numberOfFrames) {
                        (getFrame(i) as? BitmapDrawable)?.bitmap?.let {
                            if (!it.isRecycled) {
                                it.recycle()  // Recycle bitmap if not already recycled
                                Log.v(TAG, "Recycled bitmap for animation frame $i")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to release animation resources", e)
                }
            }
            holeAnimation = null  // Null reference to allow GC
            Log.v(TAG, "Hole animation set to null")
        }

        // Clean up view resources
        try {
            if (::mMainView.isInitialized) {
                mMainView.removeAllViews()  // Remove child views
                mMainView.background = null // Clear background
                Log.v(TAG, "Main view cleared and background set to null")
            }
            if (::ivBall.isInitialized) {
                ivBall.background = null    // Clear ball animation background
                Log.v(TAG, "Ball image view background set to null")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clean up views", e)
        }

        Log.d(TAG, "Resource cleanup completed")
    }
}