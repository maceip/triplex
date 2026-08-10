package dev.triplex.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeps product UI on the Triplex design-system boundary.
 *
 * The rule is not "no Material anywhere" — it is that shape, colour, motion and
 * interaction are decided once, in `ui/components`, and that everything else
 * consumes those wrappers plus the theme tokens instead of inventing a second
 * visual language per screen.
 *
 * The guard walks every package under `ui/` and subtracts a small, named set of
 * exemptions rather than listing the packages it covers. A new package is
 * therefore covered the day it is created, which is the opposite of the old
 * behaviour: that version hard-coded `agent` and `enrollment`, so `call`,
 * `keypad` and `shell` were silently outside the net.
 */
class DesignSystemGuardTest {

    /**
     * Packages the sweep deliberately skips, each for a different reason.
     *
     * - `components` is where the wrappers live. Material primitives are the raw
     *   material there; banning them would leave nothing to build with.
     * - `theme` is where the tokens are defined. Literal colours are the point.
     *
     * RikkaUI itself used to be vendored under `rikka` and exempted here. It is
     * now consumed as a real dependency from the ~/RikkaUi checkout, so there is
     * no third-party source under `ui/` left to excuse.
     */
    private val exemptPackages = setOf("components", "theme")

    /**
     * Material components with a Triplex wrapper. Importing them outside
     * `ui/components` means a screen re-decided shape, elevation or ripple.
     */
    private val bannedImports = listOf(
        "import androidx.compose.material3.Button",
        "import androidx.compose.material3.OutlinedButton",
        "import androidx.compose.material3.TextButton",
        "import androidx.compose.material3.Card",
        "import androidx.compose.material3.OutlinedCard",
        "import androidx.compose.material3.OutlinedTextField",
        "import androidx.compose.material3.TextField",
        "import androidx.compose.material3.FilterChip",
        "import androidx.compose.material3.AssistChip",
        "import androidx.compose.material3.FloatingActionButton",
    )

    /**
     * Hand-written colour and type values. These are what make two screens drift
     * apart, so they belong in `ui/theme` and nowhere else.
     */
    private val bannedLiterals = listOf(
        "Color(0x",
        "Color.White",
        "Color.Black",
        "TextStyle(",
        "FontFamily.",
    )

    /**
     * Packages exempt from [bannedLiterals] only.
     *
     * Empty, and that is the intended end state. The one entry this ever held was
     * `icons`, where hand-drawn `ImageVector`s needed a literal stroke colour to
     * build at all. Those vectors are gone: icons are now semantic `IconToken`s
     * resolved by the ambient pack, so nothing under `ui/` has a reason to write
     * a colour outside `theme` any more.
     */
    private val literalExemptPackages = emptySet<String>()

    @Test
    fun productUiDoesNotBypassTriplexComponentsOrTokens() {
        val violations = buildList {
            coveredPackages().forEach { directory ->
                val literalsApply = directory.name !in literalExemptPackages
                val snippets = if (literalsApply) bannedImports + bannedLiterals else bannedImports

                val files = directory.walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .toList()
                assertTrue("No Kotlin sources found under $directory", files.isNotEmpty())

                files.forEach { file ->
                    val source = file.readText()
                    snippets.forEach { snippet ->
                        if (source.contains(snippet)) {
                            add("${directory.name}/${file.name}: $snippet")
                        }
                    }
                }
            }
        }

        assertTrue(
            "Product UI must use Triplex components and theme tokens:\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    /**
     * An exemption that no longer names a real package is a hole in the guard: it
     * reads as deliberate while protecting nothing. Renaming or deleting one of
     * these packages must therefore fail here rather than quietly widen the sweep
     * or, worse, leave a stale name that hides a future package of the same name.
     */
    @Test
    fun everyExemptionNamesAnExistingPackage() {
        val present = uiRoot().listFiles().orEmpty().filter(File::isDirectory).map { it.name }.toSet()
        val stale = (exemptPackages + literalExemptPackages).filterNot { it in present }

        assertEquals("Exemptions naming packages that no longer exist: $stale", emptyList<String>(), stale)
    }

    /**
     * The sweep is worthless if it silently covers nothing, which is exactly what
     * a moved source root would cause.
     */
    @Test
    fun sweepCoversTheScreenPackages() {
        val covered = coveredPackages().map { it.name }.toSet()

        assertTrue(
            "Expected the screen packages to be covered, got $covered",
            covered.containsAll(setOf("agent", "call", "enrollment", "keypad", "shell")),
        )
    }

    private fun coveredPackages(): List<File> =
        uiRoot().listFiles().orEmpty()
            .filter { it.isDirectory && it.name !in exemptPackages }
            .sortedBy(File::getName)

    private fun uiRoot(): File {
        val workingDirectory = File(requireNotNull(System.getProperty("user.dir")))
        return listOf(
            File(workingDirectory, "src/main/java/dev/triplex/ui"),
            File(workingDirectory, "app/src/main/java/dev/triplex/ui"),
            File(workingDirectory, "apps/android/app/src/main/java/dev/triplex/ui"),
        ).firstOrNull(File::isDirectory)
            ?: error("Unable to locate Triplex UI sources from $workingDirectory")
    }
}
