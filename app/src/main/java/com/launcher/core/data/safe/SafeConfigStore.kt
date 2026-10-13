package com.launcher.core.data.safe

import com.launcher.core.domain.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Giao dịch ghi cấu hình an toàn. Bốn lớp bảo vệ (từ ngoài vào trong):
 *  1. Mutex trong tiến trình   - chặn 2 coroutine của Launcher ghi chồng nhau.
 *  2. FileLock liên tiến trình - chặn Launcher/Worker/instance khác của Launcher (khoá giữ ở service shell).
 *  3. Game-running guard       - Minecraft KHÔNG biết đến lock của ta (advisory lock chỉ có tác dụng với bên hợp tác),
 *                                nên cách duy nhất tránh xung đột với game là không ghi khi game đang chạy.
 *  4. Atomic write + backup + verify hash - nếu có sự cố, file không bao giờ ở trạng thái nửa vời; rollback được.
 *
 * Race còn lại (game bật giữa lúc check và ghi) được loại bỏ vì Launcher là bên duy nhất khởi động game
 * và chỉ làm vậy SAU KHI giao dịch kết thúc (xem LaunchOrchestrator).
 */
class SafeConfigStore(
    private val fs: PrivilegedFs,
    private val lockTimeoutMs: Long = 3_000,
) {
    sealed interface Result {
        data object Ok : Result
        data object GameRunning : Result
        data object LockTimeout : Result
        data class VerifyFailed(val rolledBack: Boolean) : Result
    }

    private val inProcess = Mutex()

    suspend fun update(
        relativePath: String,                 // vd "minecraftpe/options.txt"
        transform: (current: ByteArray?) -> ByteArray,
    ): Result = inProcess.withLock {
        val path = "${GamePaths.GAME_DATA}/$relativePath"
        if (fs.isProcessRunning(GamePaths.PACKAGE)) return@withLock Result.GameRunning

        val lockName = relativePath.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(100)
        if (!fs.tryLock(lockName, lockTimeoutMs)) return@withLock Result.LockTimeout
        try {
            val old = fs.read(path)
            if (old != null) fs.writeAtomic("$path.launcher.bak", old)    // điểm khôi phục

            val new = transform(old)
            fs.writeAtomic(path, new)

            if (fs.sha256(path) == sha256Hex(new)) Result.Ok
            else {
                // Hash lệch => khôi phục bản cũ, báo lỗi rõ ràng thay vì để dữ liệu hỏng âm thầm.
                val restored = old?.let { runCatching { fs.writeAtomic(path, it) }.isSuccess } ?: fs.delete(path)
                Result.VerifyFailed(restored)
            }
        } finally {
            fs.unlock(lockName)
        }
    }
}
