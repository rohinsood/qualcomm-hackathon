package dev.quad.shepherd.llm

import android.graphics.Bitmap
import android.util.Base64
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.TextBlockParam
import dev.quad.shepherd.BuildConfig
import dev.quad.shepherd.vision.Detection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.stream.Collectors

/**
 * Cloud scene narration via the Claude API (official Anthropic Java SDK).
 *
 * NOTE ON KEYS: reading the API key from BuildConfig is fine for a personal
 * prototype, but a shipped app must proxy Claude calls through a backend —
 * anything baked into an APK can be extracted.
 */
class ClaudeSceneDescriber : SceneDescriber {

    private val client: AnthropicClient by lazy {
        AnthropicOkHttpClient.builder()
            .apiKey(BuildConfig.CLAUDE_API_KEY)
            .build()
    }

    private val systemPrompt =
        "You are the eyes of a visually impaired pedestrian using an assistive " +
            "navigation app. Describe what the camera sees in 2-4 short sentences, " +
            "prioritizing safety-relevant information: obstacles in the walking path, " +
            "moving vehicles or people, surface changes (curbs, stairs, ramps), and " +
            "orientation landmarks. Speak directly to the user ('There is a bench two " +
            "meters ahead on your right'). No preamble, no markdown."

    override suspend fun describe(
        frame: Bitmap,
        detections: List<Detection>,
    ): String = withContext(Dispatchers.IO) {
        val jpeg = ByteArrayOutputStream().use { out ->
            // Downscale keeps upload fast on mobile networks
            val scaled = scaleLongEdge(frame, 1024)
            scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
            out.toByteArray()
        }
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)

        val detectionSummary = if (detections.isEmpty()) "none" else
            detections.take(10).joinToString("; ") { d ->
                val dist = d.distanceMeters?.let { String.format("%.1f m", it) } ?: "unknown distance"
                "${d.label} ($dist)"
            }

        val params = MessageCreateParams.builder()
            .model(BuildConfig.CLAUDE_MODEL)
            .maxTokens(1024L)
            // Low effort keeps latency down for a spoken, time-sensitive answer
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .system(systemPrompt)
            .addUserMessageOfBlockParams(
                listOf(
                    ContentBlockParam.ofImage(
                        ImageBlockParam.builder()
                            .source(
                                Base64ImageSource.builder()
                                    .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                                    .data(b64)
                                    .build()
                            )
                            .build()
                    ),
                    ContentBlockParam.ofText(
                        TextBlockParam.builder()
                            .text(
                                "On-device detector currently sees: $detectionSummary. " +
                                    "Describe the scene for me."
                            )
                            .build()
                    ),
                )
            )
            .build()

        val response = client.messages().create(params)
        val text = response.content().stream()
            .flatMap { block -> block.text().stream() }
            .map { it.text() }
            .collect(Collectors.joining(" "))
            .trim()

        // Empty content with no text blocks covers the refusal stop reason too
        text.ifBlank { "I couldn't describe the scene right now." }
    }

    private fun scaleLongEdge(src: Bitmap, longEdge: Int): Bitmap {
        val maxDim = maxOf(src.width, src.height)
        if (maxDim <= longEdge) return src
        val scale = longEdge.toFloat() / maxDim
        return Bitmap.createScaledBitmap(
            src,
            (src.width * scale).toInt(),
            (src.height * scale).toInt(),
            true,
        )
    }
}
