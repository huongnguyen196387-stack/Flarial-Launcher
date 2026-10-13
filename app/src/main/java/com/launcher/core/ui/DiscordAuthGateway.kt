package com.launcher.core.ui

import kotlinx.coroutines.flow.StateFlow

/** Adapter contract cho module Discord PKCE hiện có. Concrete OAuth implementation nằm ngoài launcher-core.zip. */
sealed interface DiscordAuthState {
    data object SignedOut : DiscordAuthState
    data object Authenticating : DiscordAuthState
    data class Authenticated(val displayName: String? = null) : DiscordAuthState
    data class Failed(val message: String) : DiscordAuthState
}

interface DiscordAuthGateway {
    val state: StateFlow<DiscordAuthState>

    /** Trả true khi đã có session; triển khai có thể mở OAuth PKCE và chờ callback. */
    suspend fun authenticateIfNeeded(): Boolean
    suspend fun updateRichPresence(details: String)
}
