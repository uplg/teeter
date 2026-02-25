package com.htc.android.teeter

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.WindowManager
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.htc.android.teeter.game.GameView
import com.htc.android.teeter.models.GameState
import com.htc.android.teeter.utils.GamePreferences
import com.htc.android.teeter.utils.LevelParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.util.Locale

/**
 * CTeeterActivity is the main game activity for the Teeter (balance ball) game application.
 * It manages the core game flow, including level loading, game state management,
 * UI transitions, dialog handling, and persistence of game progress.
 *
 * Key responsibilities:
 * - Initialize game view and sensor integration
 * - Manage game state (pause/resume/reset)
 * - Handle level completion and progression
 * - Persist game progress using shared preferences
 * - Manage UI transitions between game, level complete, and ranking screens
 * - Implement debug mode features for development/testing
 * - Handle system events (back press, activity lifecycle)
 */
class CTeeterActivity : AppCompatActivity(), CoroutineScope by MainScope() {

    companion object {
        // Log tag for debugging and logging purposes
        private const val TAG = "CTeeterActivity"

        // Dialog identifiers for different game dialog types
        private const val DIALOG_QUIT = 1                // Quit confirmation dialog
        private const val DIALOG_CONTINUE_RESTART = 2    // Continue/restart dialog after background resume

        // Handler message identifiers for async operations
        private const val MSG_RESUME_GAME = 291                  // Resume normal game play
        private const val MSG_RESUME_AFTER_RESTART = 1110        // Resume game after restart
        private const val MSG_RESET_GAME = 1929                  // Reset game progress

        // Time delay constants (in milliseconds)
        private const val LEVEL_TRANSITION_DELAY = 3000L         // Delay before transitioning to next level
        private const val SAVE_STATE_DELAY = 100L                // Short delay before saving game state
        private const val ANIMATION_DURATION = 200L              // Duration of fade in/out animations

        // Intent extra key for debug mode flag
        private const val EXTRA_DEBUG_MODE = "debug_mode"

        // Saved instance state keys
        private const val KEY_CURRENT_LEVEL = "currentLevel"     // Current game level
        private const val KEY_IS_GAME_PAUSED = "isGamePaused"    // Game pause state

        // Page type constants for UI state management
        private const val PAGE_TYPE_GAME = 0                     // Main game play screen
        private const val PAGE_TYPE_LEVEL_COMPLETE = 1           // Level completion summary screen
        private const val PAGE_TYPE_RANK = 2                     // Game ranking/scoreboard screen
    }

    // Core game view component that handles rendering and physics
    private lateinit var gameView: GameView

    // Game state model that tracks progress, time, attempts, and level information
    private val gameState = GameState()

    // Main handler for UI thread operations and delayed tasks
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    // Custom handler for dialog-related operations (uses WeakReference to prevent memory leaks)
    private val dialogHandler by lazy { DialogHandler(Looper.getMainLooper(), WeakReference(this)) }

    // Activity state flags
    private var isFirstLaunch = true               // Flag for initial app launch
    private var isDialogShowing = false            // Flag if dialog is currently displayed
    private var isResettingGame = false            // Flag if game is being reset
    private var isGamePaused = false               // Flag if game is in paused state
    private var isFromBackground = false           // Flag if activity resumed from background
    private var isDebugMode = false                // Flag if debug mode is enabled
    private var isAnimating = false                // Flag if UI animation is in progress

    // Current UI page/screen type
    private var currentPageType = PAGE_TYPE_GAME

    // Reusable toast for global notifications (prevents multiple toasts stacking)
    private var globalToast: Toast? = null

    // Convenience property to check if GameView is initialized
    private val isGameViewInitialized: Boolean
        get() = ::gameView.isInitialized

    // Convenience property to check if current screen is not the main game screen
    private val isNonGamePage: Boolean
        get() = currentPageType == PAGE_TYPE_LEVEL_COMPLETE || currentPageType == PAGE_TYPE_RANK

    /**
     * Called when the activity is first created.
     * Initializes window settings, game view, back press handling, and restores saved state.
     *
     * @param savedInstanceState Bundle containing saved instance state (null if first creation)
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate: Activity created, savedInstanceState: ${savedInstanceState != null}")

        // Check if debug mode is enabled from intent extra
        isDebugMode = intent.getBooleanExtra(EXTRA_DEBUG_MODE, false)
        Log.d(TAG, "onCreate: Debug mode is ${if (isDebugMode) "enabled" else "disabled"}")

        // Configure window for immersive game experience
        setupWindow()

        // Initialize game view and core game components
        initGameView()

        // Setup custom back button handling
        setupBackPressHandler()

        // Restore game state from saved instance if available
        restoreSavedState(savedInstanceState)
    }

    /**
     * Saves the current game state to the instance state bundle.
     * Called before the activity is destroyed or recreated (e.g., screen rotation).
     *
     * @param outState Bundle to save state information to
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.apply {
            putInt(KEY_CURRENT_LEVEL, gameState.currentLevel)
            putBoolean(KEY_IS_GAME_PAUSED, isGamePaused)
            putInt("currentPageType", currentPageType)
        }
        Log.d(
            TAG,
            "onSaveInstanceState: Saved level ${gameState.currentLevel}, paused: $isGamePaused, pageType: $currentPageType"
        )
    }

    /**
     * Restores game state from the instance state bundle.
     * Called after onCreate when recreating the activity with saved state.
     *
     * @param savedInstanceState Bundle containing saved state information
     */
    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        restoreSavedState(savedInstanceState)
        Log.d(
            TAG,
            "onRestoreInstanceState: Restored level ${gameState.currentLevel}, paused: $isGamePaused"
        )
    }

    /**
     * Called when the activity is paused (e.g., app goes to background).
     * Pauses game play and schedules game state saving.
     */
    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause: Activity paused, currentPageType: $currentPageType")

        // Only pause game and save state if on main game screen
        if (currentPageType == PAGE_TYPE_GAME) {
            pauseGame()

            // Schedule state save with short delay to avoid excessive IO operations
            mainHandler.postDelayed({ saveGameState() }, SAVE_STATE_DELAY)
        }

        // Update state flags
        isFirstLaunch = false
        isFromBackground = true

        // Cancel any pending toast notifications
        globalToast?.cancel()
        Log.d(TAG, "onPause: Page paused, save state scheduled if needed")
    }

    /**
     * Called when the activity is resumed (e.g., app returns to foreground).
     * Resumes game play or handles special resume scenarios (first launch, reset, background return).
     */
    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume: Activity resumed, dialog showing: $isDialogShowing, currentPageType: $currentPageType")

        // Ensure system bars are hidden for immersive experience
        hideSystemBars()

        // Skip resume logic if dialog is showing or on non-game screen
        if (isDialogShowing) return
        if (isNonGamePage) {
            Log.d(TAG, "onResume: Current page is non-game page, skip resume logic")
            return
        }

        // Handle different resume scenarios
        when {
            !isGameViewInitialized -> {
                Log.d(TAG, "onResume: GameView not initialized, skip resume")
                return
            }

            isFirstLaunch -> {
                // First launch - resume game normally
                resumeGame()
                isFirstLaunch = false
                Log.d(TAG, "onResume: First launch, game resumed")
            }

            isResettingGame -> {
                // Resume after game reset (zero velocity to prevent ball movement)
                handleResetResume()
                Log.d(TAG, "onResume: Game was resetting, handled reset resume")
            }

            else -> {
                // Resume from background - show continue/restart dialog
                handleBackgroundResume()
                Log.d(TAG, "onResume: Resumed from background, handled background resume")
            }
        }
    }

    /**
     * Called when the activity is being destroyed.
     * Cleans up coroutines, handlers, and other resources to prevent memory leaks.
     */
    override fun onDestroy() {
        Log.d(TAG, "onDestroy: Activity destroying")

        // Cancel all coroutines to prevent background operations after destruction
        cancel()

        // Remove all pending handler messages/callbacks
        mainHandler.removeCallbacksAndMessages(null)
        dialogHandler.removeCallbacksAndMessages(null)

        // Cancel any pending toast notifications
        globalToast?.cancel()

        super.onDestroy()
        Log.d(TAG, "onDestroy: Activity destroyed, resources cleaned up")
    }

    /**
     * Configures window settings for optimal game experience.
     * Enables immersive mode (hides system bars) and keeps screen on during game play.
     */
    private fun setupWindow() {
        // Enable full screen immersive mode
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Prevent screen from turning off during game play
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Log.d(TAG, "setupWindow: Window configured - immersive and keep screen on")
    }

    /**
     * Initializes the GameView component and sets up game play environment.
     * Loads saved game progress, configures game callbacks, and starts initial animation.
     */
    private fun initGameView() {
        // Set main game layout and hide system bars
        setContentViewAndHideBars(R.layout.activity_teeter)

        // Set current page type to game screen
        currentPageType = PAGE_TYPE_GAME

        // Get reference to GameView from layout
        gameView = findViewById(R.id.gameView)
        Log.d(TAG, "initGameView: GameView initialized")

        // Configure GameView properties and callbacks
        gameView.apply {
            // Keep screen on for game view specifically
            keepScreenOn = true
            // Start sensor monitoring for tilt controls
            startSensors()

            // Set callback for level completion
            onLevelComplete = ::onLevelComplete

            // Set callback for ball falling in hole (increment attempt count)
            onFallInHole = {
                gameState.retry()
                Log.d(TAG, "initGameView: Ball fell in hole, level attempts increased")
            }

            // Setup debug mode touch controls if enabled
            setTouchListenerForDebugMode()
        }

        // Load saved game progress asynchronously
        loadSavedGameProgressAsync()

        // Set initial game state and resume game play
        isGamePaused = false
        gameView.resumeGame()

        // Show fade-in animation on first launch
        if (isFirstLaunch) {
            fadeInGameView()
            Log.d(TAG, "initGameView: First launch, execute game view fade-in animation")
        }

        Log.d(TAG, "initGameView: Game initialized and resumed")
    }

    /**
     * Plays fade-in animation for the GameView component.
     * Creates smooth visual transition when game view is first displayed.
     */
    private fun fadeInGameView() {
        if (::gameView.isInitialized) {
            // Load standard fade-in animation from Android resources
            val fadeInAnim = AnimationUtils.loadAnimation(this, android.R.anim.fade_in)
            fadeInAnim.duration = ANIMATION_DURATION
            gameView.startAnimation(fadeInAnim)
            Log.d(TAG, "fadeInGameView: GameView fade-in animation (system) started")
        }
    }

    /**
     * Plays fade-out animation for the entire screen content.
     *
     * @param onComplete Callback function to execute when animation finishes
     */
    private fun fadeOutGameView(onComplete: () -> Unit = {}) {
        // Get root content view for animation
        val rootView = window.decorView.findViewById<android.view.View>(android.R.id.content)
        // Load standard fade-out animation from Android resources
        val fadeOutAnim = AnimationUtils.loadAnimation(this, android.R.anim.fade_out)
        fadeOutAnim.duration = ANIMATION_DURATION

        // Set animation listener to execute callback on completion
        fadeOutAnim.setAnimationListener(object : android.view.animation.Animation.AnimationListener {
            override fun onAnimationStart(animation: android.view.animation.Animation) {}
            override fun onAnimationEnd(animation: android.view.animation.Animation) {
                onComplete.invoke()
                Log.d(TAG, "fadeOutGameView: GameView fade-out animation (system) completed")
            }
            override fun onAnimationRepeat(animation: android.view.animation.Animation) {}
        })

        // Start fade-out animation
        rootView.startAnimation(fadeOutAnim)
        Log.d(TAG, "fadeOutGameView: GameView fade-out animation (system) started")
    }

    /**
     * Sets up custom back button handling for game-specific behavior.
     * Shows quit dialog on main game screen, exits app on non-game screens.
     */
    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Log.d(TAG, "setupBackPressHandler: Back button pressed, currentPageType: $currentPageType")

                // Exit app completely if on non-game screen
                if (isNonGamePage) {
                    finishAffinity()
                    return
                }

                // Show quit confirmation dialog on main game screen
                showGameDialog(DIALOG_QUIT)
            }
        })
        Log.d(TAG, "setupBackPressHandler: Back press handler configured")
    }

    /**
     * Restores game state from saved instance state bundle.
     *
     * @param savedInstanceState Bundle containing saved state (may be null)
     */
    private fun restoreSavedState(savedInstanceState: Bundle?) {
        savedInstanceState?.let {
            // Restore game state values from bundle
            gameState.currentLevel = it.getInt(KEY_CURRENT_LEVEL, 1)
            isGamePaused = it.getBoolean(KEY_IS_GAME_PAUSED, false)
            currentPageType = it.getInt("currentPageType", PAGE_TYPE_GAME)
            Log.d(
                TAG,
                "restoreSavedState: Restored level ${gameState.currentLevel}, paused: $isGamePaused, pageType: $currentPageType"
            )
        } ?: Log.d(TAG, "restoreSavedState: No saved state to restore")
    }

    /**
     * Loads saved game progress from shared preferences asynchronously.
     * Runs on background thread to avoid blocking UI, then updates game state on main thread.
     */
    private fun loadSavedGameProgressAsync() {
        launch(Dispatchers.IO) {
            Log.d(TAG, "loadSavedGameProgressAsync: Loading game progress in background")

            // Load saved progress from shared preferences
            val savedLevel = GamePreferences.getCurrentLevel(this@CTeeterActivity)
            val savedTotalTime = GamePreferences.getTotalTime(this@CTeeterActivity)
            val savedTotalAttempts = GamePreferences.getTotalAttempts(this@CTeeterActivity)

            // Update game state on main thread
            withContext(Dispatchers.Main) {
                gameState.apply {
                    currentLevel = savedLevel
                    totalTime = savedTotalTime
                    totalAttempts = savedTotalAttempts
                }

                Log.d(
                    TAG,
                    "loadSavedGameProgressAsync: Loaded progress - level: $savedLevel, total time: $savedTotalTime, total attempts: $savedTotalAttempts"
                )

                // Load the saved level
                loadLevelAsync(gameState.currentLevel)
            }
        }
    }

    /**
     * Handles game resume when returning from background.
     * Shows continue/restart dialog to prevent unexpected ball movement.
     */
    private fun handleBackgroundResume() {
        if (isNonGamePage) {
            Log.d(TAG, "handleBackgroundResume: Current page is non-game page, skip resume")
            return
        }

        // Get current content view to determine resume behavior
        val contentView = window.decorView.findViewById<android.view.View>(android.R.id.content)
        val currentContentView = (contentView as? android.view.ViewGroup)?.getChildAt(0)

        when (currentContentView) {
            // Show continue/restart dialog if returning to GameView from background
            is GameView if isFromBackground -> {
                Log.d(
                    TAG,
                    "handleBackgroundResume: Current view is GameView, show continue/restart dialog"
                )
                showGameDialog(DIALOG_CONTINUE_RESTART)
                isFromBackground = false
            }

            // Show continue/restart dialog if current view is not GameView
            !is GameView -> {
                Log.d(
                    TAG,
                    "handleBackgroundResume: Current view is not GameView, show continue/restart dialog"
                )
                showGameDialog(DIALOG_CONTINUE_RESTART)
            }

            // Resume game directly for other cases
            else -> {
                resumeGame()
                Log.d(TAG, "handleBackgroundResume: Resume game directly")
            }
        }
    }

    /**
     * Resumes game play after a reset with zero initial velocity.
     * Prevents ball from moving unexpectedly when game restarts.
     */
    private fun handleResetResume() {
        resumeGameWithZeroVelocity()
        gameView.startSensors()
        isResettingGame = false
        if (isGameViewInitialized) {
            gameView.setResettingGame(false)
        }
        Log.d(TAG, "handleResetResume: Game resumed with zero velocity after reset")
    }

    /**
     * Loads a specific game level asynchronously from level resources.
     *
     * @param levelNumber Number of the level to load (starting from 1)
     */
    private fun loadLevelAsync(levelNumber: Int) {
        launch(Dispatchers.IO) {
            Log.d(TAG, "loadLevelAsync: Loading level $levelNumber in background")

            // Parse level data from resources
            val level = LevelParser.loadLevel(this@CTeeterActivity, levelNumber)

            // Update GameView with loaded level on main thread
            withContext(Dispatchers.Main) {
                if (level != null) {
                    // Level loaded successfully - update game state and GameView
                    gameState.startLevel(levelNumber)
                    gameView.setLevel(level)
                    gameView.startSensors()
                    Log.d(TAG, "loadLevelAsync: Level $levelNumber loaded successfully")
                } else {
                    // Level load failed - fallback to level 1
                    Log.e(TAG, "loadLevelAsync: Failed to load level $levelNumber, fallback to level 1")
                    loadLevelAsync(1)
                }
            }
        }
    }

    /**
     * Handles level completion event from GameView.
     * Saves level score, updates game state, and transitions to appropriate next screen.
     */
    private fun onLevelComplete() {
        // Update game state for completed level
        gameState.completeLevel()
        Log.d(TAG, "onLevelComplete: Level ${gameState.currentLevel} completed")

        // Save level score asynchronously
        launch(Dispatchers.IO) {
            GamePreferences.saveLevelScore(
                this@CTeeterActivity,
                gameState.currentLevel,
                gameState.getLevelTime(),
                gameState.levelAttempts
            )
            saveGameState()
            Log.d(TAG, "onLevelComplete: Score saved for level ${gameState.currentLevel}")
        }

        // Animate out current view and handle next screen transition
        exitCurrentViewWithAnimation {
            if (gameState.currentLevel >= LevelParser.getTotalLevels()) {
                // All levels completed - show ranking screen
                Log.d(TAG, "onLevelComplete: All levels completed")

                fadeOutGameView {
                    launch(Dispatchers.IO) {
                        // Check if clear time is already recorded
                        val isRecorded = GamePreferences.isClearTimeRecorded(this@CTeeterActivity)

                        withContext(Dispatchers.Main) {
                            if (!isRecorded) {
                                // First time completing all levels - record time and show rank
                                displayRankPageAsync(gameState.totalTime)
                                launch(Dispatchers.IO) {
                                    GamePreferences.setClearTimeRecorded(this@CTeeterActivity, true)
                                }
                                Log.d(TAG, "onLevelComplete: First time clear, time recorded to rank")
                            } else {
                                // Clear time already recorded - show rank without adding
                                displayRankPageAsync(gameState.totalTime, isAddToRank = false)
                                Log.d(TAG, "onLevelComplete: Clear time already recorded, show rank only")
                            }
                        }
                    }
                }
            } else {
                // More levels available - show level transition screen
                Log.d(TAG, "onLevelComplete: Show level transition screen for next level")
                displayLevelTransitionScreen()
            }
        }
    }

    /**
     * Resets all game progress to initial state.
     * Clears saved preferences and reloads level 1 with fresh game state.
     */
    private fun resetGameProgress() {
        Log.d(TAG, "resetGameProgress: Resetting game progress")

        launch(Dispatchers.IO) {
            // Clear saved progress from shared preferences
            GamePreferences.resetProgress(this@CTeeterActivity)

            // Reset game state on main thread
            withContext(Dispatchers.Main) {
                gameState.apply {
                    currentLevel = 0
                    totalTime = 0
                    totalAttempts = 0
                    levelAttempts = 0
                }

                // Reset GameView if initialized
                if (isGameViewInitialized) {
                    gameView.apply {
                        setResettingGame(true)
                        forceResetBall()
                        pauseGame()
                        resumeGame()
                        startSensors()
                        setResettingGame(false)
                    }
                }

                // Load level 1 and update state
                loadLevelAsync(1)
                isResettingGame = false
                Log.d(TAG, "resetGameProgress: Game progress reset completed, loaded level 1")
            }
        }
    }

    /**
     * Displays the level completion transition screen with level statistics.
     * Shows level score and transitions to next level after delay.
     */
    private fun displayLevelTransitionScreen() {
        Log.d(
            TAG,
            "displayLevelTransitionScreen: Showing level transition screen for level ${gameState.currentLevel}"
        )

        // Pause game play during transition
        if (isGameViewInitialized) {
            gameView.pauseGame()
        }

        // Animate out game view and switch to level complete layout
        fadeOutGameView {
            switchToLevelCompleteLayout()
        }
    }

    /**
     * Switches to the level completion layout and displays level statistics.
     * Schedules transition to next level after specified delay.
     */
    private fun switchToLevelCompleteLayout() {
        // Set level complete layout and hide system bars
        setContentViewAndHideBars(R.layout.layout_level_complete)

        // Update current page type
        currentPageType = PAGE_TYPE_LEVEL_COMPLETE

        // Populate level statistics views
        findViewById<TextView>(R.id.level_caption).text =
            getString(R.string.str_level_caption, gameState.currentLevel)
        findViewById<TextView>(R.id.level_time_score).text = gameState.getLevelTime().formatTime()
        findViewById<TextView>(R.id.level_attempt_score).text = gameState.levelAttempts.toString()
        findViewById<TextView>(R.id.total_time_score).text = gameState.totalTime.formatTime()
        findViewById<TextView>(R.id.total_attempt_score).text = gameState.totalAttempts.toString()

        // Animate in level complete screen
        val rootView = window.decorView.findViewById<android.view.View>(android.R.id.content)
        val fadeInAnim = AnimationUtils.loadAnimation(this, android.R.anim.fade_in)
        fadeInAnim.duration = ANIMATION_DURATION
        rootView.startAnimation(fadeInAnim)

        // Schedule transition to next level after delay
        mainHandler.postDelayed({
            val nextLevel = gameState.currentLevel + 1
            Log.d(TAG,
                "displayLevelTransitionScreen: 3-second delay completed, start fade-out animation and load next level $nextLevel"
            )

            // Animate out and load next level
            exitCurrentViewWithAnimation {
                launch(Dispatchers.IO) {
                    // Save new current level to preferences
                    GamePreferences.saveCurrentLevel(this@CTeeterActivity, nextLevel)

                    withContext(Dispatchers.Main) {
                        // Reinitialize game view for next level
                        initGameView()
                        fadeInGameView()
                        Log.d(TAG,
                            "displayLevelTransitionScreen: Fade-out animation completed, load next level $nextLevel and start fade-in animation"
                        )
                    }
                }
            }
        }, LEVEL_TRANSITION_DELAY)
    }

    /**
     * Displays the ranking/scoreboard screen asynchronously.
     * Loads rank data from preferences and updates UI with animation.
     *
     * @param totalGameTime Total time taken to complete all levels
     * @param isAddToRank Whether to add current time to ranking (default: true)
     */
    private fun displayRankPageAsync(totalGameTime: Long, isAddToRank: Boolean = true) {
        Log.d(
            TAG,
            "displayRankPageAsync: Showing rank page, add to rank: $isAddToRank, total time: $totalGameTime"
        )

        // Pause game play if on game screen
        if (isGameViewInitialized) {
            gameView.pauseGame()
        }

        launch(Dispatchers.IO) {
            // Get rank list (add current time if specified)
            val finalRankArray = if (isAddToRank) {
                GamePreferences.addRankScore(this@CTeeterActivity, totalGameTime)
            } else {
                GamePreferences.getRankList(this@CTeeterActivity)
            }

            // Find index to highlight current score (if added to rank)
            val highlightIndex = if (isAddToRank) {
                finalRankArray.indexOfFirst { it == totalGameTime }
            } else {
                -1
            }
            Log.d(TAG, "displayRankPageAsync: Rank list loaded, highlight index: $highlightIndex")

            // Update rank UI on main thread
            withContext(Dispatchers.Main) {
                // Set rank layout and hide system bars
                setContentViewAndHideBars(R.layout.layout_score)

                // Update current page type
                currentPageType = PAGE_TYPE_RANK

                // Get rank text view IDs
                val rankViewIds = arrayOf(
                    R.id.rank_scores1, R.id.rank_scores2, R.id.rank_scores3,
                    R.id.rank_scores4, R.id.rank_scores5
                )

                // Populate rank scores and highlight current score if applicable
                rankViewIds.forEachIndexed { index, id ->
                    findViewById<TextView>(id).apply {
                        // Set rank time or empty string if no score
                        text = if (index < finalRankArray.size) finalRankArray[index].formatTime() else ""
                        // Default text color (white)
                        setTextColor(ContextCompat.getColor(context, android.R.color.white))

                        // Highlight current score if in rank list
                        if (index == highlightIndex) {
                            setTextColor(ContextCompat.getColor(this@CTeeterActivity, R.color.black))
                            setBackgroundColor(ContextCompat.getColor(this@CTeeterActivity, R.color.white))
                            Log.d(TAG, "displayRankPageAsync: Highlighted rank position $index")
                        }
                    }
                }

                // Setup restart button click listener
                findViewById<Button>(R.id.btn_restart).setOnClickListener {
                    Log.d(TAG, "displayRankPageAsync: Restart button clicked")
                    isResettingGame = true
                    // Animate out and reset game
                    exitCurrentViewWithAnimation {
                        resetGameProgress()
                        initGameView()
                        fadeInGameView()
                        isResettingGame = false
                    }
                }

                // Setup quit button click listener
                findViewById<Button>(R.id.btn_quit).setOnClickListener {
                    Log.d(TAG, "displayRankPageAsync: Quit button clicked, finishing activity")
                    finishAffinity()
                }

                // Animate in rank page
                animateRankPageIn()
            }
        }
    }

    /**
     * Plays fade-in animation for the ranking screen with state management.
     * Prevents multiple simultaneous animations.
     */
    private fun animateRankPageIn() {
        // Prevent animation if already animating
        if (isAnimating) return

        isAnimating = true

        // Get root view for animation
        val rootView = window.decorView.findViewById<android.view.View>(android.R.id.content)

        // Configure fade-in animation
        val fadeInAnim = AnimationUtils.loadAnimation(this, android.R.anim.fade_in)
        fadeInAnim.duration = ANIMATION_DURATION

        // Set animation listener to update state
        fadeInAnim.setAnimationListener(object : android.view.animation.Animation.AnimationListener {
            override fun onAnimationStart(animation: android.view.animation.Animation) {}
            override fun onAnimationEnd(animation: android.view.animation.Animation) {
                isAnimating = false
                Log.d(TAG, "animateRankPageIn: Rank page fade-in animation (system) completed")
            }
            override fun onAnimationRepeat(animation: android.view.animation.Animation) {}
        })

        // Start animation
        rootView.startAnimation(fadeInAnim)
        Log.d(TAG, "animateRankPageIn: Rank page fade-in animation (system) started")
    }

    /**
     * Shows game dialog based on dialog ID with state management.
     * Pauses game and prevents multiple dialogs from showing simultaneously.
     *
     * @param dialogId ID of the dialog to show (DIALOG_QUIT or DIALOG_CONTINUE_RESTART)
     */
    private fun showGameDialog(dialogId: Int) {
        // Skip if on non-game screen or dialog already showing
        if (isNonGamePage) {
            Log.d(TAG, "showGameDialog: Current page is non-game page, skip dialog")
            return
        }

        if (isDialogShowing) {
            Log.d(TAG, "showGameDialog: Dialog already showing, skip")
            return
        }

        // Pause game before showing dialog
        pauseGame()

        // Show appropriate dialog based on ID
        when (dialogId) {
            DIALOG_QUIT -> showQuitDialog()
            DIALOG_CONTINUE_RESTART -> showContinueRestartDialog()
            else -> Log.w(TAG, "showGameDialog: Unknown dialog id: $dialogId")
        }
    }

    /**
     * Shows quit confirmation dialog with optional debug mode features.
     * Allows user to quit game, continue playing, or jump levels (debug mode only).
     */
    private fun showQuitDialog() {
        Log.d(TAG, "showQuitDialog: Showing quit dialog")

        AlertDialog.Builder(this)
            .setTitle(R.string.str_app_name)
            .setMessage(R.string.str_msg_quit)
            // Positive button - quit game
            .setPositiveButton(R.string.str_btn_yes) { _, _ ->
                isDialogShowing = false
                launch(Dispatchers.IO) {
                    // Save game state before quitting
                    saveGameState()

                    withContext(Dispatchers.Main) {
                        finish()
                        Log.d(TAG, "showQuitDialog: User confirmed quit, activity finished")
                    }
                }
            }
            // Negative button - cancel quit, resume game
            .setNegativeButton(R.string.str_btn_no) { _, _ ->
                isDialogShowing = false
                resumeGame()
                Log.d(TAG, "showQuitDialog: User cancelled quit, game resumed")
            }
            // Add debug mode "JUMP" button if enabled
            .apply {
                if (isDebugMode) {
                    setNeutralButton("JUMP") { _, _ ->
                        isDialogShowing = false
                        val nextLevel = gameState.currentLevel + 1
                        launch(Dispatchers.IO) {
                            // Save new level and load it
                            GamePreferences.saveCurrentLevel(this@CTeeterActivity, nextLevel)

                            withContext(Dispatchers.Main) {
                                loadLevelAsync(nextLevel)
                                resumeGame()
                                Log.d(TAG, "showQuitDialog: Debug jump to level $nextLevel")
                            }
                        }
                    }
                }
            }
            .create()
            .apply {
                // Handle dialog cancellation (resume game)
                setOnCancelListener {
                    isDialogShowing = false
                    dialogHandler.sendEmptyMessage(MSG_RESUME_GAME)
                    Log.d(TAG, "showQuitDialog: Dialog cancelled, game resumed")
                }
                // Update state and show dialog
                isDialogShowing = true
                show()
            }
    }

    /**
     * Shows continue/restart dialog for resuming game from background.
     * Allows user to resume current game or restart from level 1.
     */
    private fun showContinueRestartDialog() {
        Log.d(TAG, "showContinueRestartDialog: Showing continue/restart dialog")

        AlertDialog.Builder(this)
            .setTitle(R.string.str_app_name)
            .setMessage(R.string.str_msg_continue)
            .apply {
                // Positive button - resume current game (zero velocity)
                setPositiveButton(R.string.str_btn_resume) { _, _ ->
                    isDialogShowing = false
                    resumeGameWithZeroVelocity()
                    Log.d(
                        TAG,
                        "showContinueRestartDialog: User chose to resume game with zero velocity"
                    )
                }

                // Negative button - restart game from level 1
                setNegativeButton(R.string.str_btn_restart) { _, _ ->
                    isDialogShowing = false
                    isResettingGame = true
                    resetGameProgress()
                    resumeGameWithZeroVelocity()
                    Log.d(TAG, "showContinueRestartDialog: User chose to restart game")
                }
            }
            .create()
            .apply {
                // Handle dialog cancellation (resume game)
                setOnCancelListener {
                    isDialogShowing = false
                    dialogHandler.sendEmptyMessage(MSG_RESUME_AFTER_RESTART)
                    Log.d(TAG, "showContinueRestartDialog: Dialog cancelled, game resumed")
                }
                // Update state and show dialog
                isDialogShowing = true
                show()
            }
    }

    /**
     * Animates out current view with fade-out effect and executes completion callback.
     * Manages animation state to prevent multiple simultaneous animations.
     *
     * @param onComplete Callback function to execute when animation finishes
     */
    private fun exitCurrentViewWithAnimation(onComplete: () -> Unit) {
        // Execute callback immediately if already animating
        if (isAnimating) {
            onComplete.invoke()
            return
        }

        // Update animation state
        isAnimating = true

        // Get root view for animation
        val rootView = window.decorView.findViewById<android.view.View>(android.R.id.content)

        // Configure fade-out animation
        val fadeOutAnim = AnimationUtils.loadAnimation(this, android.R.anim.fade_out)
        fadeOutAnim.duration = ANIMATION_DURATION

        // Set animation listener to execute callback and update state
        fadeOutAnim.setAnimationListener(object : android.view.animation.Animation.AnimationListener {
            override fun onAnimationStart(animation: android.view.animation.Animation) {}
            override fun onAnimationEnd(animation: android.view.animation.Animation) {
                isAnimating = false
                onComplete.invoke()
                Log.d(TAG, "exitCurrentViewWithAnimation: Exit animation (system) completed")
            }
            override fun onAnimationRepeat(animation: android.view.animation.Animation) {}
        })

        // Start fade-out animation
        rootView.startAnimation(fadeOutAnim)
        Log.d(TAG, "exitCurrentViewWithAnimation: Exit animation (system) started")
    }

    /**
     * Helper method to set content view and hide system bars in one call.
     * Ensures consistent window configuration across screen transitions.
     *
     * @param layoutResId Resource ID of the layout to set
     */
    private fun setContentViewAndHideBars(layoutResId: Int) {
        setContentView(layoutResId)
        hideSystemBars()
        Log.d(TAG, "setContentViewAndHideBars: Layout set to $layoutResId, system bars hidden")
    }

    /**
     * Hides system bars (status and navigation) for immersive game experience.
     * Configures bars to show temporarily when user swipes from edge.
     */
    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            // Hide all system bars
            hide(WindowInsetsCompat.Type.systemBars())
            // Configure bars to show temporarily on swipe
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        Log.v(TAG, "hideSystemBars: System bars hidden")
    }

    /**
     * Shows a global toast notification with message.
     * Cancels any existing toast to prevent multiple notifications stacking.
     *
     * @param message Text message to display
     * @param duration Toast duration (default: Toast.LENGTH_SHORT)
     */
    private fun showGlobalToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        // Cancel existing toast if present
        globalToast?.cancel()
        // Create and show new toast
        globalToast = Toast.makeText(this, message, duration).apply {
            mainHandler.post { show() }
        }
        Log.d(TAG, "showGlobalToast: Toast shown - '$message'")
    }

    /**
     * Pauses game play and updates pause state flag.
     * Stops game physics and sensor processing in GameView.
     */
    private fun pauseGame() {
        isGamePaused = true
        if (isGameViewInitialized) {
            gameView.pauseGame()
        }
        Log.d(TAG, "pauseGame: Game paused")
    }

    /**
     * Resumes normal game play from paused state.
     * Restarts sensor processing and game physics in GameView.
     */
    private fun resumeGame() {
        isGamePaused = false
        isFromBackground = false
        if (isGameViewInitialized) {
            gameView.startSensors()
            gameView.resumeGame()
        }
        // Ensure system bars remain hidden
        hideSystemBars()
        Log.d(TAG, "resumeGame: Game resumed normally")
    }

    /**
     * Resumes game play with zero initial velocity to prevent unexpected ball movement.
     * Used after game reset or background resume to ensure stable game state.
     */
    private fun resumeGameWithZeroVelocity() {
        isGamePaused = false
        isFromBackground = false
        if (isGameViewInitialized) {
            gameView.startSensors()
            gameView.resumeGameWithZeroVelocity()
        }
        // Ensure system bars remain hidden
        hideSystemBars()
        Log.d(TAG, "resumeGameWithZeroVelocity: Game resumed with zero velocity")
    }

    /**
     * Saves core game state to shared preferences asynchronously.
     * Stores current level, total time, and total attempts for progress persistence.
     */
    private fun saveGameState() {
        launch(Dispatchers.IO) {
            GamePreferences.saveGameCoreState(
                this@CTeeterActivity,
                gameState.currentLevel,
                gameState.totalTime,
                gameState.totalAttempts
            )
            Log.d(
                TAG,
                "saveGameState: Saved core state - level: ${gameState.currentLevel}, total time: ${gameState.totalTime}, total attempts: ${gameState.totalAttempts}"
            )
        }
    }

    /**
     * Extension function for GameView to set up debug mode touch controls.
     * Only enabled when debug mode is active (from intent extra).
     *
     * Touch zones:
     * - Left 20%: Toggle hole display
     * - Right 20%: Toggle end zone display
     * - Middle: Show quit dialog
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun GameView.setTouchListenerForDebugMode() {
        // Only setup debug controls if debug mode is enabled
        if (!isDebugMode) return

        setOnTouchListener { _, event ->
            // Only process touch down events
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                // Calculate touch position relative to screen dimensions
                val screenWidth = width.toFloat()
                val screenHeight = height.toFloat()
                val relativeX = event.x / screenWidth
                val relativeY = event.y / screenHeight

                // Process touch in valid Y range
                if (relativeY in 0.0f..1.0f) {
                    when {
                        // Left zone - toggle hole display
                        relativeX < 0.2f -> {
                            toggleHoleDisplay()
                            showGlobalToast(if (isHoleDisplayed) "Hole ON" else "Hole OFF")
                            Log.d(
                                TAG,
                                "setTouchListenerForDebugMode: Toggle hole display - ${if (isHoleDisplayed) "ON" else "OFF"}"
                            )
                            true
                        }
                        // Right zone - toggle end zone display
                        relativeX > 0.8f -> {
                            toggleEndDisplay()
                            showGlobalToast(if (isEndDisplayed) "End ON" else "End OFF")
                            Log.d(
                                TAG,
                                "setTouchListenerForDebugMode: Toggle end display - ${if (isEndDisplayed) "ON" else "OFF"}"
                            )
                            true
                        }
                        // Middle zone - show quit dialog
                        else -> {
                            pauseGame()
                            showGameDialog(DIALOG_QUIT)
                            Log.d(
                                TAG,
                                "setTouchListenerForDebugMode: Middle touch, show quit dialog"
                            )
                            true
                        }
                    }
                } else {
                    // Touch outside valid Y range - ignore
                    false
                }
            } else {
                // Not touch down event - ignore
                false
            }
        }
        Log.d(TAG, "setTouchListenerForDebugMode: Debug touch listener set")
    }

    /**
     * Extension function to format milliseconds into human-readable time string.
     * Formats time as HH:MM:SS using device default locale.
     *
     * @return Formatted time string (HH:MM:SS)
     */
    private fun Long.formatTime(): String {
        // Calculate hours, minutes, seconds from milliseconds
        val seconds = (this / 1000) % 60
        val minutes = (this / 60000) % 60
        val hours = (this / 3600000)
        // Format time string using resource string for localization
        val formattedTime = String.format(
            Locale.getDefault(),
            getString(R.string.str_time),
            hours,
            minutes,
            seconds
        )
        Log.v(TAG, "formatTime: $this ms -> $formattedTime")
        return formattedTime
    }

    /**
     * Custom Handler class for processing dialog-related messages.
     * Uses WeakReference to prevent memory leaks with the parent activity.
     *
     * @param looper Looper to associate with this handler (main looper)
     * @param activity WeakReference to parent CTeeterActivity
     */
    private class DialogHandler(
        looper: Looper,
        private val activity: WeakReference<CTeeterActivity>
    ) : Handler(looper) {
        /**
         * Processes incoming messages and dispatches to appropriate handlers.
         * Ignores messages if activity is null (destroyed) or on non-game screen.
         *
         * @param msg Message object containing what/arg1/arg2 data
         */
        override fun handleMessage(msg: Message) {
            super.handleMessage(msg)
            // Get activity reference or return if null (activity destroyed)
            val activity = activity.get() ?: return

            // Ignore messages if on non-game screen
            if (activity.isNonGamePage) {
                Log.d(TAG, "DialogHandler: Current page is non-game page, skip message ${msg.what}")
                return
            }

            // Process message based on ID
            when (msg.what) {
                // Resume game with zero velocity
                MSG_RESUME_GAME, MSG_RESUME_AFTER_RESTART -> {
                    activity.resumeGameWithZeroVelocity()
                    Log.d(
                        TAG,
                        "DialogHandler: Handle message ${msg.what}, game resumed with zero velocity"
                    )
                }

                // Reset game progress
                MSG_RESET_GAME -> {
                    activity.resetGameProgress()
                    Log.d(TAG, "DialogHandler: Handle message ${msg.what}, game progress reset")
                }

                // Unknown message ID
                else -> Log.w(TAG, "DialogHandler: Unknown message id: ${msg.what}")
            }
        }
    }
}