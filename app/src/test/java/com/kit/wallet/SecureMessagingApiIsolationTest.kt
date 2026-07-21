package com.kit.wallet

import com.kit.wallet.data.remote.KitWalletApi
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HEAD
import retrofit2.http.HTTP
import retrofit2.http.OPTIONS
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT

class SecureMessagingApiIsolationTest {
    @Test
    fun `public wallet api exposes no secure messaging route`() {
        val messagingRoutes = KitWalletApi::class.java.declaredMethods
            .flatMap { method -> method.annotations.mapNotNull(::route) }
            .filter(::isMessagingRoute)

        assertTrue(
            "KitWalletApi must not expose raw secure-messaging routes: $messagingRoutes",
            messagingRoutes.isEmpty(),
        )
    }

    @Test
    fun `only secure transport and network DI reference the raw messaging api`() {
        val root = repositoryRoot()
        val sourceRoot = File(root, "app/src/main/java")
        val declaration = "com/kit/wallet/data/remote/SecureMessagingWireApi.kt"
        val references = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.relativeTo(sourceRoot).invariantSeparatorsPath != declaration }
            .filter { it.readText().contains(Regex("\\bSecureMessagingWireApi\\b")) }
            .map { it.relativeTo(sourceRoot).invariantSeparatorsPath }
            .toSet()

        assertEquals(
            setOf(
                "com/kit/wallet/data/messaging/RemoteSecureMessagingTransport.kt",
                "com/kit/wallet/di/NetworkModule.kt",
            ),
            references,
        )
    }

    private fun repositoryRoot(): File {
        val workingDirectory = File(System.getProperty("user.dir")).canonicalFile
        return generateSequence(workingDirectory) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/java").isDirectory }
            ?: error("Could not locate the Android repository root from $workingDirectory")
    }

    private fun route(annotation: Annotation): String? = when (annotation) {
        is DELETE -> annotation.value
        is GET -> annotation.value
        is HEAD -> annotation.value
        is HTTP -> annotation.path
        is OPTIONS -> annotation.value
        is PATCH -> annotation.value
        is POST -> annotation.value
        is PUT -> annotation.value
        else -> null
    }

    private fun isMessagingRoute(route: String): Boolean =
        route.split('/').any { it == "messaging" }
}
