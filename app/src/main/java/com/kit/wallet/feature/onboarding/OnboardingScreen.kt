package com.kit.wallet.feature.onboarding

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.kit.wallet.BuildConfig
import com.kit.wallet.R
import com.kit.wallet.feature.legal.OpenSourceLicenceDialog
import com.kit.wallet.feature.legal.isTrustedKitReleaseSourceUrl
import com.kit.wallet.feature.legal.openSourceLicencePresentation
import com.kit.wallet.ui.components.KitGreenButton
import com.kit.wallet.ui.theme.KitWalletTheme

private data class Slide(val icon: ImageVector, val title: String, val body: String)

private val slides = listOf(
    Slide(
        Icons.Rounded.Bolt,
        "Your money, securely connected",
        "Send, receive and request money with people on Kit Pay, with every financial change confirmed by Kit Pay.",
    ),
    Slide(
        Icons.Rounded.Call,
        "Voice and video calls",
        "Call other Kit Pay users when calling is enabled for your account and device.",
    ),
    Slide(
        Icons.Rounded.Lock,
        "Protected by design",
        "Wallet PIN authorization, authenticator codes and device-session controls help protect your account.",
    ),
)

@Composable
fun OnboardingScreen(
    onGetStarted: () -> Unit,
    onPrivacyPolicy: () -> Unit,
) {
    val context = LocalContext.current
    val pagerState = rememberPagerState { slides.size }
    var showOpenSourceLicence by rememberSaveable { mutableStateOf(false) }

    if (showOpenSourceLicence) {
        val presentation = openSourceLicencePresentation(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
        )
        OpenSourceLicenceDialog(
            presentation = presentation,
            onOpenSource = {
                if (isTrustedKitReleaseSourceUrl(presentation.sourceUrl)) {
                    val intent = Intent(Intent.ACTION_VIEW, presentation.sourceUrl.toUri()).apply {
                        addCategory(Intent.CATEGORY_BROWSABLE)
                    }
                    if (runCatching { context.startActivity(intent) }.isSuccess) {
                        showOpenSourceLicence = false
                    }
                }
            },
            onDismiss = { showOpenSourceLicence = false },
        )
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(28.dp))
            Image(
                painter = painterResource(R.drawable.ic_kit_logo_lockup),
                contentDescription = "Kit Pay",
                modifier = Modifier.height(44.dp),
            )
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
            ) { page ->
                val slide = slides[page]
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier
                            .size(140.dp)
                            .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            slide.icon,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    Spacer(Modifier.height(36.dp))
                    Text(
                        slide.title,
                        style = MaterialTheme.typography.headlineMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        slide.body,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Row(
                Modifier.padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                repeat(slides.size) { i ->
                    val active = pagerState.currentPage == i
                    Box(
                        Modifier
                            .size(width = if (active) 24.dp else 8.dp, height = 8.dp)
                            .background(
                                if (active) MaterialTheme.colorScheme.secondary
                                else MaterialTheme.colorScheme.outlineVariant,
                                CircleShape,
                            ),
                    )
                }
            }
            Column(Modifier.padding(horizontal = 24.dp)) {
                KitGreenButton(text = "Get started", onClick = onGetStarted)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Review how Kit Pay handles your data before continuing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 2.dp),
                )
                TextButton(
                    onClick = onPrivacyPolicy,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) { Text("Privacy policy") }
                TextButton(
                    onClick = { showOpenSourceLicence = true },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text("Open-source licence")
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OnboardingPreview() {
    KitWalletTheme { OnboardingScreen(onGetStarted = {}, onPrivacyPolicy = {}) }
}
