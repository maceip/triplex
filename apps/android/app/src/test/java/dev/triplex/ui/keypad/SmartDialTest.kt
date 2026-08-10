package dev.triplex.ui.keypad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T9 is pure and stateless, so the whole behaviour is pinned by plain asserts. */
class SmartDialTest {

    @Test
    fun `letters map to the ITU-T keypad`() {
        assertEquals("2", SmartDial.toDigits("abc").take(1))
        assertEquals("222", SmartDial.toDigits("abc"))
        assertEquals("9999", SmartDial.toDigits("wxyz"))
        assertEquals("5263", SmartDial.toDigits("Jane"))
    }

    @Test
    fun `non-letters are ignored rather than mapped`() {
        assertEquals("5263", SmartDial.toDigits("Jane-2"))
        assertEquals("", SmartDial.toDigits("+1 (318) 555-0100"))
    }

    @Test
    fun `digits match the start of a first name`() {
        // 5-2-6 spells "Jan", the start of "Jane".
        assertTrue(SmartDial.matches("Jane Kowalski", "526"))
    }

    @Test
    fun `digits match across a word boundary`() {
        // "Jane" + "K": the words run together, which is how people type.
        assertTrue(SmartDial.matches("Jane Kowalski", "52635"))
    }

    @Test
    fun `digits match the start of a later word`() {
        // 5-6-9 spells "Kow", so a surname is reachable without the first name.
        assertTrue(SmartDial.matches("Jane Kowalski", "569"))
    }

    @Test
    fun `digits match initials`() {
        assertTrue(SmartDial.matches("Jane Kowalski", "55"))
        assertTrue(SmartDial.matches("Ada B Carter", "222"))
    }

    @Test
    fun `unrelated digits do not match`() {
        assertFalse(SmartDial.matches("Jane Kowalski", "999"))
        assertFalse(SmartDial.matches("Jane Kowalski", "5264"))
    }

    @Test
    fun `an empty query never matches`() {
        assertFalse(SmartDial.matches("Jane Kowalski", ""))
        assertFalse(SmartDial.matches("Jane Kowalski", "   "))
    }

    @Test
    fun `a nameless entry never matches`() {
        assertFalse(SmartDial.matches("", "526"))
        assertFalse(SmartDial.matches("+13185550100", "526"))
    }

    @Test
    fun `matching ignores case`() {
        assertTrue(SmartDial.matches("jane kowalski", "526"))
        assertTrue(SmartDial.matches("JANE KOWALSKI", "526"))
    }
}
