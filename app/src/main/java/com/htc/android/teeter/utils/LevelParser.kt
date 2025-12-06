package com.htc.android.teeter.utils

import android.content.Context
import com.htc.android.teeter.R
import com.htc.android.teeter.models.Hole
import com.htc.android.teeter.models.Level
import com.htc.android.teeter.models.Wall
import org.xmlpull.v1.XmlPullParser

object LevelParser {
    
    // Generate level resources using reflection only once at initialization
    private val levelResources: IntArray by lazy {
        R.xml::class.java.fields
            .filter { it.name.startsWith("level") }
            .sortedBy { it.name }
            .map { it.getInt(null) }
            .toIntArray()
    }
    
    fun loadLevel(context: Context, levelNumber: Int): Level? {
        try {
            if (levelNumber < 1 || levelNumber > levelResources.size) return null
            
            val resourceId = levelResources[levelNumber - 1]
            val parser = context.resources.getXml(resourceId)
            
            var beginX = 0f
            var beginY = 0f
            var endX = 0f
            var endY = 0f
            val walls = mutableListOf<Wall>()
            val holes = mutableListOf<Hole>()
            
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "begin" -> {
                                beginX = parser.getAttributeValue(null, "x").toFloat()
                                beginY = parser.getAttributeValue(null, "y").toFloat()
                            }
                            "end" -> {
                                endX = parser.getAttributeValue(null, "x").toFloat()
                                endY = parser.getAttributeValue(null, "y").toFloat()
                            }
                            "wall" -> {
                                val left = parser.getAttributeValue(null, "left").toFloat()
                                val top = parser.getAttributeValue(null, "top").toFloat()
                                val right = parser.getAttributeValue(null, "right").toFloat()
                                val bottom = parser.getAttributeValue(null, "bottom").toFloat()
                                walls.add(Wall(left, top, right, bottom))
                            }
                            "hole" -> {
                                val x = parser.getAttributeValue(null, "x").toFloat()
                                val y = parser.getAttributeValue(null, "y").toFloat()
                                holes.add(Hole(x, y))
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
            
            return Level(levelNumber, beginX, beginY, endX, endY, walls, holes)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
    
    fun getTotalLevels(): Int = levelResources.size
}
