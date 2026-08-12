package dev.triplex.ui.components

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads a contacts-provider photo URI into an [ImageBitmap], or null when the
 * URI is missing / unreadable. Used by favourite chips so a real headshot wins
 * over the generated bust.
 */
@Composable
fun rememberContactPhoto(photoUri: String?): ImageBitmap? {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, photoUri, context) {
        if (photoUri.isNullOrBlank()) {
            value = null
            return@produceState
        }
        value =
            withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(Uri.parse(photoUri))?.use { stream ->
                        BitmapFactory.decodeStream(stream)?.asImageBitmap()
                    }
                }.getOrNull()
            }
    }
    return bitmap
}
