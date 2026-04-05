package tools.perry.lastwarscanner.ocr

import android.graphics.Rect
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class OcrProcessor {

    // Initialize all specialized recognizers
    private val latinRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val koreanRecognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    private val chineseRecognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private val japaneseRecognizer = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    private val devanagariRecognizer = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())

    fun process(
        image: InputImage,
        onSuccess: (List<OcrLine>) -> Unit,
        onError: (Exception) -> Unit
    ) {
        // We run all recognizers in parallel to catch all languages
        val tasks = listOf(
            latinRecognizer.process(image),
            koreanRecognizer.process(image),
            chineseRecognizer.process(image),
            japaneseRecognizer.process(image),
            devanagariRecognizer.process(image)
        )

        Tasks.whenAllComplete(tasks)
            .addOnSuccessListener { completedTasks ->
                val allLines = mutableListOf<OcrLine>()
                
                for (task in completedTasks) {
                    if (task.isSuccessful) {
                        val result = task.result as Text
                        for (block in result.textBlocks) {
                            for (line in block.lines) {
                                val lineBox = line.boundingBox ?: continue
                                val elements = line.elements.mapNotNull { element ->
                                    val elementBox = element.boundingBox ?: return@mapNotNull null
                                    OcrElement(element.text, elementBox)
                                }
                                allLines.add(OcrLine(line.text, lineBox, elements))
                            }
                        }
                    }
                }
                
                // Merge lines that are at the same position to avoid duplicates
                val mergedLines = mergeOverlappingLines(allLines)
                onSuccess(mergedLines)
            }
            .addOnFailureListener {
                onError(it)
            }
    }

    /**
     * Merges lines that refer to the same text block but were detected by different language models.
     */
    private fun mergeOverlappingLines(lines: List<OcrLine>): List<OcrLine> {
        if (lines.isEmpty()) return emptyList()
        
        val merged = mutableListOf<OcrLine>()
        val sortedLines = lines.sortedByDescending { it.text.length } // Prefer longer strings (usually more accurate)

        for (line in sortedLines) {
            val isDuplicate = merged.any { existing ->
                // Check if bounding boxes overlap significantly
                val overlapX = Math.max(0, Math.min(line.right, existing.right) - Math.max(line.left, existing.left))
                val overlapY = Math.max(0, Math.min(line.bottom, existing.bottom) - Math.max(line.top, existing.top))
                val overlapArea = overlapX * overlapY
                val lineArea = (line.right - line.left) * (line.bottom - line.top)
                
                // If bounding boxes overlap by more than 70%, treat as duplicate
                overlapArea > lineArea * 0.7
            }
            
            if (!isDuplicate) {
                merged.add(line)
            }
        }
        return merged
    }
}

data class OcrElement(
    val text: String,
    val boundingBox: Rect
)

data class OcrLine(
    val text: String,
    val boundingBox: Rect,
    val elements: List<OcrElement> = emptyList()
) {
    val top: Int get() = boundingBox.top
    val left: Int get() = boundingBox.left
    val bottom: Int get() = boundingBox.bottom
    val right: Int get() = boundingBox.right
}