package com.kit.wallet.feature.bills

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kit.wallet.data.demo.DemoData
import com.kit.wallet.ui.model.BillProvider
import com.kit.wallet.ui.theme.KitWalletTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillsScreen(
    airtimeEnabled: Boolean,
    onBack: () -> Unit,
    onProvider: (String) -> Unit,
    onAirtime: () -> Unit,
    viewModel: BillsViewModel = hiltViewModel(),
) {
    val providers by viewModel.providers.collectAsStateWithLifecycle()
    val airtimeProducts by viewModel.airtimeProducts.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    BillsContent(
        providers = providers,
        airtimeAvailable = airtimeEnabled && airtimeProducts.isNotEmpty(),
        loading = loading,
        error = error,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onProvider = onProvider,
        onAirtime = onAirtime,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BillsContent(
    providers: List<BillProvider>,
    airtimeAvailable: Boolean,
    loading: Boolean,
    error: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onProvider: (String) -> Unit,
    onAirtime: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pay bills") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh, enabled = !loading) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh providers")
                    }
                },
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (error != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (loading && providers.isEmpty() && !airtimeAvailable) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "Loading available RukaPay services…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!loading && error == null && providers.isEmpty() && !airtimeAvailable) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "No bill or airtime services are currently available.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (airtimeAvailable) {
                item {
                    ProviderCard("Airtime", Icons.Rounded.SimCard, onAirtime)
                }
            }
            items(providers, key = BillProvider::id) { provider ->
                ProviderCard(
                    title = provider.name,
                    icon = providerIcon(provider.category),
                    onClick = { onProvider(provider.id) },
                )
            }
        }
    }
}

@Composable
private fun ProviderCard(title: String, icon: ImageVector, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(48.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}

private fun providerIcon(category: String): ImageVector = when {
    category.contains("electric", ignoreCase = true) ||
        category.contains("power", ignoreCase = true) -> Icons.Rounded.Bolt
    category.contains("water", ignoreCase = true) -> Icons.Rounded.WaterDrop
    category.contains("internet", ignoreCase = true) -> Icons.Rounded.Wifi
    category.contains("television", ignoreCase = true) ||
        category.equals("tv", ignoreCase = true) -> Icons.Rounded.Tv
    category.contains("school", ignoreCase = true) ||
        category.contains("education", ignoreCase = true) -> Icons.Rounded.School
    else -> Icons.Rounded.Gavel
}

@Preview(showBackground = true)
@Composable
private fun BillsPreview() {
    KitWalletTheme {
        BillsContent(
            providers = DemoData.billProviders,
            airtimeAvailable = true,
            loading = false,
            error = null,
            onBack = {},
            onRefresh = {},
            onProvider = {},
            onAirtime = {},
        )
    }
}
