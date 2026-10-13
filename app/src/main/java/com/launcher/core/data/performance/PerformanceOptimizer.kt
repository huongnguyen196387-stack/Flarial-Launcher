package com.launcher.core.data.performance

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Thao tác an toàn trước khi mở game; không đụng cache chung hoặc tiến trình Minecraft. */
data class OptimizationReport(
    val deletedTemporaryFiles: Int,
    val availableMemoryBytes: Long?,
    val lowMemory: Boolean?,
)

class PerformanceOptimizer(private val context: Context) {
    /**
     * Chỉ dọn thư mục tạm do launcher quản lý. Không xóa toàn bộ cacheDir/externalCacheDir vì file mod
     * đầu vào có thể nằm ở đó; không gọi setThreadPriority vì chỉ ảnh hưởng thread launcher, không phải game.
     */
    suspend fun executePreLaunchOptimization(preserve: Set<File> = emptySet()): OptimizationReport =
        withContext(Dispatchers.IO) {
            val preservePaths = preserve.mapNotNull { runCatching { it.canonicalPath }.getOrNull() }.toSet()
            val ownedTemp = File(context.cacheDir, TEMP_DIRECTORY)
            val deleted = if (ownedTemp.exists()) deleteOwnedContents(ownedTemp, preservePaths) else 0

            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memory = manager?.let {
                ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
            }
            OptimizationReport(
                deletedTemporaryFiles = deleted,
                availableMemoryBytes = memory?.availMem,
                lowMemory = memory?.lowMemory,
            )
        }

    private fun deleteOwnedContents(directory: File, preservePaths: Set<String>): Int {
        var deleted = 0
        val children = directory.listFiles() ?: return 0
        for (child in children) {
            val canonical = runCatching { child.canonicalPath }.getOrNull()
            if (canonical != null && (canonical in preservePaths || preservePaths.any { it.startsWith("$canonical/") })) {
                if (child.isDirectory) deleted += deleteOwnedContents(child, preservePaths)
                continue
            }
            if (child.isDirectory) deleted += deleteOwnedContents(child, preservePaths)
            if (child.delete()) deleted++
        }
        return deleted
    }

    private companion object { const val TEMP_DIRECTORY = "launcher-generated-temp" }
}
