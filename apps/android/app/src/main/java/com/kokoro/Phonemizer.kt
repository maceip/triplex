package com.kokoro

interface Phonemizer {
    fun phonemize(text: String): IntArray
}
