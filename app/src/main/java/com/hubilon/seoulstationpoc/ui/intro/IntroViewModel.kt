package com.hubilon.seoulstationpoc.ui.intro

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PermissionItem(
    val title: String,
    val permission: String,
    val description: String,
    val isGranted: Boolean = false
)

data class IntroUiState(
    val permissionItems: List<PermissionItem> = emptyList(),
    val allGranted: Boolean = false,
    val showSettingsDialog: Boolean = false
)

class IntroViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(IntroUiState())
    val uiState: StateFlow<IntroUiState> = _uiState.asStateFlow()

    init {
        refreshPermissionStates()
    }

    private fun buildPermissionList(): List<PermissionItem> = buildList {
        add(PermissionItem(
            title = "위치 권한",
            permission = Manifest.permission.ACCESS_FINE_LOCATION,
            description = "WiFi/BLE 신호 스캔에 필요합니다"
        ))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(PermissionItem(
                title = "블루투스 스캔",
                permission = Manifest.permission.BLUETOOTH_SCAN,
                description = "BLE 기기 탐색에 필요합니다"
            ))
            add(PermissionItem(
                title = "블루투스 연결",
                permission = Manifest.permission.BLUETOOTH_CONNECT,
                description = "BLE 기기 연결에 필요합니다"
            ))
            add(PermissionItem(
                title = "주변 WiFi 기기",
                permission = Manifest.permission.NEARBY_WIFI_DEVICES,
                description = "WiFi RTT(FTM) 거리 측정에 필요합니다"
            ))
        }
    }

    fun refreshPermissionStates() {
        val context = getApplication<Application>()
        val items = buildPermissionList().map { item ->
            item.copy(
                isGranted = ContextCompat.checkSelfPermission(context, item.permission)
                        == PackageManager.PERMISSION_GRANTED
            )
        }
        _uiState.update { it.copy(
            permissionItems = items,
            allGranted = items.all { it.isGranted }
        )}
    }

    fun updatePermissionResults(results: Map<String, Boolean>) {
        _uiState.update { state ->
            val updated = state.permissionItems.map { item ->
                item.copy(isGranted = results[item.permission] ?: item.isGranted)
            }
            state.copy(
                permissionItems = updated,
                allGranted = updated.all { it.isGranted }
            )
        }
    }

    fun getPendingPermissions(): List<String> =
        _uiState.value.permissionItems.filter { !it.isGranted }.map { it.permission }

    fun setShowSettingsDialog(show: Boolean) {
        _uiState.update { it.copy(showSettingsDialog = show) }
    }
}
