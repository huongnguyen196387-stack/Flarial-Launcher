package com.launcher.core.domain

import java.security.MessageDigest

/**
 * ===== DOMAIN LAYER (không phụ thuộc Android/Shizuku) =====
 * Chỉ chứa: model, interface (port), use-case. Tầng data triển khai các port này (DIP).
 */
object GamePaths {
    const val PACKAGE = "com.mojang.minecraftpe"
    private const val ROOT = "/storage/emulated/0/Android/data/$PACKAGE/files"
    const val GAME_DATA = "$ROOT/games/com.mojang"
    const val PAYLOAD_DIR = "$ROOT/launcher_payload"
    const val LOCK_DIR = "$PAYLOAD_DIR/.locks"
}

/** Máy trạng thái kết nối Shizuku - mỗi nhánh tương ứng một hành động UI rõ ràng. */
sealed interface ShizukuState {
    data object NotInstalled : ShizukuState        // -> hướng dẫn cài Shizuku
    data object NotRunning : ShizukuState          // -> hướng dẫn khởi động (ADB / Wireless debugging)
    data object PermissionRequired : ShizukuState  // -> gọi requestPermission()
    data object PermissionDenied : ShizukuState    // -> người dùng chọn "từ chối vĩnh viễn": mở app Shizuku để cấp tay
    data object Binding : ShizukuState             // -> đang bind UserService
    data class Ready(val fs: PrivilegedFs) : ShizukuState
    data class Error(val cause: Throwable) : ShizukuState
}

data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String)

/** Port: mọi thao tác đặc quyền đều đi qua interface này (ISP: nhỏ, tập trung). */
interface AtomicWriteSession {
    /** Ghi một phần dữ liệu; implementation phải chia nhỏ trước khi gửi qua Binder. */
    suspend fun write(data: ByteArray, length: Int = data.size)
    /** Flush + fsync + thay file đích bằng rename nguyên tử. */
    suspend fun commit()
    /** Hủy phiên và xóa file tạm; có thể gọi nhiều lần. */
    suspend fun abort()
}

interface PrivilegedFs {
    suspend fun read(path: String): ByteArray?
    suspend fun writeAtomic(path: String, data: ByteArray)
    /** Mở phiên ghi nguyên tử dạng stream để không đưa cả archive vào RAM/Binder. */
    suspend fun openAtomicWrite(path: String): AtomicWriteSession
    suspend fun delete(path: String): Boolean
    suspend fun exists(path: String): Boolean
    suspend fun sha256(path: String): String?
    suspend fun isProcessRunning(pkg: String): Boolean
    suspend fun shell(vararg argv: String): ShellResult
    suspend fun tryLock(name: String, timeoutMs: Long): Boolean
    suspend fun unlock(name: String)
}

interface GameStarter { fun start(): Boolean }

data class PayloadFile(val name: String, val bytes: ByteArray, val sha256: String)
interface PayloadSource { suspend fun load(): List<PayloadFile> }

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
