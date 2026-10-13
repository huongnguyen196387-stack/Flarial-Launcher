package com.launcher.core.data.processing

sealed interface ProcessingState {
    data class Progress(
        val percent: Int,
        val currentFile: String,
        /** SHA-256 hoàn chỉnh được cung cấp sau khi xác minh, không phải digest tạm. */
        val sha256: String? = null,
    ) : ProcessingState

    data class Success(
        val category: String,
        val destinationPath: String,
        val sha256: String,
    ) : ProcessingState

    data class Error(val exception: Throwable) : ProcessingState
}
