package dev.triplex.speech.tts

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** BRANDED call voice: Inflect Nano → single PCM chunk at 16 kHz. */
@Singleton
class InflectCallVoice @Inject constructor(
    @ApplicationContext context: Context,
) : CallVoice {
    private val engine = InflectLiteRtCallVoice(context)
    private val cancelled = AtomicBoolean(false)

    override fun synthesizeStream(text: String): Flow<ShortArray> = flow {
        cancelled.set(false)
        val pcm = engine.synthesize(text)
        if (cancelled.get() || pcm.isEmpty()) return@flow
        emit(pcm)
    }

    override fun cancel() {
        cancelled.set(true)
    }

    override fun close() {
        cancel()
        engine.close()
    }
}
