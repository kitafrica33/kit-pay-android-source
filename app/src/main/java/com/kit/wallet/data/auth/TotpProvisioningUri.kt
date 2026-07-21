package com.kit.wallet.data.auth

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal fun isTrustedTotpProvisioningUri(value: String, expectedSecret: String): Boolean =
    runCatching {
        if (!expectedSecret.matches(Regex("^[A-Z2-7]{32}$"))) {
            return@runCatching false
        }
        val uri = URI(value)
        if (!uri.scheme.equals("otpauth", ignoreCase = true) ||
            !uri.host.equals("totp", ignoreCase = true) ||
            uri.userInfo != null ||
            uri.port != -1 ||
            uri.fragment != null ||
            uri.path.isNullOrBlank()
        ) {
            return@runCatching false
        }

        val parameters = uri.rawQuery
            ?.split('&')
            ?.map { pair ->
                val pieces = pair.split('=', limit = 2)
                decode(pieces[0]) to decode(pieces.getOrElse(1) { "" })
            }
            ?.groupBy({ it.first }, { it.second })
            ?: return@runCatching false

        val expectedKeys = setOf("secret", "issuer", "algorithm", "digits", "period")
        parameters.keys == expectedKeys &&
            parameters.values.all { it.size == 1 } &&
            parameters.getValue("secret").single() == expectedSecret &&
            parameters.getValue("issuer").single().isNotBlank() &&
            parameters.getValue("algorithm").single() == "SHA1" &&
            parameters.getValue("digits").single() == "6" &&
            parameters.getValue("period").single() == "30"
    }.getOrDefault(false)

private fun decode(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8.name())
