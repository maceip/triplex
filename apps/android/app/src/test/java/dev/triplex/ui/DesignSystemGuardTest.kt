package dev.triplex.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeps product screens on the Triplex design-system boundary.
 *
 * Material primitives remain available inside ui/components, where their shape,
 * color, motion, and interaction behavior are defined once. Screens consume
 * those wrappers and the MaterialTheme typography roles instead of creating
 * one-off visual language.
 */
class DesignSystemGuardTest {

    @Test
    fun productScreensDoNotBypassTriplexComponentsOrTokens() {
        val screensDirectory = locateScreensDirectory()
        val screenFiles = screensDirectory
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

        assertTrue("No product screen sources found under $screensDirectory", screenFiles.isNotEmpty())

        val forbiddenSnippets = listOf(
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
            "Color(0x",
            "Color.White",
            "Color.Black",
            "TextStyle(",
            "FontFamily."
        )

        val violations = buildList {
            screenFiles.forEach { file ->
                val source = file.readText()
                forbiddenSnippets.forEach { snippet ->
                    if (source.contains(snippet)) {
                        add("${file.name}: $snippet")
                    }
                }
            }
        }

        assertTrue(
            "Product screens must use Triplex components and theme tokens:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }

    private fun locateScreensDirectory(): File {
        val workingDirectory = File(requireNotNull(System.getProperty("user.dir")))
        return listOf(
            File(workingDirectory, "src/main/java/dev/triplex/ui/screens"),
            File(workingDirectory, "app/src/main/java/dev/triplex/ui/screens"),
            File(workingDirectory, "apps/android/app/src/main/java/dev/triplex/ui/screens")
        ).firstOrNull(File::isDirectory)
            ?: error("Unable to locate Triplex product screens from $workingDirectory")
    }
}
