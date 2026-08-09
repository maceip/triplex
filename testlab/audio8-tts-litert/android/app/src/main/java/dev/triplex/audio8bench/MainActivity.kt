package dev.triplex.audio8bench

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        output = TextView(this).apply {
            textSize = 14f
            setPadding(32, 24, 32, 32)
        }
        val button = Button(this).apply {
            text = "Run Audio8 clone benchmark"
            setOnClickListener {
                isEnabled = false
                output.text = ""
                worker.execute {
                    runCatching {
                        Audio8Engine(this@MainActivity, ::report).run()
                    }.onFailure { error ->
                        report("FAIL ${error::class.java.simpleName}: ${error.message}")
                        Log.e(TAG, "benchmark failed", error)
                    }
                    main.post { isEnabled = true }
                }
            }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(button, LinearLayout.LayoutParams(-1, -2))
            addView(
                ScrollView(this@MainActivity).apply { addView(output) },
                LinearLayout.LayoutParams(-1, 0, 1f),
            )
        }
        setContentView(column)
        report("Models: ${getExternalFilesDir(null)}/models")
    }

    private fun report(message: String) {
        Log.i(TAG, message)
        main.post { output.append(message + "\n") }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private companion object { const val TAG = "AUDIO8_BENCH" }
}
