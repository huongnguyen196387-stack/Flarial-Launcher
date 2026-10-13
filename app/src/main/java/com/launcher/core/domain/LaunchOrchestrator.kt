package com.launcher.core.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

sealed interface LaunchPhase {
    data object Preflight : LaunchPhase
    data object AcquiringLock : LaunchPhase
    data object StagingPayload : LaunchPhase
    data object ApplyingProfile : LaunchPhase
    data object Launching : LaunchPhase
    data object Done : LaunchPhase
    data class Failed(val reason: LaunchFailure) : LaunchPhase
}

sealed interface LaunchFailure {
    data class ShizukuUnavailable(val state: ShizukuState) : LaunchFailure
    data object GameAlreadyRunning : LaunchFailure
    data object LockTimeout : LaunchFailure
    data class PayloadCorrupt(val file: String) : LaunchFailure
    data class ProfileApplyFailed(val stderr: String) : LaunchFailure
    data object GameNotInstalled : LaunchFailure
    data class Unexpected(val cause: Throwable) : LaunchFailure
}

/**
 * Use-case "khởi chạy game" = vòng đời của Launcher trước khi giao quyền cho Minecraft gốc.
 *
 *   Preflight -> AcquiringLock -> StagingPayload -> ApplyingProfile -> (nhả lock) -> Launching -> Done
 *
 * Nguyên tắc:
 *  - IDEMPOTENT: payload đã đúng hash thì bỏ qua -> chạy lại bao nhiêu lần cũng an toàn.
 *  - VERIFY-BEFORE-USE: ghi xong phải đọc lại hash; sai là dừng, KHÔNG khởi động game với payload hỏng.
 *  - Khoá được nhả TRƯỚC khi start game để Launcher không giữ lock lên dữ liệu game đang chạy.
 *
 * Về classes.dex: Launcher chỉ STAGE + XÁC MINH file (read-only, đúng hash). Việc nạp dex vào process game
 * là trách nhiệm của loader nằm trong APK game đã patch/re-sign (đọc từ PAYLOAD_DIR). Launcher không (và
 * không thể, nếu không root) chèn mã vào process của app khác.
 *
 * Về baseline.prof: shell uid không ghi được vào kho profile của ART (/data/misc/profiles). Cách chuẩn là
 * baseline.prof nằm trong assets/dexopt/ của APK; ở bước này ta chỉ ép dexopt để ART dùng profile đó.
 */
class LaunchOrchestrator(
    private val shizukuState: () -> ShizukuState,
    private val payloadSource: PayloadSource,
    private val starter: GameStarter,
    private val strictProfile: Boolean = false,   // false: lỗi profile chỉ làm giảm hiệu năng, vẫn cho chạy game
) {
    fun launch(): Flow<LaunchPhase> = flow {
        emit(LaunchPhase.Preflight)
        val fs = (shizukuState() as? ShizukuState.Ready)?.fs
        if (fs == null) {
            emit(LaunchPhase.Failed(LaunchFailure.ShizukuUnavailable(shizukuState())))
            return@flow
        }
        try {
            if (fs.isProcessRunning(GamePaths.PACKAGE)) {
                emit(LaunchPhase.Failed(LaunchFailure.GameAlreadyRunning)); return@flow
            }

            emit(LaunchPhase.AcquiringLock)
            if (!fs.tryLock(LOCK, 5_000)) {
                emit(LaunchPhase.Failed(LaunchFailure.LockTimeout)); return@flow
            }

            try {
                emit(LaunchPhase.StagingPayload)
                for (f in payloadSource.load()) {
                    require(f.name.matches(Regex("[A-Za-z0-9_.-]{1,128}")) && f.name != "." && f.name != "..") {
                        "Tên payload không hợp lệ: ${f.name}"
                    }
                    val dst = "${GamePaths.PAYLOAD_DIR}/${f.name}"
                    if (fs.sha256(dst) == f.sha256) continue                  // đã đúng -> bỏ qua
                    writePayloadInChunks(fs, dst, f.bytes)
                    if (fs.sha256(dst) != f.sha256) {                          // verify sau khi ghi
                        emit(LaunchPhase.Failed(LaunchFailure.PayloadCorrupt(f.name))); return@flow
                    }
                }

                emit(LaunchPhase.ApplyingProfile)
                val r = fs.shell("cmd", "package", "compile", "-f", "-m", "speed-profile", GamePaths.PACKAGE)
                if (r.exitCode != 0 && strictProfile) {
                    emit(LaunchPhase.Failed(LaunchFailure.ProfileApplyFailed(r.stderr))); return@flow
                }
            } finally {
                fs.unlock(LOCK)
            }

            emit(LaunchPhase.Launching)
            if (!starter.start()) {
                emit(LaunchPhase.Failed(LaunchFailure.GameNotInstalled)); return@flow
            }
            emit(LaunchPhase.Done)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            emit(LaunchPhase.Failed(LaunchFailure.Unexpected(t)))
        }
    }

    private suspend fun writePayloadInChunks(fs: PrivilegedFs, path: String, bytes: ByteArray) {
        val session = fs.openAtomicWrite(path)
        var committed = false
        try {
            var offset = 0
            while (offset < bytes.size) {
                val end = minOf(offset + PAYLOAD_CHUNK_BYTES, bytes.size)
                val chunk = bytes.copyOfRange(offset, end)
                session.write(chunk, chunk.size)
                offset = end
            }
            session.commit()
            committed = true
        } finally {
            if (!committed) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    runCatching { session.abort() }
                }
            }
        }
    }

    private companion object {
        const val LOCK = "launch"
        const val PAYLOAD_CHUNK_BYTES = 48 * 1024
    }
}
