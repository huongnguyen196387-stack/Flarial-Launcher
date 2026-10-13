package com.launcher.core.data.shizuku

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.annotation.Keep
import com.launcher.core.domain.GamePaths
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

/**
 * Shizuku UserService (uid shell). Mọi thao tác ghi đều kiểm tra sandbox và ghi vào file tạm.
 * File lớn dùng beginWrite/writeChunk/commitWrite để tránh Binder transaction quá lớn.
 */
@Keep
class PrivilegedFileService() : IPrivilegedFileService.Stub() {
    @Keep constructor(@Suppress("UNUSED_PARAMETER") context: Context) : this()

    private val allowedRoots = listOf(GamePaths.GAME_DATA, GamePaths.PAYLOAD_DIR)
        .map { File(it).canonicalPath }
    private val locks = ConcurrentHashMap<String, Pair<RandomAccessFile, FileLock>>()
    private val pendingWrites = ConcurrentHashMap<Int, PendingWrite>()
    private val pendingPaths = ConcurrentHashMap<String, Int>()
    private val nextWriteId = AtomicInteger(1)

    private data class PendingWrite(
        val target: File,
        val temp: File,
        val output: FileOutputStream,
    )

    private fun guard(path: String): File {
        require(path.isNotBlank()) { "Đường dẫn rỗng" }
        val file = File(path).canonicalFile
        val allowed = allowedRoots.any { root -> file.path == root || file.path.startsWith("$root/") }
        if (!allowed) throw SecurityException("Path ngoài sandbox: $path")
        return file
    }

    private inline fun <T> io(block: () -> T): T =
        try { block() } catch (e: IOException) { throw IllegalStateException(e.message, e) }

    override fun destroy() {
        pendingWrites.keys.toList().forEach(::abortWrite)
        locks.keys.toList().forEach(::unlock)
        exitProcess(0)
    }

    override fun read(path: String): ByteArray? = io {
        val file = guard(path)
        if (file.isFile) file.readBytes() else null
    }

    /** Chỉ dành cho payload/config nhỏ; file lớn phải dùng phiên ghi stream. */
    override fun writeAtomic(path: String, data: ByteArray) = io {
        val target = guard(path)
        require(target.name.isNotBlank()) { "Tên file đích không hợp lệ" }
        val parent = target.parentFile ?: throw IOException("Thiếu thư mục cha")
        if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("Không tạo được thư mục đích")
        }
        if (target.isDirectory) throw IOException("Đích là thư mục")
        val temp = File(parent, ".${target.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temp).use { stream -> stream.write(data); stream.fd.sync() }
            atomicRename(temp, target)
            makeDexReadOnly(target)
        } catch (t: Throwable) {
            temp.delete()
            throw t
        }
    }

    override fun beginWrite(path: String): Int = io {
        val target = guard(path)
        if (target.isDirectory) throw IOException("Đích là thư mục")
        val parent = target.parentFile ?: throw IOException("Thiếu thư mục cha")
        if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("Không tạo được thư mục đích")
        }
        val id = nextPositiveWriteId()
        if (pendingPaths.putIfAbsent(target.path, id) != null) {
            throw IllegalStateException("Đường dẫn này đang được ghi")
        }
        val temp = File(parent, ".${target.name}.$id.${System.nanoTime()}.tmp")
        try {
            val output = FileOutputStream(temp)
            pendingWrites[id] = PendingWrite(target, temp, output)
            id
        } catch (t: Throwable) {
            pendingPaths.remove(target.path, id)
            temp.delete()
            throw t
        }
    }

    override fun writeChunk(writeId: Int, chunk: ByteArray) = io {
        require(chunk.size <= MAX_CHUNK_BYTES) { "Chunk vượt quá giới hạn 64 KiB" }
        val pending = pendingWrites[writeId] ?: throw IllegalStateException("Phiên ghi không tồn tại")
        synchronized(pending) {
            if (pendingWrites[writeId] !== pending) throw IllegalStateException("Phiên ghi đã kết thúc")
            pending.output.write(chunk)
        }
    }

    override fun commitWrite(writeId: Int) = io {
        val pending = pendingWrites.remove(writeId) ?: throw IllegalStateException("Phiên ghi không tồn tại")
        var committed = false
        try {
            synchronized(pending) {
                pending.output.flush()
                pending.output.fd.sync()
                pending.output.close()
                atomicRename(pending.temp, pending.target)
                makeDexReadOnly(pending.target)
            }
            committed = true
        } finally {
            pendingPaths.remove(pending.target.path, writeId)
            if (!committed) {
                runCatching { pending.output.close() }
                pending.temp.delete()
            }
        }
    }

    override fun abortWrite(writeId: Int) {
        val pending = pendingWrites.remove(writeId) ?: return
        synchronized(pending) {
            runCatching { pending.output.close() }
            pending.temp.delete()
        }
        pendingPaths.remove(pending.target.path, writeId)
    }

    private fun nextPositiveWriteId(): Int {
        repeat(Int.MAX_VALUE) {
            val id = nextWriteId.getAndUpdate { current -> if (current >= Int.MAX_VALUE) 1 else current + 1 }
            if (id > 0 && !pendingWrites.containsKey(id)) return id
        }
        throw IllegalStateException("Không còn write session ID")
    }

    private fun atomicRename(temp: File, target: File) {
        try {
            // POSIX rename trên cùng filesystem thay file đích atomically (nếu đã tồn tại).
            Os.rename(temp.absolutePath, target.absolutePath)
            runCatching {
                val parentFd = Os.open(target.parentFile!!.absolutePath, OsConstants.O_RDONLY, 0)
                try { Os.fsync(parentFd) } finally { Os.close(parentFd) }
            }
        } catch (e: ErrnoException) {
            throw IOException("Không thể commit file ${target.name}: ${e.message}", e)
        }
    }

    private fun makeDexReadOnly(file: File) {
        if (file.name.endsWith(".dex", ignoreCase = true) && !file.setReadOnly()) {
            throw IOException("Không thể đặt file dex thành chỉ đọc")
        }
    }

    override fun delete(path: String): Boolean = io { guard(path).delete() }
    override fun exists(path: String): Boolean = io { guard(path).exists() }

    override fun sha256(path: String): String? = io {
        val file = guard(path).takeIf { it.isFile } ?: return@io null
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    override fun isProcessRunning(pkg: String): Boolean = io {
        require(pkg.matches(Regex("[A-Za-z0-9_.]+")))
        val process = ProcessBuilder("pidof", pkg).start()
        val completed = process.waitFor(5, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            false
        } else process.exitValue() == 0
    }

    override fun shell(argv: Array<String>): Array<String> = io {
        val allowed = argv.contentEquals(
            arrayOf("cmd", "package", "compile", "-f", "-m", "speed-profile", GamePaths.PACKAGE)
        )
        if (!allowed) throw SecurityException("Chỉ cho phép compile speed-profile đúng package Minecraft")
        val process = ProcessBuilder(*argv).start()
        // Đọc stdout/stderr đồng thời để tránh pipe deadlock mà vẫn giữ được thông báo lỗi riêng.
        val stdoutFuture = java.util.concurrent.CompletableFuture.supplyAsync {
            process.inputStream.bufferedReader().use { it.readText() }
        }
        val stderrFuture = java.util.concurrent.CompletableFuture.supplyAsync {
            process.errorStream.bufferedReader().use { it.readText() }
        }
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            stdoutFuture.cancel(true)
            stderrFuture.cancel(true)
            throw IllegalStateException("shell timeout")
        }
        val stdout = stdoutFuture.get(2, TimeUnit.SECONDS)
        val stderr = stderrFuture.get(2, TimeUnit.SECONDS)
        arrayOf(process.exitValue().toString(), stdout, stderr)
    }

    override fun tryLock(name: String, timeoutMs: Long): Boolean = io {
        require(name.matches(Regex("[A-Za-z0-9_.-]{1,100}")))
        val lockFile = File(GamePaths.LOCK_DIR, "$name.lock").also { it.parentFile?.mkdirs() }
        val raf = RandomAccessFile(lockFile, "rw")
        val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(0)
        var acquired: FileLock? = null
        try {
            while (System.currentTimeMillis() <= deadline) {
                acquired = try { raf.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                if (acquired != null) break
                Thread.sleep(50)
            }
            val lock = acquired
            if (lock == null) {
                raf.close()
                false
            } else if (locks.putIfAbsent(name, raf to lock) != null) {
                lock.release()
                raf.close()
                false
            } else true
        } catch (t: Throwable) {
            runCatching { acquired?.release() }
            runCatching { raf.close() }
            throw t
        }
    }

    override fun unlock(name: String) {
        locks.remove(name)?.let { (raf, lock) ->
            runCatching { lock.release() }
            runCatching { raf.close() }
        }
    }

    private companion object { const val MAX_CHUNK_BYTES = 64 * 1024 }
}
