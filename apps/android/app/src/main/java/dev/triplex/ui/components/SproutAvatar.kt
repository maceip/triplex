package dev.triplex.ui.components

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * DiceBear **sprouts** avatar generated on-device via Rust
 * (`apps/android/sprouts` → `libtriplex_sprouts.so`).
 *
 * No npm, no gomobile, no network. Same seed always yields the same plant.
 *
 * @param seed Stable identity, e.g. `"${id}:${number}:${name}"`.
 * @param modifier Modifier for the avatar.
 * @param size Diameter.
 * @param label Accessibility description.
 * @param animation DiceBear `animationVariant` (`medium` by default).
 */
@Composable
fun SproutAvatar(
    seed: String,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    label: String = "",
    animation: String = "medium",
) {
    val svg by produceState<String?>(initialValue = null, seed, animation) {
        value =
            withContext(Dispatchers.Default) {
                SproutsNative.svg(seed = seed, size = 128, animation = animation)
            }
    }

    val semantics =
        if (label.isNotBlank()) {
            Modifier.semantics { contentDescription = label }
        } else {
            Modifier
        }

    Box(modifier = modifier.then(semantics).size(size).clip(CircleShape)) {
        val document = svg
        if (document != null) {
            SproutSvgWebView(svg = document, modifier = Modifier.matchParentSize())
        }
    }
}

/**
 * Favorites / directory seed. Keep this format forever — changing it remaps
 * every contact to a different sprout.
 */
fun sproutSeed(id: Long, number: String, displayName: String): String =
    "$id:$number:$displayName"

/** JNI bridge to `libtriplex_sprouts.so` (DiceBear Rust SDK). */
internal object SproutsNative {
    init {
        System.loadLibrary("triplex_sprouts")
    }

    fun svg(seed: String, size: Int, animation: String): String? =
        nativeSvg(seed, size, animation)

    @JvmStatic
    private external fun nativeSvg(seed: String, size: Int, animation: String): String?
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SproutSvgWebView(
    svg: String,
    modifier: Modifier = Modifier,
) {
    // DiceBear CSS @keyframes live inside the SVG; WebView <img> plays them.
    val html =
        remember(svg) {
            val encoded =
                android.util.Base64.encodeToString(
                    svg.toByteArray(StandardCharsets.UTF_8),
                    android.util.Base64.NO_WRAP,
                )
            """
            <!DOCTYPE html><html><head>
            <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
            <style>
              html,body{margin:0;padding:0;background:transparent;overflow:hidden;height:100%;width:100%;}
              img{width:100%;height:100%;object-fit:contain;display:block;}
            </style></head><body>
            <img src="data:image/svg+xml;base64,$encoded" alt=""/>
            </body></html>
            """.trimIndent()
        }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(AndroidColor.TRANSPARENT)
                setLayerType(WebView.LAYER_TYPE_HARDWARE, null)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                settings.apply {
                    javaScriptEnabled = false
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    displayZoomControls = false
                    builtInZoomControls = false
                }
                webViewClient = WebViewClient()
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
            }
        },
        update = { webView ->
            webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        },
    )
}
