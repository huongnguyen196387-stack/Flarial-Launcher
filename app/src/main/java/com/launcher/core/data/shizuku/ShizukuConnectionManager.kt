package com.launcher.core.data.shizuku

import android.content.ComponentName
import android.content.Context
import android.os.IBinder
import android.content.ServiceConnection
import android.content.pm.PackageManager
import com.launcher.core.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * Quản lý toàn bộ vòng đời kết nối Shizuku và phơi ra StateFlow<ShizukuState>.
 * UI chỉ quan sát state, không biết gì về Shizuku (DIP).
 *
 * Cần trong Manifest: <queries><package android:name="moe.shizuku.privileged.api"/></queries>
 * (Android 11+ package visibility) và ShizukuProvider (dev.rikka.shizuku:provider).
 */
class ShizukuConnectionManager(private val context: Context) {

    private val _state = MutableStateFlow<ShizukuState>(ShizukuState.NotRunning)
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    private var bound = false
    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, PrivilegedFileService::class.java.name)
    ).processNameSuffix("fs").daemon(false).version(1)

    private val onBinderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val onBinderDead = Shizuku.OnBinderDeadListener {
        bound = false
        // Đừng để Ready cũ ngăn bind lại sau khi binder của Shizuku chết.
        _state.value = ShizukuState.NotRunning
        refresh()
    }
    private val onPermission = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            _state.value = ShizukuState.Ready(ShizukuPrivilegedFs(IPrivilegedFileService.Stub.asInterface(binder)))
        }
        override fun onServiceDisconnected(name: ComponentName) {
            bound = false
            _state.value = ShizukuState.NotRunning
            refresh()
        }
    }

    fun start() {
        Shizuku.addBinderReceivedListenerSticky(onBinderReceived)
        Shizuku.addBinderDeadListener(onBinderDead)
        Shizuku.addRequestPermissionResultListener(onPermission)
        refresh()
    }

    fun stop() {
        Shizuku.removeBinderReceivedListener(onBinderReceived)
        Shizuku.removeBinderDeadListener(onBinderDead)
        Shizuku.removeRequestPermissionResultListener(onPermission)
        unbind()
    }

    /** Gọi từ UI khi state == PermissionRequired. Kết quả đến qua onPermission -> refresh(). */
    fun requestPermission() {
        if (state.value is ShizukuState.PermissionRequired) Shizuku.requestPermission(REQUEST_CODE)
    }

    @Synchronized
    fun refresh() {
        try {
            when {
                !isShizukuInstalled() -> { unbind(); _state.value = ShizukuState.NotInstalled }
                !Shizuku.pingBinder() -> { unbind(); _state.value = ShizukuState.NotRunning }
                Shizuku.isPreV11() -> _state.value = ShizukuState.Error(UnsupportedOperationException("Shizuku quá cũ (<v11)"))
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> bindIfNeeded()
                // true khi người dùng đã chọn từ chối vĩnh viễn -> requestPermission() sẽ không hiện hộp thoại nữa
                Shizuku.shouldShowRequestPermissionRationale() -> _state.value = ShizukuState.PermissionDenied
                else -> _state.value = ShizukuState.PermissionRequired
            }
        } catch (t: Throwable) {
            _state.value = ShizukuState.Error(t)
        }
    }

    private fun bindIfNeeded() {
        if (bound || _state.value is ShizukuState.Ready) return
        bound = true
        _state.value = ShizukuState.Binding
        try {
            Shizuku.bindUserService(userServiceArgs, conn)
        } catch (t: Throwable) {
            bound = false
            _state.value = ShizukuState.Error(t)
            throw t
        }
    }

    private fun unbind() {
        if (!bound) return
        runCatching { Shizuku.unbindUserService(userServiceArgs, conn, true) }
        bound = false
    }

    private fun isShizukuInstalled(): Boolean = try {
        context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }

    private companion object { const val REQUEST_CODE = 0x5A1 }
}

/** Adapter: AIDL (blocking Binder) -> PrivilegedFs (suspend, chạy trên IO dispatcher). */
internal class ShizukuPrivilegedFs(private val svc: IPrivilegedFileService) : PrivilegedFs {
    private suspend fun <T> io(b: () -> T) = withContext(Dispatchers.IO) { b() }
    override suspend fun read(path: String) = io { svc.read(path) }
    override suspend fun writeAtomic(path: String, data: ByteArray) = io { svc.writeAtomic(path, data) }
    override suspend fun openAtomicWrite(path: String): com.launcher.core.domain.AtomicWriteSession {
        val id = io { svc.beginWrite(path) }
        return object : com.launcher.core.domain.AtomicWriteSession {
            @Volatile private var finished = false

            override suspend fun write(data: ByteArray, length: Int) {
                check(!finished) { "Phiên ghi đã kết thúc" }
                require(length in 0..data.size) { "length không hợp lệ" }
                var offset = 0
                while (offset < length) {
                    val end = minOf(offset + MAX_IPC_CHUNK_BYTES, length)
                    // Mỗi transaction < 64 KiB; không truyền nguyên file qua Binder.
                    val chunk = data.copyOfRange(offset, end)
                    io { svc.writeChunk(id, chunk) }
                    offset = end
                }
            }

            override suspend fun commit() {
                check(!finished) { "Phiên ghi đã kết thúc" }
                try {
                    io { svc.commitWrite(id) }
                    finished = true
                } catch (t: Throwable) {
                    finished = true
                    runCatching { withContext(Dispatchers.IO) { svc.abortWrite(id) } }
                    throw t
                }
            }

            override suspend fun abort() {
                if (finished) return
                finished = true
                withContext(Dispatchers.IO) { svc.abortWrite(id) }
            }
        }
    }
    override suspend fun delete(path: String) = io { svc.delete(path) }
    override suspend fun exists(path: String) = io { svc.exists(path) }
    override suspend fun sha256(path: String) = io { svc.sha256(path) }
    override suspend fun isProcessRunning(pkg: String) = io { svc.isProcessRunning(pkg) }
    override suspend fun shell(vararg argv: String) = io {
        val r = svc.shell(arrayOf(*argv)); ShellResult(r[0].toInt(), r[1], r[2])
    }
    override suspend fun tryLock(name: String, timeoutMs: Long) = io { svc.tryLock(name, timeoutMs) }
    override suspend fun unlock(name: String) = io { svc.unlock(name) }

    private companion object { const val MAX_IPC_CHUNK_BYTES = 48 * 1024 }
}
