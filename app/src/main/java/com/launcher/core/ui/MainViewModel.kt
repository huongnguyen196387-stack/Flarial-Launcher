package com.launcher.core.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.launcher.core.data.performance.PerformanceOptimizer
import com.launcher.core.data.processing.BigDataProcessor
import com.launcher.core.data.processing.ProcessingState
import com.launcher.core.data.shizuku.ShizukuConnectionManager
import com.launcher.core.domain.ShizukuState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/** State duy nhất mà Compose cần quan sát cho dashboard. */
data class DashboardUiState(
    val shizukuState: ShizukuState,
    val processingState: ProcessingState? = null,
    val discordState: DiscordAuthState = DiscordAuthState.SignedOut,
    val isBusy: Boolean = false,
    val message: String? = null,
)

/**
 * Cầu nối: Shizuku StateFlow -> optimizer/Discord -> BigDataProcessor -> UI StateFlow.
 * Không giữ Activity/Context trong ViewModel; dependency được truyền vào qua factory/DI.
 */
class MainViewModel(
    private val shizuku: ShizukuConnectionManager,
    private val optimizer: PerformanceOptimizer,
    private val discord: DiscordAuthGateway,
) : ViewModel() {
    private val processing = MutableStateFlow<ProcessingState?>(null)
    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    val uiState = combine(shizuku.state, processing, discord.state, busy, message) {
            shizukuState, processingState, discordState, isBusy, currentMessage ->
        DashboardUiState(
            shizukuState = shizukuState,
            processingState = processingState,
            discordState = discordState,
            isBusy = isBusy,
            message = currentMessage,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DashboardUiState(
            shizukuState = shizuku.state.value,
            discordState = discord.state.value,
        ),
    )

    init { shizuku.start() }

    fun requestShizukuPermission() = shizuku.requestPermission()
    fun refreshShizukuState() = shizuku.refresh()

    fun importMod(file: File, expectedSha256: String? = null) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            processing.value = null
            message.value = null
            try {
                val initialReady = shizuku.state.value as? ShizukuState.Ready
                if (initialReady == null) {
                    message.value = "Shizuku chưa sẵn sàng: ${shizuku.state.value::class.simpleName}"
                    return@launch
                }
                require(file.isFile && file.canRead()) { "Không đọc được file mod đã chọn" }

                // Chỉ dọn file tạm riêng của launcher; giữ nguyên file đầu vào, kể cả khi nằm trong cacheDir.
                optimizer.executePreLaunchOptimization(preserve = setOf(file))

                // Discord là tích hợp tùy chọn: OAuth thất bại không được chặn cài mod offline.
                try {
                    if (discord.authenticateIfNeeded()) {
                        discord.updateRichPresence("Preparing Minecraft Bedrock pack")
                    } else {
                        message.value = "Chưa xác thực Discord; vẫn tiếp tục cài mod."
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    message.value = "Discord chưa sẵn sàng (${t.message ?: "lỗi xác thực"}); vẫn tiếp tục cài mod."
                }

                // Binder có thể chết trong lúc OAuth chạy; phải lấy state mới trước khi gọi IPC.
                val fs = (shizuku.state.value as? ShizukuState.Ready)?.fs
                if (fs == null) {
                    message.value = "Kết nối Shizuku đã mất. Kết nối lại rồi thử lại."
                    return@launch
                }

                BigDataProcessor(fs).processPackage(file, expectedSha256).collect { state ->
                    processing.value = state
                    when (state) {
                        is ProcessingState.Progress -> Unit
                        is ProcessingState.Success -> {
                            message.value = "Đã cài gói vào ${state.destinationPath}"
                            updatePresenceSafely("Installed Minecraft Bedrock pack")
                        }
                        is ProcessingState.Error -> {
                            message.value = state.exception.message ?: "Cài mod thất bại"
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                processing.value = ProcessingState.Error(t)
                message.value = t.message ?: "Đã xảy ra lỗi không xác định"
            } finally {
                busy.value = false
            }
        }
    }

    private suspend fun updatePresenceSafely(details: String) {
        try {
            discord.updateRichPresence(details)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Rich Presence là tùy chọn; không làm hỏng giao dịch cài pack nếu Discord đang lỗi.
        }
    }

    override fun onCleared() {
        shizuku.stop()
        super.onCleared()
    }
}

/** Factory cho Activity/Compose khi chưa dùng DI container như Hilt/Koin. */
class MainViewModelFactory(
    private val shizuku: ShizukuConnectionManager,
    private val optimizer: PerformanceOptimizer,
    private val discord: DiscordAuthGateway,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (!modelClass.isAssignableFrom(MainViewModel::class.java)) {
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
        @Suppress("UNCHECKED_CAST")
        return MainViewModel(shizuku, optimizer, discord) as T
    }
}
