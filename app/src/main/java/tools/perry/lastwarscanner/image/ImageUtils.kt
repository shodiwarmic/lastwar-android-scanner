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
     * Canonical RGB orange fallback — matches the "Shared fallback constants"
     * table in lastwar-screen-definitions/README.md (HSV equivalent
     * h_min=0.014, h_max=0.153, s_min=0.40, v_min=0.55). Used for active-tab
     * detection only when the layout's `tabs.active_indicator.color.hsv_override`
     * is absent. Both consumers (this scanner and lastwar-ocr-service) MUST use
     * the same constants — fix in screen-definitions if a UI palette change
     * breaks them, then update both consumers in the same PR cycle.
     */
    fun isOrange(r: Int, g: Int, b: Int): Boolean {
        return r > 200 && g in 80..170 && b < 90
    }

    /**
     * Canonical RGB white fallback — matches the "Shared fallback constants"
     * table in lastwar-screen-definitions/README.md.
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
