package com.kit.wallet.ui.theme

import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------
// Kit brand palette — extracted from the official Kit logo.
// Keep in sync with res/values/colors.xml.
// ---------------------------------------------------------------------------

/** Primary brand color: deep Kit navy. */
val KitNavy = Color(0xFF122D46)

/** Accent brand color: Kit green. */
val KitGreen = Color(0xFF34B98B)

// Navy ramp
val KitNavy900 = Color(0xFF081524)
val KitNavy800 = Color(0xFF0B1D2E)
val KitNavy700 = Color(0xFF122D46)
val KitNavy600 = Color(0xFF1D4166)
val KitNavy500 = Color(0xFF2B5788)
val KitNavy400 = Color(0xFF4B77A8)
val KitNavy300 = Color(0xFF7A9EC6)
val KitNavy200 = Color(0xFFAAC4DF)
val KitNavy100 = Color(0xFFD6E4F2)
val KitNavy050 = Color(0xFFEDF3FA)

// Green ramp
val KitGreen900 = Color(0xFF04301F)
val KitGreen800 = Color(0xFF0A4A31)
val KitGreen700 = Color(0xFF127A52)
val KitGreen600 = Color(0xFF1F9A6C)
val KitGreen500 = Color(0xFF34B98B)
val KitGreen400 = Color(0xFF57CBA2)
val KitGreen300 = Color(0xFF86DCBD)
val KitGreen200 = Color(0xFFB4EAD6)
val KitGreen100 = Color(0xFFD6F4E8)
val KitGreen050 = Color(0xFFEBFAF3)

// Neutrals (navy-tinted)
val KitInk = Color(0xFF0E1B27)
val KitSlate = Color(0xFF51606E)
val KitMist = Color(0xFF8A97A3)
val KitCloud = Color(0xFFE3E9EF)
val KitFog = Color(0xFFF0F3F7)
val KitPaper = Color(0xFFF7F9FB)
val KitWhite = Color(0xFFFFFFFF)

// Semantic
val KitError = Color(0xFFBA1A1A)
val KitErrorDark = Color(0xFFFFB4AB)
val KitWarning = Color(0xFFB77800)
val KitWarningDark = Color(0xFFF7C566)
val KitSuccess = KitGreen600
val KitSuccessDark = KitGreen400

// Gold. Money shared out in a group wears it, so it is never mistaken at a glance for an ordinary
// message or for the green of a one-to-one payment. Both variants are darkened well past
// decorative "shiny gold": the light one has to carry ink on it, and the dark one has to sit on a
// near-black chat wallpaper. Byte-identical to the iOS `KitColor.gold` family.
val KitGold = Color(0xFFB07E12)
val KitGoldDark = Color(0xFFE0B044)
val KitGoldContainer = Color(0xFFFCF0CD)
val KitGoldContainerDark = Color(0xFF4A390E)
val KitGoldSheenStart = Color(0xFFD6A636)
val KitGoldSheenEnd = Color(0xFF8C600A)
val KitGoldSheenStartDark = Color(0xFFF6CE6C)
val KitGoldSheenEndDark = Color(0xFF966E1E)

/** Read-receipt blue for double-tick "read" state, matching common messenger conventions. */
val KitReadReceipt = Color(0xFF34B7F1)
val KitReadReceiptDark = Color(0xFF53BDEB)

// Verified-organization blue. Official Kit Pay support wears this and nothing else does: it must
// never be confused with the green KYC/"verified account" family (a claim about the customer) or
// with the read-receipt sky blue (a claim about a message). Deep professional blue in light mode
// so it carries a white check; lifted toward powder blue in dark mode to hold contrast on navy.
val KitVerifiedBlue = Color(0xFF1565C0)
val KitVerifiedBlueDark = Color(0xFF7FB4EA)
val KitVerifiedBlueContainer = Color(0xFFD8E7F9)
val KitVerifiedBlueContainerDark = Color(0xFF173E63)
