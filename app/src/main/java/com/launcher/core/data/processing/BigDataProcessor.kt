package com.launcher.core.data.processing

import com.launcher.core.domain.GamePaths
import com.launcher.core.domain.PrivilegedFs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.math.roundToInt

/**
 * Đọc archive cục bộ theo buffer; mọi file đích được stream qua Shizuku AIDL theo chunk và commit nguyên tử.
 * Không bao giờ tạo ByteArray chứa toàn bộ archive hoặc entry.
 */
class BigDataProcessor(private val fs: PrivilegedFs) {
    fun processPackage(file: File, expectedSha256: String? = null): Flow<ProcessingState> = flow {
        var locked = false
        var currentWrite: com.launcher.core.domain.AtomicWriteSession? = null
        try {
            require(file.isFile && file.canRead()) { "Không đọc được file mod: ${file.absolutePath}" }
            if (!file.extension.lowercase().matches(Regex("mcpack|mcaddon|zip"))) {
                throw IllegalArgumentException("Chỉ hỗ trợ .mcpack, .mcaddon hoặc .zip")
            }

            emit(ProcessingState.Progress(0, "Đang xác minh SHA-256…"))
            val actualHash = calculateSha256(file) { fraction ->
                emit(ProcessingState.Progress((fraction * 20f).roundToInt().coerceIn(0, 20), "Đang xác minh SHA-256…"))
            }
            if (!expectedSha256.isNullOrBlank()) {
                if (!expectedSha256.matches(Regex("(?i)[0-9a-f]{64}"))) {
                    throw IllegalArgumentException("SHA-256 kỳ vọng phải có đúng 64 ký tự hex")
                }
                if (!actualHash.equals(expectedSha256.trim(), ignoreCase = true)) {
                    throw SecurityException("SHA-256 không khớp. Expected=$expectedSha256, actual=$actualHash")
                }
            }
            emit(ProcessingState.Progress(20, "SHA-256 đã xác minh", actualHash))

            val category = classifyPackage(file)
            emit(ProcessingState.Progress(24, "Đang kiểm tra cấu trúc archive…", actualHash))
            val archiveInfo = inspectArchive(file)
            emit(ProcessingState.Progress(30, "Đang chuẩn bị giải nén…", actualHash))

            if (fs.isProcessRunning(GamePaths.PACKAGE)) {
                throw IllegalStateException("Hãy đóng Minecraft trước khi cài pack để tránh ghi đè dữ liệu đang dùng")
            }
            if (!fs.tryLock(IMPORT_LOCK, 5_000)) {
                throw IllegalStateException("Không lấy được khóa cài mod; một thao tác khác có thể đang chạy")
            }
            locked = true
            // Kiểm tra lần hai sau khi lấy khóa.
            if (fs.isProcessRunning(GamePaths.PACKAGE)) {
                throw IllegalStateException("Minecraft đã khởi chạy trong lúc chuẩn bị cài mod")
            }

            // Mỗi pack có thư mục riêng để manifest.json của pack này không ghi đè pack khác.
            val directoryName = safePackDirectoryName(file.nameWithoutExtension, actualHash)
            val targetRoot = "${GamePaths.GAME_DATA}/$category/$directoryName"
            val totalBytes = archiveInfo.totalUncompressedBytes.coerceAtLeast(1L)
            var copiedBytes = 0L
            var completedEntries = 0
            var lastReportedPercent = 29

            ZipInputStream(BufferedInputStream(FileInputStream(file), IO_BUFFER_BYTES)).use { zip ->
                val buffer = ByteArray(IO_BUFFER_BYTES)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val normalizedName = safeRelativeEntry(entry.name)
                    if (normalizedName !in archiveInfo.sourceEntries) {
                        throw SecurityException("Entry không khớp central directory: ${entry.name}")
                    }
                    val relative = archiveInfo.rootPrefix?.let { prefix ->
                        if (normalizedName == prefix.removeSuffix("/")) "" else normalizedName.removePrefix(prefix)
                    } ?: normalizedName
                    if (relative.isEmpty() && entry.isDirectory) {
                        completedEntries++
                        zip.closeEntry()
                        continue
                    }
                    if (relative.isEmpty()) throw SecurityException("Entry file rỗng sau chuẩn hóa")
                    if (entry.isDirectory) {
                        completedEntries++
                        zip.closeEntry()
                        continue
                    }

                    val destination = "$targetRoot/$relative"
                    currentWrite = fs.openAtomicWrite(destination)
                    var committed = false
                    try {
                        var count: Int
                        while (zip.read(buffer).also { count = it } != -1) {
                            if (count == 0) continue
                            copiedBytes += count.toLong()
                            if (copiedBytes > MAX_UNCOMPRESSED_BYTES) {
                                throw SecurityException("Archive giải nén vượt giới hạn an toàn")
                            }
                            currentWrite!!.write(buffer, count)
                            val byteFraction = copiedBytes.toDouble() / totalBytes.toDouble()
                            val entryFraction = (completedEntries + 0.5).toDouble() / archiveInfo.entryCount.coerceAtLeast(1)
                            val fraction = if (archiveInfo.totalUncompressedBytes > 0) byteFraction else entryFraction
                            val percent = (30 + (fraction.coerceIn(0.0, 1.0) * 69.0)).roundToInt().coerceIn(30, 99)
                            if (percent >= lastReportedPercent + 1) {
                                lastReportedPercent = percent
                                emit(ProcessingState.Progress(percent, entry.name, actualHash))
                            }
                        }
                        currentWrite!!.commit()
                        committed = true
                    } finally {
                        if (!committed) {
                            withContext(NonCancellable) { runCatching { currentWrite?.abort() } }
                        }
                        currentWrite = null
                    }
                    completedEntries++
                    zip.closeEntry()
                    val fraction = if (archiveInfo.totalUncompressedBytes > 0) {
                        copiedBytes.toDouble() / totalBytes.toDouble()
                    } else {
                        completedEntries.toDouble() / archiveInfo.entryCount.coerceAtLeast(1)
                    }
                    val percent = (30 + (fraction.coerceIn(0.0, 1.0) * 69.0)).roundToInt().coerceIn(30, 99)
                    if (percent > lastReportedPercent) {
                        lastReportedPercent = percent
                        emit(ProcessingState.Progress(percent, entry.name, actualHash))
                    }
                }
            }

            emit(ProcessingState.Progress(100, "Hoàn tất", actualHash))
            emit(ProcessingState.Success(category, targetRoot, actualHash))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            emit(ProcessingState.Error(t))
        } finally {
            currentWrite?.let { session -> withContext(NonCancellable) { runCatching { session.abort() } } }
            if (locked) withContext(NonCancellable) { runCatching { fs.unlock(IMPORT_LOCK) } }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun calculateSha256(file: File, onProgress: suspend (Float) -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(IO_BUFFER_BYTES)
        val total = file.length().coerceAtLeast(1L)
        var readBytes = 0L
        var lastReported = -1f
        FileInputStream(file).buffered(IO_BUFFER_BYTES).use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                readBytes += count
                val fraction = (readBytes.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
                if (fraction >= 1f || fraction - lastReported >= HASH_PROGRESS_STEP) {
                    lastReported = fraction
                    onProgress(fraction)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private data class ArchiveInfo(
        val sourceEntries: Set<String>,
        val rootPrefix: String?,
        val entryCount: Int,
        val totalUncompressedBytes: Long,
    )

    private fun inspectArchive(file: File): ArchiveInfo = ZipFile(file).use { archive ->
        val entries = Collections.list(archive.entries())
        if (entries.isEmpty()) throw IllegalArgumentException("Archive không có entry")
        if (entries.size > MAX_ENTRIES) throw SecurityException("Archive có quá nhiều entry")

        val safeNames = LinkedHashSet<String>()
        var totalBytes = 0L
        for (entry in entries) {
            val name = safeRelativeEntry(entry.name)
            if (!safeNames.add(name)) throw SecurityException("Entry trùng đường dẫn: $name")
            if (entry.size < 0L) throw SecurityException("Kích thước entry không hợp lệ: $name")
            if (entry.size > MAX_ENTRY_BYTES) throw SecurityException("Entry quá lớn: $name")
            if (totalBytes > MAX_UNCOMPRESSED_BYTES - entry.size) {
                throw SecurityException("Archive vượt giới hạn tổng dung lượng giải nén")
            }
            totalBytes += entry.size
        }

        // Một số pack ZIP có thêm wrapper directory duy nhất: bỏ wrapper để manifest.json nằm ở pack root.
        val regularFiles = entries.filterNot { it.isDirectory }.map { safeRelativeEntry(it.name) }
        val hasRootFile = regularFiles.any { '/' !in it }
        val topLevelNames = regularFiles.map { it.substringBefore('/') }.distinct()
        val rootPrefix = if (!hasRootFile && regularFiles.isNotEmpty() && topLevelNames.size == 1 &&
            regularFiles.all { '/' in it }) "${topLevelNames.single()}/" else null
        ArchiveInfo(safeNames, rootPrefix, entries.size, totalBytes)
    }

    private fun safePackDirectoryName(original: String, sha256: String): String {
        val safeBase = original.replace(Regex("[^A-Za-z0-9_.-]"), "_")
            .trim('.', '_').take(60).ifBlank { "pack" }
        return "${safeBase}_${sha256.take(12)}"
    }

    /** Chống Zip Slip, đường dẫn tuyệt đối, ký tự NUL và đường dẫn kiểu Windows. */
    private fun safeRelativeEntry(rawName: String): String {
        if (rawName.isBlank() || rawName.contains('\u0000')) throw SecurityException("Tên entry không hợp lệ")
        val normalized = rawName.replace('\\', '/')
        if (normalized.startsWith('/') || normalized.matches(Regex("^[A-Za-z]:.*"))) {
            throw SecurityException("Entry dùng đường dẫn tuyệt đối: $rawName")
        }
        val segments = normalized.split('/')
        if (segments.any { it == ".." }) throw SecurityException("Entry thoát khỏi thư mục đích: $rawName")
        val safe = segments.filter { it.isNotEmpty() && it != "." }.joinToString("/")
        if (safe.isBlank()) throw SecurityException("Tên entry rỗng sau chuẩn hóa")
        return safe
    }

    private fun classifyPackage(file: File): String = when (file.extension.lowercase()) {
        "mcpack" -> "resource_packs"
        "mcaddon" -> "behavior_packs"
        "zip" -> "resource_packs"
        else -> throw IllegalArgumentException("Định dạng không hỗ trợ")
    }

    private companion object {
        const val IMPORT_LOCK = "mod-import"
        const val IO_BUFFER_BYTES = 64 * 1024
        const val MAX_ENTRIES = 10_000
        const val MAX_ENTRY_BYTES = 512L * 1024 * 1024
        const val MAX_UNCOMPRESSED_BYTES = 1024L * 1024 * 1024
        const val HASH_PROGRESS_STEP = 0.02f
    }
}
