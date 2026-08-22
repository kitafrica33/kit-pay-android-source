package com.kit.wallet

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomerFacingProviderCopyTest {
    @Test
    fun `customer-facing Android resources do not expose backend provider names`() {
        val presentationRoots = listOf(Path.of("src/main"))
        val forbiddenNames = listOf("RukaPay")
        val violations = presentationRoots.flatMap { root ->
            if (!Files.exists(root)) return@flatMap emptyList()
            Files.walk(root).use { paths ->
                paths.filter(Files::isRegularFile)
                    .filter { it.extension in setOf("kt", "xml") }
                    .flatMap { path ->
                        val text = path.readText()
                        forbiddenNames.stream()
                            .filter(text::contains)
                            .map { name -> "$path contains $name" }
                    }
                    .toList()
            }
        }

        assertTrue(violations.joinToString(separator = "\n"), violations.isEmpty())
    }
}
