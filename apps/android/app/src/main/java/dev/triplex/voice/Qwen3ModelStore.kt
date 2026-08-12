package dev.triplex.voice

import android.content.Context
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.AssetPackState
import com.google.android.play.core.assetpacks.AssetPackStateUpdateListener
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.triplex.BuildConfig
import dev.triplex.data.repository.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Resolves the on-device Qwen3 LiteRT bundle.
 *
 * Preference order:
 * 1. Already-complete `filesDir/models/qwen3-tts` (adb push or debug download)
 * 2. Debug → HTTP download into filesDir
 * 3. Release → Play Asset Delivery fast-follow pack `qwen3_tts`
 */
@Singleton
class Qwen3ModelStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloader: Qwen3HttpModelDownloader,
) {
    private val filesDirModels: File
        get() = File(context.filesDir, Qwen3ModelFiles.RELATIVE_DIR)

    @Volatile
    private var packModelsDir: File? = null

    /** True when a usable model directory is already on disk / pack path. */
    fun hasModels(): Boolean = resolveModelsDir()?.let { Qwen3ModelFiles.isComplete(it) } == true

    /**
     * Directory engines should load from. Prefer filesDir (writable, adb-friendly),
     * else the asset-pack assets path when the pack is present.
     */
    fun modelsDir(): File {
        resolveModelsDir()?.let { return it }
        return filesDirModels.also { it.mkdirs() }
    }

    /**
     * Ensures models are present, reporting [0f, 1f] progress while installing
     * or downloading. Idempotent when already ready.
     */
    suspend fun ensureReady(onProgress: (Float) -> Unit = {}): Result<File> {
        resolveModelsDir()?.let { existing ->
            onProgress(1f)
            return Result.Success(existing)
        }
        return if (BuildConfig.DEBUG) {
            Timber.i("Qwen3 models missing; starting debug HTTP download")
            when (val downloaded = downloader.download(filesDirModels, onProgress)) {
                is Result.Success -> Result.Success(filesDirModels)
                is Result.Error -> downloaded
            }
        } else {
            Timber.i("Qwen3 models missing; requesting asset pack %s", Qwen3ModelFiles.PACK_NAME)
            ensureAssetPack(onProgress)
        }
    }

    private fun resolveModelsDir(): File? {
        if (Qwen3ModelFiles.isComplete(filesDirModels)) return filesDirModels
        packModelsDir?.takeIf { Qwen3ModelFiles.isComplete(it) }?.let { return it }
        locatePackDir()?.let { pack ->
            packModelsDir = pack
            if (Qwen3ModelFiles.isComplete(pack)) return pack
        }
        return null
    }

    private fun locatePackDir(): File? {
        return runCatching {
            val manager = AssetPackManagerFactory.getInstance(context)
            val location = manager.getPackLocation(Qwen3ModelFiles.PACK_NAME) ?: return null
            val assetsPath = location.assetsPath() ?: return null
            val root = File(assetsPath)
            val nested = File(root, Qwen3ModelFiles.RELATIVE_DIR)
            when {
                Qwen3ModelFiles.isComplete(nested) -> nested
                Qwen3ModelFiles.isComplete(root) -> root
                else -> nested.takeIf { it.isDirectory } ?: root
            }
        }.onFailure {
            Timber.w(it, "Asset pack location lookup failed")
        }.getOrNull()
    }

    private suspend fun ensureAssetPack(onProgress: (Float) -> Unit): Result<File> =
        withContext(Dispatchers.IO) {
            locatePackDir()?.takeIf { Qwen3ModelFiles.isComplete(it) }?.let {
                packModelsDir = it
                onProgress(1f)
                return@withContext Result.Success(it)
            }

            val manager = AssetPackManagerFactory.getInstance(context)
            suspendCancellableCoroutine { cont ->
                val finished = AtomicBoolean(false)
                fun complete(result: Result<File>) {
                    if (finished.compareAndSet(false, true) && cont.isActive) {
                        cont.resume(result)
                    }
                }
                val listener = AssetPackStateUpdateListener { state ->
                    if (state.name() != Qwen3ModelFiles.PACK_NAME) return@AssetPackStateUpdateListener
                    handlePackState(state, onProgress, ::complete)
                }
                manager.registerListener(listener)
                cont.invokeOnCancellation {
                    runCatching { manager.unregisterListener(listener) }
                }
                manager.fetch(listOf(Qwen3ModelFiles.PACK_NAME))
                    .addOnFailureListener { error ->
                        runCatching { manager.unregisterListener(listener) }
                        complete(
                            Result.Error(
                                "Voice model install failed: ${error.message}",
                                error,
                            ),
                        )
                    }
                manager.getPackStates(listOf(Qwen3ModelFiles.PACK_NAME))
                    .addOnSuccessListener { states ->
                        val state = states.packStates()[Qwen3ModelFiles.PACK_NAME] ?: return@addOnSuccessListener
                        handlePackState(state, onProgress) { result ->
                            runCatching { manager.unregisterListener(listener) }
                            complete(result)
                        }
                    }
            }
        }

    private fun handlePackState(
        state: AssetPackState,
        onProgress: (Float) -> Unit,
        done: (Result<File>) -> Unit,
    ) {
        when (state.status()) {
            AssetPackStatus.PENDING,
            AssetPackStatus.WAITING_FOR_WIFI,
            AssetPackStatus.DOWNLOADING,
            AssetPackStatus.TRANSFERRING,
            -> {
                val total = state.totalBytesToDownload().coerceAtLeast(1L)
                val doneBytes = state.bytesDownloaded().coerceIn(0L, total)
                onProgress(doneBytes.toFloat() / total.toFloat())
            }
            AssetPackStatus.COMPLETED -> {
                onProgress(1f)
                val dir = locatePackDir()
                if (dir != null && Qwen3ModelFiles.isComplete(dir)) {
                    packModelsDir = dir
                    done(Result.Success(dir))
                } else {
                    done(Result.Error("Voice models installed but required files are missing."))
                }
            }
            AssetPackStatus.FAILED,
            AssetPackStatus.CANCELED,
            -> {
                done(
                    Result.Error(
                        "Voice model install failed (status=${state.status()} " +
                            "error=${state.errorCode()})",
                    ),
                )
            }
            else -> Unit
        }
    }
}
