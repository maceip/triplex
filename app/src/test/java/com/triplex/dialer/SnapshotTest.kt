package com.triplex.dialer

import android.widget.TextView
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

class SnapshotTest {

    @Rule
    @JvmField
    val paparazzi = Paparazzi()

    @Test
    fun rootComposable() {
        val textView = TextView(paparazzi.context)
        textView.text = "Triplex Dialer"
        textView.textSize = 24f
        paparazzi.snapshot(textView)
    }
}
