package com.triplex.dialer

import android.content.Context
import androidx.core.content.FileProvider as CoreFileProvider

/**
 * File provider for sharing call-related files (logs, audio dumps).
 * Mirrors AndroidX reference app's FileProvider.
 */
class FileProvider : CoreFileProvider() {

    companion object {
        fun getUriForFile(context: Context, file: java.io.File) =
            getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
}
