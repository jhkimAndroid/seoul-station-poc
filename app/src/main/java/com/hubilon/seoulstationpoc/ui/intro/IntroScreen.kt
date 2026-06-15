package com.hubilon.seoulstationpoc.ui.intro

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun IntroScreen(
    onAllPermissionsGranted: () -> Unit,
    viewModel: IntroViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? ComponentActivity

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        viewModel.updatePermissionResults(results)
        // 거부된 권한 중 영구 거부(Don't ask again)된 항목 확인
        val hasPermanentDenial = results.any { (permission, granted) ->
            !granted && activity?.let {
                !ActivityCompat.shouldShowRequestPermissionRationale(it, permission)
            } ?: false
        }
        if (hasPermanentDenial) {
            viewModel.setShowSettingsDialog(true)
        }
    }

    // 화면 진입 시 현재 권한 상태 갱신
    LaunchedEffect(Unit) {
        viewModel.refreshPermissionStates()
    }

    // 모든 권한이 이미 허용된 경우 즉시 이동
    LaunchedEffect(uiState.allGranted) {
        if (uiState.allGranted) onAllPermissionsGranted()
    }

    if (uiState.showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.setShowSettingsDialog(false) },
            title = { Text("권한 설정 필요") },
            text = {
                Text("일부 권한이 영구적으로 거부되었습니다.\n설정 화면에서 직접 권한을 허용해 주세요.")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setShowSettingsDialog(false)
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                    )
                }) { Text("설정으로 이동") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.setShowSettingsDialog(false) }) {
                    Text("닫기")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .systemBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(32.dp))

        // 앱 헤더
        Text(
            text = "📡",
            style = MaterialTheme.typography.displayMedium
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "서울역 위치 측위",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "WiFi · BLE 신호를 수집해\n현재 위치를 지도에 표시합니다",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(48.dp))

        // 권한 섹션
        Text(
            text = "필요한 권한",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))

        uiState.permissionItems.forEach { item ->
            PermissionCard(item = item)
            Spacer(Modifier.height(8.dp))
        }

        // 모든 권한 허용 시 안내 메시지
        if (uiState.allGranted) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "모든 권한이 허용되었습니다",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(40.dp))

        // 실행 버튼
        Button(
            onClick = {
                if (uiState.allGranted) {
                    onAllPermissionsGranted()
                } else {
                    val pending = viewModel.getPendingPermissions()
                    if (pending.isNotEmpty()) {
                        permissionLauncher.launch(pending.toTypedArray())
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Text(
                text = if (uiState.allGranted) "서비스 시작" else "권한 허용하기",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun PermissionCard(item: PermissionItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (item.isGranted)
                MaterialTheme.colorScheme.secondaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 상태 인디케이터
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(
                        if (item.isGranted) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.outlineVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (item.isGranted) "✓" else "!",
                    color = if (item.isGranted) MaterialTheme.colorScheme.onSecondary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = item.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 상태 배지
            Surface(
                shape = MaterialTheme.shapes.small,
                color = if (item.isGranted) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.errorContainer
            ) {
                Text(
                    text = if (item.isGranted) "허용됨" else "필요",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (item.isGranted) MaterialTheme.colorScheme.onSecondary
                            else MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}
