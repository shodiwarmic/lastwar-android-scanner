package tools.perry.lastwarscanner.image

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.core.graphics.get

/**
 * Utility class for image processing tasks, such as color detection and percentage calculation
 * in specific regions of a bitmap.
 */
object ImageUtils {

    /**
     * Checks if a given RGB color is considered "orange" according to predefined thresholds.
     * Used for detecting active tabs in certain screen layouts.
     */
    fun isOrange(r: Int, g: Int, b: Int): Boolean {
        return r > 180 && g in 80..200 && b < 120 && (r > g + 20)
    }

    /**
     * Checks if a given RGB color is considered "white" according to predefined thresholds.
     * Used for detecting active tabs in certain screen layouts.
     */
    fun isWhite(r: Int, g: Int, b: Int): Boolean {
        return r > 215 && g > 215 && b > 215
    }

    /**
     * Checks if a region contains a significant amount of a specific color.
     * Returns the percentage (0.0 to 1.0) of pixels that match.
     */
    fun getColorPercentage(
        bitmap: Bitmap, 
        rect: Rect, 
        colorCheck: (Int, Int, Int) -> Boolean
    ): Float {
        val left = rect.left.coerceAtLeast(0)
        val top = rect.top.coerceAtLeast(0)
        val right = rect.right.coerceAtMost(bitmap.width - 1)
        val bottom = rect.bottom.coerceAtMost(bitmap.height - 1)

        if (right <= left || bottom <= top) return 0f

        var matchCount = 0
        val step = 2
        var sampledCount = 0
        
        for (x in left until right step step) {
            for (y in top until bottom step step) {
                val pixel = bitmap[x, y]
                if (colorCheck(Color.red(pixel), Color.green(pixel), Color.blue(pixel))) {
                    matchCount++
                }
                sampledCount++
            }
        }

        return if (sampledCount == 0) 0f else matchCount.toFloat() / sampledCount
    }
}
