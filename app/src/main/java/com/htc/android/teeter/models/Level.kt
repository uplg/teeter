package com.htc.android.teeter.models

/**
 * Data model representing a single level in the Teeter game (tilt maze game)
 * This class contains all the configuration and elements needed to render and run a game level
 *
 * @property levelNumber Unique identifier for the level (e.g., 1, 2, 3)
 * @property levelResId Resource ID for the level's background or layout resource
 * @property beginX Initial X coordinate of the game ball at level start
 * @property beginY Initial Y coordinate of the game ball at level start
 * @property endX Target X coordinate (destination hole) for the ball to reach
 * @property endY Target Y coordinate (destination hole) for the ball to reach
 * @property walls List of wall boundaries that restrict ball movement
 * @property holes List of hole positions (including both target and trap holes)
 * @property isFacetBackground Flag indicating if the level uses faceted/textured background
 */
data class Level(
    val levelNumber: Int,
    val levelResId: Int,
    val beginX: Float,
    val beginY: Float,
    val endX: Float,
    val endY: Float,
    val walls: List<Wall>,
    val holes: List<Hole>,
    val isFacetBackground: Boolean
) {
    /**
     * Data model representing a rectangular wall obstacle in the game level
     * Defines the bounding rectangle for collision detection with the ball
     *
     * @property left Left (minimum X) boundary of the wall
     * @property top Top (minimum Y) boundary of the wall
     * @property right Right (maximum X) boundary of the wall
     * @property bottom Bottom (maximum Y) boundary of the wall
     */
    data class Wall(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    )

    /**
     * Data model representing a hole (target or trap) in the game level
     * The ball will end the level when it reaches a hole (success for target, failure for trap)
     *
     * @property x Center X coordinate of the hole
     * @property y Center Y coordinate of the hole
     */
    data class Hole(
        val x: Float,
        val y: Float
    )
}