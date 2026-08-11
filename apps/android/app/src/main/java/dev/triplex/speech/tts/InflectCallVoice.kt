package dev.triplex.speech.tts

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.triplex.dialogue.SpokenReply
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BRANDED call voice: Inflect Nano → 16 kHz PCM.
 *
 * Multi-sentence replies are synthesized clause-by-clause so the first
 * audible audio can start while later clauses are still rendering. Nano is
 * still batch today; this is the TTS-side half of Nano→Inflect overlap.
 */
@Singleton
class InflectCallVoice @Inject constructor(
    @ApplicationContext context: Context,
) : CallVoice {
    private val engine = InflectLiteRtCallVoice(context)
    private val cancelled = AtomicBoolean(false)

    override fun synthesizeStream(text: String): Flow<ShortArray> = flow {
        cancelled.set(false)
        for (clause in SpokenReply.clauses(text)) {
            if (cancelled.get()) return@flow
            val pcm = engine.synthesize(clause)
            if (cancelled.get() || pcm.isEmpty()) continue
            emit(pcm)
        }
    }

    override fun cancel() {
        cancelled.set(true)
    }

    override fun close() {
        cancel()
        engine.close()
    }
}
