package com.htc.android.teeter.utils

import android.content.Context
import com.htc.android.teeter.R
import com.htc.android.teeter.models.Level
import org.xmlpull.v1.XmlPullParser

/**
 * A utility object for parsing Teeter game level configuration from XML resources
 * This class loads level definition files (stored in res/xml/level*.xml) and converts them
 * into [Level] model objects for game runtime use.
 */
object LevelParser {

    /**
     * Lazy-loaded array of level XML resource IDs, sorted by level number
     * Discovers all resources in res/xml/ that start with "level" (e.g., level1.xml, level2.xml),
     * sorts them alphabetically (which corresponds to numerical level order), and stores their IDs.
     * Lazy initialization ensures resources are only loaded once when first accessed.
     */
    private val levelResources: IntArray by lazy {
        R.xml::class.java.fields
            .filter { it.name.startsWith("level") } // Filter XML resources named like "level1", "level2"
            .sortedBy { it.name } // Sort to maintain numerical level order
            .map { it.getInt(null) } // Convert Field objects to resource IDs
            .toIntArray()
    }

    /**
     * Loads and parses a specific game level from its XML resource file
     * Reads level configuration (start/end positions, walls, holes, background type) and constructs
     * a [Level] object. Returns null if the level number is invalid or parsing fails.
     *
     * @param context Application/Activity context to access resources
     * @param levelNumber The number of the level to load (starting from 1)
     * @return Parsed [Level] object if successful, null if level number is invalid or parsing error occurs
     */
    fun loadLevel(context: Context, levelNumber: Int): Level? {
        try {
            // Validate level number range (1 to total available levels)
            if (levelNumber < 1 || levelNumber > levelResources.size) return null

            // Get the XML resource ID for the specified level (adjust for 0-based array)
            val levelResId = levelResources[levelNumber - 1]
            val parser = context.resources.getXml(levelResId)

            // Initialize level configuration variables with default values
            var beginX = 0f
            var beginY = 0f
            var endX = 0f
            var endY = 0f
            val walls = mutableListOf<Level.Wall>()
            val holes = mutableListOf<Level.Hole>()
            var isFacetBackground = false

            // Parse XML document node by node
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    // Process only start tags (ignore text, end tags, etc.)
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            // Parse starting position of the ball
                            "begin" -> {
                                beginX = parser.getAttributeValue(null, "x").toFloat()
                                beginY = parser.getAttributeValue(null, "y").toFloat()
                            }
                            // Parse target end position (destination hole)
                            "end" -> {
                                endX = parser.getAttributeValue(null, "x").toFloat()
                                endY = parser.getAttributeValue(null, "y").toFloat()
                            }
                            // Parse rectangular wall obstacle
                            "wall" -> {
                                val left = parser.getAttributeValue(null, "left").toFloat()
                                val top = parser.getAttributeValue(null, "top").toFloat()
                                val right = parser.getAttributeValue(null, "right").toFloat()
                                val bottom = parser.getAttributeValue(null, "bottom").toFloat()
                                walls.add(Level.Wall(left, top, right, bottom))
                            }
                            // Parse hole position (trap or target)
                            "hole" -> {
                                val x = parser.getAttributeValue(null, "x").toFloat()
                                val y = parser.getAttributeValue(null, "y").toFloat()
                                holes.add(Level.Hole(x, y))
                            }
                            // Parse background type (faceted or regular)
                            "background" -> {
                                // Handle both integer and string attribute values for compatibility
                                val bgValue = try {
                                    parser.getAttributeIntValue(0, -1)
                                } catch (_: Exception) {
                                    val strValue = parser.getAttributeValue(0) ?: "-1"
                                    strValue.toIntOrNull() ?: -1
                                }
                                // 0 = faceted background, any other value = regular background
                                isFacetBackground = (bgValue == 0)
                            }
                        }
                    }
                }
                // Move to next XML event/node
                eventType = parser.next()
            }

            // Create and return Level object with parsed configuration
            return Level(
                levelNumber = levelNumber,
                levelResId = levelResId,
                beginX = beginX,
                beginY = beginY,
                endX = endX,
                endY = endY,
                walls = walls,
                holes = holes,
                isFacetBackground = isFacetBackground
            )
        } catch (e: Exception) {
            // Log parsing errors and return null to indicate failure
            e.printStackTrace()
            return null
        }
    }

    /**
     * Gets the total number of available game levels
     * Based on the number of "level*.xml" resources found in res/xml/ directory
     *
     * @return Total count of available levels (>= 0)
     */
    fun getTotalLevels(): Int = levelResources.size
}