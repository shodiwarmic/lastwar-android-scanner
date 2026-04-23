package tools.perry.lastwarscanner.ocr

import android.content.Context
import org.yaml.snakeyaml.Yaml

/**
 * Loads cross-consumer constants from the bundled
 * `assets/screen_definitions/constants.yaml`. See that file (and the
 * "Shared fallback constants" section of the screen-definitions README)
 * for the canonical values; this loader exists only so Kotlin code reads
 * them once at startup instead of duplicating numeric literals.
 *
 * Both consumers (this scanner and `lastwar-ocr-service`) MUST use the
 * same values. To change a constant, edit `constants.yaml` and bump the
 * submodule SHA in this app — no Kotlin changes required.
 */
object ConstantsLoader {

    private const val ASSET_PATH = "screen_definitions/constants.yaml"

    @Volatile private var _cached: Constants? = null

    fun load(context: Context): Constants {
        _cached?.let { return it }
        synchronized(this) {
            _cached?.let { return it }
            val parsed = parse(context)
            _cached = parsed
            return parsed
        }
    }

    /**
     * Returns the constants loaded by a previous [load] call, or null if
     * [load] has never been invoked. Use this from contexts that lack a
     * Context handle (e.g. companion-object helpers) — callers should
     * fall back to a hardcoded literal when this returns null so unit
     * tests that don't initialise the loader still work.
     */
    fun cached(): Constants? = _cached

    @Suppress("UNCHECKED_CAST")
    private fun parse(context: Context): Constants {
        val yaml = Yaml()
        val raw: Map<String, Any> = context.assets.open(ASSET_PATH).use { stream ->
            yaml.load(stream)
        }

        val colors  = raw["fallback_colors"] as Map<String, Any>
        val orange  = colors["orange"] as Map<String, Any>
        val white   = colors["white"]  as Map<String, Any>
        val orgRgb  = orange["rgb"] as Map<String, Any>
        val orgHsv  = orange["hsv"] as Map<String, Any>
        val whtRgb  = white["rgb"]  as Map<String, Any>

        val window  = raw["window_detection"] as Map<String, Any>
        val crash   = raw["crash_tokens"]     as Map<String, Any>

        return Constants(
            orangeRgb = OrangeRgb(
                rMin = (orgRgb["r_min"] as Number).toInt(),
                gMin = (orgRgb["g_min"] as Number).toInt(),
                gMax = (orgRgb["g_max"] as Number).toInt(),
                bMax = (orgRgb["b_max"] as Number).toInt(),
            ),
            orangeHsv = HsvThresholds(
                hMin = (orgHsv["h_min"] as Number).toFloat(),
                hMax = (orgHsv["h_max"] as Number).toFloat(),
                sMin = (orgHsv["s_min"] as Number).toFloat(),
                vMin = (orgHsv["v_min"] as Number).toFloat(),
            ),
            whiteRgb = WhiteRgb(
                rMin = (whtRgb["r_min"] as Number).toInt(),
                gMin = (whtRgb["g_min"] as Number).toInt(),
                bMin = (whtRgb["b_min"] as Number).toInt(),
            ),
            windowDetection = WindowDetection(
                nearBlackMaxChannel     = (window["near_black_max_channel"]    as Number).toInt(),
                borderCoverageThreshold = (window["border_coverage_threshold"] as Number).toFloat(),
                minWindowFraction       = (window["min_window_fraction"]       as Number).toFloat(),
                sampleCount             = (window["sample_count"]              as Number).toInt(),
                bboxPaddingFraction     = (window["bbox_padding_fraction"]     as Number).toFloat(),
            ),
            crashTokens = CrashTokens(
                scoreSuffixPattern = crash["score_suffix_pattern"] as String,
            ),
        )
    }
}

/** Top-level container for everything in `constants.yaml`. Frozen at first load. */
data class Constants(
    val orangeRgb: OrangeRgb,
    val orangeHsv: HsvThresholds,
    val whiteRgb:  WhiteRgb,
    val windowDetection: WindowDetection,
    val crashTokens: CrashTokens,
)

data class OrangeRgb(val rMin: Int, val gMin: Int, val gMax: Int, val bMax: Int) {
    fun matches(r: Int, g: Int, b: Int): Boolean =
        r > rMin && g in gMin..gMax && b < bMax
}

data class WhiteRgb(val rMin: Int, val gMin: Int, val bMin: Int) {
    fun matches(r: Int, g: Int, b: Int): Boolean =
        r > rMin && g > gMin && b > bMin
}

data class HsvThresholds(
    val hMin: Float = 0.0f,
    val hMax: Float = 1.0f,
    val sMin: Float = 0.0f,
    val vMin: Float = 0.0f,
)

data class WindowDetection(
    val nearBlackMaxChannel: Int,
    val borderCoverageThreshold: Float,
    val minWindowFraction: Float,
    val sampleCount: Int,
    val bboxPaddingFraction: Float,
)

data class CrashTokens(
    val scoreSuffixPattern: String,
)
