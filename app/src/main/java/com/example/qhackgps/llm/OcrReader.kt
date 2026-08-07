package com.example.qhackgps.llm

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/**
 * On-demand text reading (signs, doors, menus, labels) via ML Kit's
 * bundled Latin recognizer — the model ships inside the APK and runs fully
 * on-device. Invoked only when the user's question sounds like a read
 * request; the recognized text is appended to that turn's scene digest so
 * the companion can read or summarize it. The frame comes from the camera
 * scan, so reading requires the SCAN toggle to be on.
 */
class OcrReader {

    companion object {
        private const val TAG = "OcrReader"
        private const val MAX_CHARS = 400
    }

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /** Returns recognized text (newlines flattened), or null when none/failed. */
    suspend fun read(bitmap: Bitmap): String? = try {
        val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        result.text
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .takeIf { it.isNotEmpty() }
            ?.take(MAX_CHARS)
    } catch (e: Exception) {
        Log.w(TAG, "ocr failed", e)
        null
    }

    fun close() {
        runCatching { recognizer.close() }
    }
}
