package dev.triplex.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint

/**
 * ADB-only development control surface. It is absent from release APKs.
 *
 * Long work runs in [DevelopmentControlService] so enroll/preview cannot ANR
 * the broadcast watchdog on devices slower than a high-RAM Pixel.
 */
@AndroidEntryPoint
class DevelopmentControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            DevelopmentControlService.ACTION_PROVISION,
            DevelopmentControlService.ACTION_OUTBOUND_SMOKE,
            DevelopmentControlService.ACTION_VOICE_CLONE_SMOKE,
            DevelopmentControlService.ACTION_FETCH_QWEN3_MODELS,
            DevelopmentControlService.ACTION_SET_CLONED_POLICY,
            DevelopmentControlService.ACTION_HANGUP,
            -> {
                runCatching { DevelopmentControlService.enqueue(context, intent) }
                    .onFailure { error ->
                        Log.e(
                            DevelopmentControlService.TAG,
                            "TRIPLEX_DEV_CONTROL FAIL ${error::class.java.simpleName}: ${error.message}",
                        )
                    }
            }
            else -> Log.e(
                DevelopmentControlService.TAG,
                "TRIPLEX_DEV_CONTROL FAIL unknown_action",
            )
        }
    }
}
