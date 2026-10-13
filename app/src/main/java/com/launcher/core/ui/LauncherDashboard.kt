package com.launcher.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.launcher.core.data.processing.ProcessingState
import com.launcher.core.domain.ShizukuState

/** UI tối thiểu chứng minh StateFlow được nối trực tiếp; onPickMod mở Android document picker ở Activity. */
@Composable
fun LauncherDashboard(
    viewModel: MainViewModel,
    onPickMod: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    Column(
        modifier = modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Flarial Launcher", style = MaterialTheme.typography.headlineSmall)
        Text("Shizuku: ${state.shizukuState.toUiLabel()}")
        Text("Discord: ${state.discordState.toUiLabel()}")

        if (state.shizukuState is ShizukuState.PermissionRequired) {
            Button(onClick = viewModel::requestShizukuPermission, modifier = Modifier.fillMaxWidth()) {
                Text("Cấp quyền Shizuku")
            }
        }
        if (state.shizukuState is ShizukuState.Error) {
            Button(onClick = viewModel::refreshShizukuState, modifier = Modifier.fillMaxWidth()) {
                Text("Thử kết nối lại")
            }
        }

        Button(
            onClick = onPickMod,
            enabled = !state.isBusy && state.shizukuState is ShizukuState.Ready,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (state.isBusy) "Đang xử lý…" else "Chọn gói .mcpack / .mcaddon") }

        when (val progress = state.processingState) {
            is ProcessingState.Progress -> {
                Text("${progress.percent}% · ${progress.currentFile}")
                LinearProgressIndicator(
                    progress = { progress.percent.coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                progress.sha256?.let { Text("SHA-256: $it", style = MaterialTheme.typography.bodySmall) }
            }
            is ProcessingState.Success -> {
                Text("Hoàn tất: ${progress.category}")
                Text("SHA-256: ${progress.sha256}", style = MaterialTheme.typography.bodySmall)
            }
            is ProcessingState.Error -> Text(
                "Lỗi: ${progress.exception.message ?: "Không rõ nguyên nhân"}",
                color = MaterialTheme.colorScheme.error,
            )
            null -> Unit
        }
        state.message?.let { Text(it) }
    }
}

private fun ShizukuState.toUiLabel(): String = when (this) {
    ShizukuState.NotInstalled -> "Chưa cài Shizuku"
    ShizukuState.NotRunning -> "Shizuku chưa chạy"
    ShizukuState.PermissionRequired -> "Cần cấp quyền"
    ShizukuState.PermissionDenied -> "Quyền bị từ chối"
    ShizukuState.Binding -> "Đang kết nối"
    is ShizukuState.Ready -> "Sẵn sàng"
    is ShizukuState.Error -> "Lỗi: ${cause.message ?: "không rõ"}"
}

private fun DiscordAuthState.toUiLabel(): String = when (this) {
    DiscordAuthState.SignedOut -> "Chưa xác thực"
    DiscordAuthState.Authenticating -> "Đang xác thực"
    is DiscordAuthState.Authenticated -> "Đã kết nối${displayName?.let { ": $it" } ?: ""}"
    is DiscordAuthState.Failed -> "Lỗi: $message"
}
