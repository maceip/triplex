package dev.triplex.ui.shell

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the shell's inset policy.
 *
 * The rule this protects: the nav bar pads itself by the bottom inset and the
 * scaffold already reserves the bar's height, so handing the same inset to the
 * content too would reserve the gesture strip twice. Whether that double reserve
 * is visible depends on the device, which is exactly why it is worth a test that
 * does not need one.
 */
class ShellInsetsTest {

    private val system = ShellInsets(left = 8.dp, top = 24.dp, right = 8.dp, bottom = 48.dp)

    @Test
    fun bottomBarPresentMeansContentGivesUpTheBottomInset() {
        val resolved = resolveShellContentInsets(system, hasBottomBar = true)

        assertEquals(0.dp, resolved.bottom)
    }

    @Test
    fun bottomBarPresentLeavesTheOtherThreeEdgesAlone() {
        val resolved = resolveShellContentInsets(system, hasBottomBar = true)

        assertEquals(system.left, resolved.left)
        assertEquals(system.top, resolved.top)
        assertEquals(system.right, resolved.right)
    }

    @Test
    fun noBottomBarMeansContentTakesEveryInset() {
        val resolved = resolveShellContentInsets(system, hasBottomBar = false)

        assertEquals(system, resolved)
    }

    @Test
    fun zeroInsetsStayZeroInBothModes() {
        val none = ShellInsets()

        assertEquals(none, resolveShellContentInsets(none, hasBottomBar = true))
        assertEquals(none, resolveShellContentInsets(none, hasBottomBar = false))
    }

    @Test
    fun defaultsAreZeroOnEveryEdge() {
        val none = ShellInsets()

        assertEquals(0.dp, none.left)
        assertEquals(0.dp, none.top)
        assertEquals(0.dp, none.right)
        assertEquals(0.dp, none.bottom)
    }
}
