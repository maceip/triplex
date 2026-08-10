package dev.triplex.ui.components

import zed.rainxch.rikkaui.components.ui.button.ButtonVariant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the Triplex → Rikka button mapping.
 *
 * `when` over an enum is exhaustive, so the compiler catches a missing branch —
 * but not a wrong one. A DANGER button rendered as an ordinary filled button
 * still compiles and still ships.
 */
class TriplexButtonStyleTest {

    @Test
    fun destructiveIntentSurvivesTheMapping() {
        assertEquals(ButtonVariant.Destructive, TriplexButtonStyle.DANGER.toButtonVariant())
    }

    @Test
    fun eachStyleMapsToItsRikkaVariant() {
        assertEquals(ButtonVariant.Default, TriplexButtonStyle.PRIMARY.toButtonVariant())
        assertEquals(ButtonVariant.Secondary, TriplexButtonStyle.SECONDARY.toButtonVariant())
        assertEquals(ButtonVariant.Outline, TriplexButtonStyle.OUTLINE.toButtonVariant())
        assertEquals(ButtonVariant.Ghost, TriplexButtonStyle.GHOST.toButtonVariant())
    }

    @Test
    fun everyStyleMapsToADistinctVariant() {
        val variants = TriplexButtonStyle.entries.map { it.toButtonVariant() }

        assertEquals(
            "Two Triplex button styles collapsed onto one Rikka variant: $variants",
            TriplexButtonStyle.entries.size,
            variants.toSet().size,
        )
    }
}
