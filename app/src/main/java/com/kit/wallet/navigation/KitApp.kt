package com.kit.wallet.navigation

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kit.wallet.data.auth.AuthChallengeKind
import com.kit.wallet.data.notifications.IncomingCallPayload
import com.kit.wallet.data.remote.KitFeature
import com.kit.wallet.feature.auth.AuthViewModel
import com.kit.wallet.feature.auth.AccountAccessViewModel
import com.kit.wallet.feature.auth.ForgotPasswordScreen
import com.kit.wallet.feature.auth.OtpScreen
import com.kit.wallet.feature.auth.PhoneLoginScreen
import com.kit.wallet.feature.auth.PinSetupScreen
import com.kit.wallet.feature.auth.RegisterScreen
import com.kit.wallet.feature.auth.ResetPasswordScreen
import com.kit.wallet.feature.auth.VerifyEmailScreen
import com.kit.wallet.feature.bank.BankScreen
import com.kit.wallet.feature.bills.AirtimeScreen
import com.kit.wallet.feature.bills.BillPayScreen
import com.kit.wallet.feature.bills.BillsScreen
import com.kit.wallet.feature.calls.ActiveCallScreen
import com.kit.wallet.feature.calls.CallsScreen
import com.kit.wallet.feature.chat.ChatsScreen
import com.kit.wallet.feature.chat.ConversationScreen
import com.kit.wallet.feature.chat.IncomingTextShareCoordinator
import com.kit.wallet.feature.chat.IncomingTextShareRequest
import com.kit.wallet.feature.contacts.ContactPickerPurpose
import com.kit.wallet.feature.contacts.ContactsScreen
import com.kit.wallet.feature.home.HomeScreen
import com.kit.wallet.feature.mobilemoney.MobileMoneyScreen
import com.kit.wallet.feature.onboarding.OnboardingScreen
import com.kit.wallet.feature.settings.SecurityScreen
import com.kit.wallet.feature.settings.MfaScreen
import com.kit.wallet.feature.settings.KycScreen
import com.kit.wallet.feature.settings.ProfileEditorScreen
import com.kit.wallet.feature.settings.SettingsScreen
import com.kit.wallet.feature.settings.KIT_PRIVACY_POLICY_URL
import com.kit.wallet.feature.wallet.ReceiveScreen
import com.kit.wallet.feature.wallet.RequestMoneyScreen
import com.kit.wallet.feature.wallet.ScanScreen
import com.kit.wallet.feature.wallet.SendMoneyScreen
import com.kit.wallet.feature.wallet.TransactionDetailScreen
import com.kit.wallet.feature.wallet.TransactionsScreen

private data class Tab(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val activeIcon: ImageVector,
    val badge: Int = 0,
)

@Composable
fun KitApp(
    deepLinkUri: String? = null,
    onDeepLinkConsumed: () -> Unit = {},
    secureMessageConversationId: String? = null,
    onSecureMessageRouteConsumed: () -> Unit = {},
    incomingTextShare: IncomingTextShareRequest? = null,
    onTextShareConsumed: (String) -> Unit = {},
    onTextShareSendingChanged: (String, Boolean) -> Unit = { _, _ -> },
    authViewModel: AuthViewModel = hiltViewModel(),
    accountAccessViewModel: AccountAccessViewModel = hiltViewModel(),
    capabilitiesViewModel: AppCapabilitiesViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()
    val signedIn by authViewModel.signedIn.collectAsStateWithLifecycle()
    val profileSetupRequired by authViewModel.profileSetupRequired.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val notificationPermissionRequested = rememberSaveable { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { notificationPermissionRequested.value = true }
    val authState by authViewModel.uiState.collectAsStateWithLifecycle()
    val capabilities by capabilitiesViewModel.state.collectAsStateWithLifecycle()
    val tabs = buildList {
        add(Tab(Dest.HOME, "Home", Icons.Outlined.AccountBalanceWallet, Icons.Filled.AccountBalanceWallet))
        if (capabilities.messagingEntryVisible) {
            add(Tab(Dest.CHATS, "Messages", Icons.Outlined.ChatBubbleOutline, Icons.Filled.ChatBubble))
        }
        if (capabilities.enabled(KitFeature.CALLS)) {
            add(Tab(Dest.CALLS, "Calls", Icons.Outlined.Call, Icons.Filled.Call))
        }
        add(Tab(Dest.SETTINGS, "Profile", Icons.Outlined.Person, Icons.Filled.Person))
    }

    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val signInFlowActive = currentRoute in SIGN_IN_ROUTES
    val showBottomBar = signedIn && currentRoute in tabs.map { it.route }

    LaunchedEffect(
        deepLinkUri,
        secureMessageConversationId,
        signedIn,
        capabilities.loaded,
        capabilities.loadFailed,
        capabilities.features,
        capabilities.messagingServerCompatible,
        capabilities.secureMessagingClientReady,
    ) {
        val rawDeepLink = deepLinkUri
        if (rawDeepLink == null && secureMessageConversationId == null) return@LaunchedEffect
        val incomingCall = rawDeepLink?.let { IncomingCallPayload.fromDeepLink(it) }
        val uri = rawDeepLink?.let { Uri.parse(it) }
        val isKycReturn = uri?.let {
            it.scheme == "kitwallet" && it.host == "kyc" && it.path == "/status"
        } == true
        when {
            incomingCall != null -> {
                if (!signedIn || !capabilities.loaded || capabilities.loadFailed) {
                    // Keep the call pending until session and fail-closed capability checks finish.
                    return@LaunchedEffect
                }
                if (capabilities.enabled(KitFeature.CALLS)) {
                    navController.navigate(
                        Dest.incomingCall(incomingCall.callId),
                    ) { launchSingleTop = true }
                }
                onDeepLinkConsumed()
            }
            secureMessageConversationId != null -> {
                if (!signedIn || !capabilities.loaded || capabilities.loadFailed) {
                    // Keep the authenticated route pending until this account's capability
                    // discovery completes. No conversation data is accepted from the push.
                    return@LaunchedEffect
                }
                if (capabilities.messagingServerCompatible &&
                    !capabilities.secureMessagingClientReady
                ) {
                    // Process restart can briefly precede local key/session activation.
                    return@LaunchedEffect
                }
                if (capabilities.messagingUsable) {
                    navController.navigate(Dest.conversation(secureMessageConversationId)) {
                        launchSingleTop = true
                    }
                }
                onSecureMessageRouteConsumed()
            }
            isKycReturn -> {
                if (!signedIn || !capabilities.loaded || capabilities.loadFailed) {
                    // Keep the callback pending so a later successful capability refresh can handle it.
                    return@LaunchedEffect
                }
                if (capabilities.enabled(KitFeature.KYC)) {
                    navController.navigate(Dest.KYC) { launchSingleTop = true }
                }
                onDeepLinkConsumed()
            }
            else -> onDeepLinkConsumed()
        }
    }

    LaunchedEffect(signedIn, currentRoute, capabilities) {
        val authRoutes = setOf(
            Dest.ONBOARDING,
            Dest.PHONE_LOGIN,
            Dest.OTP,
            Dest.REGISTER,
            Dest.VERIFY_EMAIL,
            Dest.FORGOT_PASSWORD,
            Dest.RESET_PASSWORD,
        )
        if (!signedIn && currentRoute != null && currentRoute !in authRoutes) {
            navController.resetTo(Dest.ONBOARDING)
        } else if (!signedIn && !capabilities.routeUsable(currentRoute)) {
            if (!navController.popBackStack(Dest.PHONE_LOGIN, inclusive = false)) {
                navController.resetTo(Dest.PHONE_LOGIN)
            }
        } else if (signedIn && !capabilities.routeUsable(currentRoute)) {
            if (!navController.popBackStack(Dest.HOME, inclusive = false)) {
                navController.resetTo(Dest.HOME)
            }
        }
    }

    LaunchedEffect(signedIn, profileSetupRequired, currentRoute) {
        if (shouldRequireProfileSetup(signedIn, profileSetupRequired, currentRoute)) {
            navController.resetTo(Dest.profileSetup(needsPin = false))
        }
    }

    val notificationsEnabled = capabilities.enabled(KitFeature.NOTIFICATIONS)
    LaunchedEffect(signedIn, notificationsEnabled) {
        if (signedIn && notificationsEnabled &&
            capabilities.pushMessagingConfigured &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED &&
            !notificationPermissionRequested.value
        ) {
            notificationPermissionRequested.value = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(signedIn) {
        capabilitiesViewModel.onSessionChanged()
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    tabs.forEach { tab ->
                        val selected = currentRoute == tab.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                BadgedBox(badge = {
                                    if (tab.badge > 0) Badge { Text(tab.badge.toString()) }
                                }) {
                                    Icon(
                                        if (selected) tab.activeIcon else tab.icon,
                                        contentDescription = tab.label,
                                    )
                                }
                            },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                            ),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        KitNavHost(
            navController = navController,
            startDestination = if (signedIn) Dest.HOME else Dest.ONBOARDING,
            signedIn = signedIn,
            authViewModel = authViewModel,
            accountAccessViewModel = accountAccessViewModel,
            authState = authState,
            capabilities = capabilities,
            modifier = if (showBottomBar) Modifier.padding(innerPadding) else Modifier,
        )
    }

    incomingTextShare?.let { request ->
        IncomingTextShareCoordinator(
            request = request,
            signedIn = signedIn,
            accountSetupRequired = profileSetupRequired || currentRoute in ACCOUNT_SETUP_ROUTES,
            signInFlowActive = signInFlowActive,
            capabilitiesLoaded = capabilities.loaded,
            capabilityLoadFailed = capabilities.loadFailed,
            secureMessagingUsable = capabilities.messagingUsable,
            onSignIn = {
                if (!signInFlowActive) {
                    navController.navigate(Dest.PHONE_LOGIN) { launchSingleTop = true }
                }
            },
            onRetryCapabilities = capabilitiesViewModel::refresh,
            onConsumed = { onTextShareConsumed(request.token) },
            onSendingChanged = { sending ->
                onTextShareSendingChanged(request.token, sending)
            },
        )
    }
}

private val SIGN_IN_ROUTES = setOf(
    Dest.PHONE_LOGIN,
    Dest.OTP,
    Dest.REGISTER,
    Dest.VERIFY_EMAIL,
    Dest.FORGOT_PASSWORD,
    Dest.RESET_PASSWORD,
)

private val ACCOUNT_SETUP_ROUTES = setOf(Dest.PIN_SETUP, Dest.PROFILE_SETUP)

@Composable
private fun KitNavHost(
    navController: NavHostController,
    startDestination: String,
    signedIn: Boolean,
    authViewModel: AuthViewModel,
    accountAccessViewModel: AccountAccessViewModel,
    authState: com.kit.wallet.feature.auth.AuthUiState,
    capabilities: AppCapabilities,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val openAuthenticated: (Boolean, Boolean) -> Unit =
        { needsPaymentPinSetup, needsProfileSetup ->
        val destination = when {
            needsProfileSetup -> Dest.profileSetup(needsPaymentPinSetup)
            needsPaymentPinSetup -> Dest.PIN_SETUP
            else -> Dest.HOME
        }
        navController.navigate(destination) {
            popUpTo(Dest.ONBOARDING) { inclusive = true }
            launchSingleTop = true
        }
    }
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
        enterTransition = {
            slideInHorizontally(tween(280)) { it / 4 } + fadeIn(tween(280))
        },
        exitTransition = { fadeOut(tween(200)) },
        popEnterTransition = { fadeIn(tween(200)) },
        popExitTransition = {
            slideOutHorizontally(tween(280)) { it / 4 } + fadeOut(tween(280))
        },
    ) {
        // --- Auth flow ---
        composable(Dest.ONBOARDING) {
            OnboardingScreen(
                onGetStarted = { navController.navigate(Dest.PHONE_LOGIN) },
                onPrivacyPolicy = {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(KIT_PRIVACY_POLICY_URL),
                            ).addCategory(android.content.Intent.CATEGORY_BROWSABLE),
                        )
                    }
                },
            )
        }
        composable(Dest.PHONE_LOGIN) {
            PhoneLoginScreen(
                onBack = { navController.popBackStack() },
                loading = authState.loading,
                error = authState.error,
                onPhoneContinue = { phone ->
                    authViewModel.requestPhoneOtp(
                        rawPhone = phone,
                        onChallengeReady = { navController.navigate(Dest.OTP) },
                    )
                },
                onEmailContinue = { email, password ->
                    authViewModel.loginWithEmail(
                        email = email,
                        password = password,
                        onChallengeReady = { navController.navigate(Dest.OTP) },
                        onAuthenticated = openAuthenticated,
                    )
                },
                onCreateAccount = { navController.navigate(Dest.REGISTER) },
                onForgotPassword = { email ->
                    accountAccessViewModel.setEmail(email)
                    navController.navigate(Dest.FORGOT_PASSWORD)
                },
                onVerifyEmail = { email ->
                    accountAccessViewModel.setEmail(email)
                    navController.navigate(Dest.VERIFY_EMAIL)
                },
                emailRegistrationAvailable = capabilities.enabled(KitFeature.EMAIL_REGISTRATION),
                emailRecoveryAvailable = capabilities.enabled(KitFeature.EMAIL_RECOVERY),
            )
        }
        composable(Dest.REGISTER) {
            FeatureRouteContent(!signedIn, capabilities, Dest.REGISTER) {
                RegisterScreen(
                    onBack = { navController.popBackStack() },
                    onRegistered = { navController.navigate(Dest.VERIFY_EMAIL) },
                    viewModel = accountAccessViewModel,
                )
            }
        }
        composable(Dest.VERIFY_EMAIL) {
            VerifyEmailScreen(
                onBack = { navController.popBackStack() },
                onVerified = {
                    navController.navigate(Dest.PHONE_LOGIN) {
                        popUpTo(Dest.PHONE_LOGIN) { inclusive = false }
                        launchSingleTop = true
                    }
                },
                viewModel = accountAccessViewModel,
            )
        }
        composable(Dest.FORGOT_PASSWORD) {
            FeatureRouteContent(!signedIn, capabilities, Dest.FORGOT_PASSWORD) {
                ForgotPasswordScreen(
                    onBack = { navController.popBackStack() },
                    onRequested = { navController.navigate(Dest.RESET_PASSWORD) },
                    viewModel = accountAccessViewModel,
                )
            }
        }
        composable(Dest.RESET_PASSWORD) {
            ResetPasswordScreen(
                onBack = { navController.popBackStack() },
                onReset = {
                    navController.navigate(Dest.PHONE_LOGIN) {
                        popUpTo(Dest.PHONE_LOGIN) { inclusive = false }
                        launchSingleTop = true
                    }
                },
                viewModel = accountAccessViewModel,
            )
        }
        composable(Dest.OTP) {
            val challenge = authState.pendingChallenge
            OtpScreen(
                destination = challenge?.destination
                    ?: authState.pendingPhone
                    ?: "your enrolled verification method",
                loading = authState.loading,
                error = authState.error,
                resendSupported = challenge?.kind in setOf(
                    AuthChallengeKind.PHONE_OTP,
                ),
                challengeId = challenge?.id,
                challengeKind = challenge?.kind,
                resendNotBeforeEpochMillis = authState.resendNotBeforeEpochMillis,
                authenticatorChallenge = challenge?.kind == AuthChallengeKind.TWO_FACTOR &&
                    challenge.method.equals("totp", ignoreCase = true),
                onBack = {
                    authViewModel.clearPendingChallenge()
                    navController.popBackStack()
                },
                onVerify = { code -> authViewModel.verifyCode(code, openAuthenticated) },
                onResend = authViewModel::resendPhoneOtp,
            )
        }
        composable(Dest.PIN_SETUP) {
            AuthenticatedRouteContent(signedIn) {
                PinSetupScreen(
                    onDone = {
                        navController.navigate(Dest.HOME) {
                            popUpTo(Dest.PIN_SETUP) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }
        }
        composable(
            route = Dest.PROFILE_SETUP,
            arguments = listOf(
                navArgument("needsPin") {
                    type = NavType.BoolType
                    defaultValue = false
                },
            ),
        ) { entry ->
            AuthenticatedRouteContent(signedIn) {
                val needsPin = entry.arguments?.getBoolean("needsPin") == true
                val continueAfterProfile = {
                    navController.navigate(if (needsPin) Dest.PIN_SETUP else Dest.HOME) {
                        popUpTo(Dest.PROFILE_SETUP) { inclusive = true }
                        launchSingleTop = true
                    }
                }
                ProfileEditorScreen(
                    setup = true,
                    onDone = continueAfterProfile,
                )
            }
        }
        composable(Dest.PIN_CHANGE) {
            AuthenticatedRouteContent(signedIn) {
                PinSetupScreen(
                    requireCurrentPin = true,
                    onDone = { navController.popBackStack() },
                )
            }
        }

        // --- Tabs ---
        composable(Dest.HOME) {
            AuthenticatedRouteContent(signedIn) {
                HomeScreen(
                    capabilities = capabilities,
                    onSend = { navController.navigate(Dest.SEND) },
                    onReceive = { navController.navigate(Dest.RECEIVE) },
                    onScan = { navController.navigate(Dest.SCAN) },
                    onBills = { navController.navigate(Dest.BILLS) },
                    onAirtime = { navController.navigate(Dest.AIRTIME) },
                    onBank = { navController.navigate(Dest.BANK) },
                    onMobileMoney = { navController.navigate(Dest.MOBILE_MONEY) },
                    onRequest = { navController.navigate(Dest.REQUEST) },
                    onKyc = { navController.navigate(Dest.KYC) },
                    onAllTransactions = { navController.navigate(Dest.TRANSACTIONS) },
                    onTransaction = { navController.navigate(Dest.txDetail(it)) },
                )
            }
        }
        composable(Dest.CHATS) {
            FeatureRouteContent(signedIn, capabilities, Dest.CHATS) {
                ChatsScreen(
                    onChat = { navController.navigate(Dest.conversation(it)) },
                    onNewChat = { navController.navigate(Dest.CONTACTS) },
                )
            }
        }
        composable(Dest.CALLS) {
            FeatureRouteContent(signedIn, capabilities, Dest.CALLS) {
                CallsScreen(
                    onVoiceCall = { navController.navigate(Dest.voiceCall(it)) },
                    onVideoCall = { navController.navigate(Dest.videoCall(it)) },
                    onNewCall = { navController.navigate(Dest.CALL_CONTACTS) },
                )
            }
        }
        composable(Dest.SETTINGS) {
            AuthenticatedRouteContent(signedIn) {
                SettingsScreen(
                    capabilities = capabilities,
                    onEditProfile = { navController.navigate(Dest.PROFILE_EDIT) },
                    onSecurity = { navController.navigate(Dest.SECURITY) },
                    onKyc = { navController.navigate(Dest.KYC) },
                    onLogoutCurrentDevice = authViewModel::logoutCurrentDevice,
                    logoutBusy = authState.loading,
                    logoutError = authState.error,
                    onDismissLogoutError = authViewModel::clearError,
                )
            }
        }

        // --- Wallet ---
        composable(Dest.SEND) {
            FeatureRouteContent(signedIn, capabilities, Dest.SEND) {
                SendMoneyScreen(
                    onBack = { navController.popBackStack() },
                    onDone = { navController.popBackStack(Dest.HOME, inclusive = false) },
                )
            }
        }
        composable(Dest.RECEIVE) {
            FeatureRouteContent(signedIn, capabilities, Dest.RECEIVE) {
                ReceiveScreen(onBack = { navController.popBackStack() })
            }
        }
        composable(Dest.SCAN) {
            FeatureRouteContent(signedIn, capabilities, Dest.SCAN) {
                ScanScreen(onBack = { navController.popBackStack() })
            }
        }
        composable(Dest.REQUEST) {
            FeatureRouteContent(signedIn, capabilities, Dest.REQUEST) {
                RequestMoneyScreen(
                    onBack = { navController.popBackStack() },
                    onDone = { navController.popBackStack(Dest.HOME, inclusive = false) },
                )
            }
        }
        composable(Dest.TRANSACTIONS) {
            FeatureRouteContent(signedIn, capabilities, Dest.TRANSACTIONS) {
                TransactionsScreen(
                    onBack = { navController.popBackStack() },
                    onTransaction = { navController.navigate(Dest.txDetail(it)) },
                )
            }
        }
        composable(Dest.TX_DETAIL) { entry ->
            FeatureRouteContent(signedIn, capabilities, Dest.TX_DETAIL) {
                TransactionDetailScreen(
                    txId = entry.arguments?.getString("txId").orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }
        }

        // --- Bills & bank ---
        composable(Dest.BILLS) {
            FeatureRouteContent(signedIn, capabilities, Dest.BILLS) {
                BillsScreen(
                    airtimeEnabled = capabilities.enabled(KitFeature.AIRTIME),
                    onBack = { navController.popBackStack() },
                    onProvider = { navController.navigate(Dest.billPay(it)) },
                    onAirtime = { navController.navigate(Dest.AIRTIME) },
                )
            }
        }
        composable(Dest.BILL_PAY) { entry ->
            FeatureRouteContent(signedIn, capabilities, Dest.BILL_PAY) {
                BillPayScreen(
                    providerId = entry.arguments?.getString("providerId").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onDone = { navController.popBackStack(Dest.HOME, inclusive = false) },
                )
            }
        }
        composable(Dest.AIRTIME) {
            FeatureRouteContent(signedIn, capabilities, Dest.AIRTIME) {
                AirtimeScreen(
                    onBack = { navController.popBackStack() },
                    onDone = { navController.popBackStack(Dest.HOME, inclusive = false) },
                )
            }
        }
        composable(Dest.BANK) {
            FeatureRouteContent(signedIn, capabilities, Dest.BANK) {
                BankScreen(onBack = { navController.popBackStack() })
            }
        }
        composable(Dest.MOBILE_MONEY) {
            FeatureRouteContent(signedIn, capabilities, Dest.MOBILE_MONEY) {
                MobileMoneyScreen(onBack = { navController.popBackStack() })
            }
        }

        // --- Messaging, calls, contacts ---
        composable(Dest.CONTACTS) {
            FeatureRouteContent(signedIn, capabilities, Dest.CONTACTS) {
                ContactsScreen(
                    onBack = { navController.popBackStack() },
                    onContact = { chatId ->
                        navController.navigate(Dest.conversation(chatId)) {
                            popUpTo(Dest.CONTACTS) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }
        }
        composable(Dest.CALL_CONTACTS) {
            FeatureRouteContent(signedIn, capabilities, Dest.CALL_CONTACTS) {
                ContactsScreen(
                    onBack = { navController.popBackStack() },
                    purpose = ContactPickerPurpose.CALL,
                    onVoiceCall = { navController.navigate(Dest.voiceCall(it)) },
                    onVideoCall = { navController.navigate(Dest.videoCall(it)) },
                )
            }
        }
        composable(Dest.CONVERSATION) { entry ->
            FeatureRouteContent(signedIn, capabilities, Dest.CONVERSATION) {
                ConversationScreen(
                    chatId = entry.arguments?.getString("chatId").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onVoiceCall = { navController.navigate(Dest.voiceCall(it)) },
                    onVideoCall = { navController.navigate(Dest.videoCall(it)) },
                )
            }
        }
        composable(Dest.VOICE_CALL) { entry ->
            FeatureRouteContent(signedIn, capabilities, Dest.VOICE_CALL) {
                ActiveCallScreen(
                    name = entry.arguments?.getString("name").orEmpty(),
                    video = false,
                    onEnd = { navController.popBackStack() },
                )
            }
        }
        composable(Dest.VIDEO_CALL) { entry ->
            FeatureRouteContent(signedIn, capabilities, Dest.VIDEO_CALL) {
                ActiveCallScreen(
                    name = entry.arguments?.getString("name").orEmpty(),
                    video = true,
                    onEnd = { navController.popBackStack() },
                )
            }
        }
        composable(
            route = Dest.INCOMING_CALL,
            arguments = listOf(
                navArgument("callId") { type = NavType.StringType },
            ),
        ) {
            FeatureRouteContent(signedIn, capabilities, Dest.INCOMING_CALL) {
                ActiveCallScreen(
                    name = "Incoming Kit Pay call",
                    video = false,
                    onEnd = { navController.popBackStack() },
                )
            }
        }

        // --- Settings ---
        composable(Dest.PROFILE_EDIT) {
            AuthenticatedRouteContent(signedIn) {
                ProfileEditorScreen(
                    setup = false,
                    onDone = { navController.popBackStack() },
                    onBack = { navController.popBackStack() },
                )
            }
        }
        composable(Dest.SECURITY) {
            AuthenticatedRouteContent(signedIn) {
                SecurityScreen(
                    onBack = { navController.popBackStack() },
                    onWalletPin = { navController.navigate(Dest.PIN_CHANGE) },
                    onMfa = { navController.navigate(Dest.MFA) },
                )
            }
        }
        composable(Dest.MFA) {
            AuthenticatedRouteContent(signedIn) {
                MfaScreen(onBack = { navController.popBackStack() })
            }
        }
        composable(Dest.KYC) {
            FeatureRouteContent(signedIn, capabilities, Dest.KYC) {
                KycScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

internal fun shouldRequireProfileSetup(
    signedIn: Boolean,
    profileSetupRequired: Boolean,
    currentRoute: String?,
): Boolean = signedIn && profileSetupRequired && currentRoute != null && currentRoute !in setOf(
    Dest.ONBOARDING,
    Dest.PHONE_LOGIN,
    Dest.OTP,
    Dest.REGISTER,
    Dest.VERIFY_EMAIL,
    Dest.FORGOT_PASSWORD,
    Dest.RESET_PASSWORD,
    Dest.PIN_SETUP,
    Dest.PROFILE_SETUP,
    Dest.PROFILE_EDIT,
)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun NavHostController.resetTo(route: String) {
    navigate(route) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}

@Composable
private fun FeatureRouteContent(
    routeAccess: Boolean,
    capabilities: AppCapabilities,
    route: String,
    content: @Composable () -> Unit,
) {
    if (routeAccess && capabilities.routeUsable(route)) content()
}

@Composable
private fun AuthenticatedRouteContent(
    signedIn: Boolean,
    content: @Composable () -> Unit,
) {
    if (signedIn) content()
}
