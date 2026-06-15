package com.hubilon.seoulstationpoc.ui.scan

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hubilon.seoulstationpoc.data.fingerprint.FingerprintEntry
import com.hubilon.seoulstationpoc.data.fingerprint.MISSING_RSSI
import com.hubilon.seoulstationpoc.domain.model.BleSignal
import com.hubilon.seoulstationpoc.domain.model.WifiSignal
import com.hubilon.seoulstationpoc.ui.map.MapViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanDetailScreen(
    viewModel: MapViewModel,
    startSection: String = "wifi",
    onNavigateUp: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val initialTab = when (startSection) {
        "ble"         -> 1
        "fingerprint" -> 2
        else          -> 0
    }
    var selectedTab by remember { mutableIntStateOf(initialTab) }

    val fingerprintEntries = uiState.fingerprintEntries
    val matchCount = fingerprintEntries?.count { it.rssi != MISSING_RSSI } ?: 0

    Scaffold(
        contentWindowInsets = WindowInsets.systemBars,
        topBar = {
            TopAppBar(
                title = { Text("스캔 결과") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "뒤로"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("WiFi (${uiState.scanData.wifiSignals.size})") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("BLE (${uiState.scanData.bleSignals.size})") }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("매칭 ($matchCount/${fingerprintEntries?.size ?: 0})") }
                )
            }

            when (selectedTab) {
                0 -> WifiSignalList(signals = uiState.scanData.wifiSignals)
                1 -> BleSignalList(signals = uiState.scanData.bleSignals)
                2 -> FingerprintList(entries = fingerprintEntries)
            }
        }
    }
}

@Composable
private fun WifiSignalList(signals: List<WifiSignal>) {
    if (signals.isEmpty()) {
        EmptyContent("수집된 WiFi 신호가 없습니다")
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(signals, key = { it.bssid }) { signal ->
                WifiSignalItem(signal)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun BleSignalList(signals: List<BleSignal>) {
    if (signals.isEmpty()) {
        EmptyContent("수집된 BLE 신호가 없습니다")
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(signals, key = { it.deviceAddress }) { signal ->
                BleSignalItem(signal)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun WifiSignalItem(signal: WifiSignal) {
    ListItem(
        headlineContent = {
            Text(
                text = signal.ssid.ifEmpty { "(숨겨진 네트워크)" },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Text(
                text = signal.bssid,
                style = MaterialTheme.typography.bodySmall
            )
        },
        leadingContent = { SignalTypeTag(color = MaterialTheme.colorScheme.primary, label = "W") },
        trailingContent = { RssiChip(signal.rssi) }
    )
}

@Composable
private fun BleSignalItem(signal: BleSignal) {
    ListItem(
        headlineContent = { Text(signal.deviceAddress) },
        leadingContent = { SignalTypeTag(color = MaterialTheme.colorScheme.tertiary, label = "B") },
        trailingContent = { RssiChip(signal.rssi) }
    )
}

@Composable
private fun SignalTypeTag(color: Color, label: String) {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = color
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White
        )
    }
}

@Composable
private fun RssiChip(rssi: Int) {
    val containerColor = when {
        rssi >= -60 -> MaterialTheme.colorScheme.primaryContainer
        rssi >= -75 -> MaterialTheme.colorScheme.tertiaryContainer
        else        -> MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = when {
        rssi >= -60 -> MaterialTheme.colorScheme.onPrimaryContainer
        rssi >= -75 -> MaterialTheme.colorScheme.onTertiaryContainer
        else        -> MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = containerColor
    ) {
        Text(
            text = "$rssi dBm",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = contentColor
        )
    }
}

@Composable
private fun FingerprintList(entries: List<FingerprintEntry>?) {
    if (entries == null) {
        EmptyContent("스캔 후 매칭 결과가 표시됩니다")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        itemsIndexed(entries) { index, entry ->
            FingerprintEntryItem(index = index, entry = entry)
            HorizontalDivider()
        }
    }
}

@Composable
private fun FingerprintEntryItem(index: Int, entry: FingerprintEntry) {
    val isMissing = entry.rssi == MISSING_RSSI
    ListItem(
        headlineContent = {
            Text(
                text = entry.mac,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isMissing) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface
            )
        },
        supportingContent = {
            Text(
                text = "#${index + 1}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        leadingContent = {
            if (entry.isBle) SignalTypeTag(color = MaterialTheme.colorScheme.tertiary, label = "B")
            else             SignalTypeTag(color = MaterialTheme.colorScheme.primary,   label = "W")
        },
        trailingContent = {
            if (isMissing) {
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = "미감지",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                RssiChip(entry.rssi)
            }
        }
    )
}

@Composable
private fun EmptyContent(text: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
