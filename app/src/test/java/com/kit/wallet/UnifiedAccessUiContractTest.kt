package com.kit.wallet

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedAccessUiContractTest {
    @Test
    fun `self designation appears once in the profile header and nowhere else`() {
        val root = repositoryRoot()
        val settings = source(root, "feature/settings/SettingsScreen.kt")
        val nonProfileSurfaces = listOf(
            "feature/home/HomeScreen.kt",
            "feature/settings/ProfileEditorScreen.kt",
            "feature/settings/ProfileAvatarPicker.kt",
            "navigation/KitApp.kt",
        ).associateWith { source(root, it) }

        assertEquals(
            1,
            Regex("""AccountVerificationBadge\(\s*profile\.accountVerification""")
                .findAll(settings)
                .count(),
        )
        nonProfileSurfaces.forEach { (path, text) ->
            assertFalse(path, text.contains("accountVerification = profile.accountVerification"))
            assertFalse(
                path,
                Regex("""AccountVerificationBadge\(\s*profile\.accountVerification""")
                    .containsMatchIn(text),
            )
        }
    }

    @Test
    fun `home keeps financial entry points visible while routing every tap through policy`() {
        val root = repositoryRoot()
        val home = source(root, "feature/home/HomeScreen.kt")
        val navigation = source(root, "navigation/KitApp.kt")
        val accessScreen = source(root, "feature/wallet/FinancialAccessScreen.kt")
        val guardedHomeRoutes = listOf(
            "onSend = { openFinancialRoute(Dest.SEND) }",
            "onReceive = { openFinancialRoute(Dest.RECEIVE) }",
            "onScan = { openFinancialRoute(Dest.SCAN) }",
            "onBills = { openFinancialRoute(Dest.BILLS) }",
            "onAirtime = { openFinancialRoute(Dest.AIRTIME) }",
            "onBank = { openFinancialRoute(Dest.BANK) }",
            "onMobileMoney = { openFinancialRoute(Dest.MOBILE_MONEY) }",
            "onRequest = { openFinancialRoute(Dest.REQUEST) }",
            "onAllTransactions = { openFinancialRoute(Dest.TRANSACTIONS) }",
            "onTransaction = { openFinancialRoute(Dest.txDetail(it)) }",
            "onFavorite = { openFinancialRoute(Dest.send(it)) }",
        )

        guardedHomeRoutes.forEach { wiring -> assertTrue(wiring, navigation.contains(wiring)) }
        assertTrue(navigation.contains("financialRouteAccessAllowed(route, moneyAccessAllowed"))
        assertTrue(navigation.contains("onFinancialIdentityRequired()"))
        assertTrue(navigation.contains("composable(Dest.FINANCIAL_ACCESS)"))
        assertFalse(navigation.contains("FinancialIdentityRequiredDialog"))
        assertTrue(accessScreen.contains("Scaffold("))
        assertFalse(accessScreen.contains("AlertDialog"))

        // Actions and the history affordance stay mounted; only sensitive values and rows hide.
        assertTrue(home.contains("BalanceCard("))
        assertTrue(home.contains("QuickAction("))
        assertTrue(home.contains("\"Recent activity\""))
        assertTrue(home.contains("onAction = onAllTransactions"))
        assertTrue(home.contains("balanceAvailable = walletEnabled && moneyAccessAllowed"))
        assertTrue(
            Regex("""if \(moneyAccessAllowed\) \{\s*items\(recent\.size\)""")
                .containsMatchIn(home),
        )
    }

    private fun source(root: File, path: String): String =
        File(root, "app/src/main/java/com/kit/wallet/$path").readText()

    private fun repositoryRoot(): File {
        val workingDirectory = File(checkNotNull(System.getProperty("user.dir"))).canonicalFile
        return generateSequence(workingDirectory) { it.parentFile }
            .firstOrNull { File(it, "app/build.gradle.kts").isFile }
            ?: error("Could not locate the Android repository root from $workingDirectory")
    }
}
