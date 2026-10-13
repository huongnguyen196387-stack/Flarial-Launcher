package com.launcher.core

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.launcher.core.data.performance.PerformanceOptimizer
import com.launcher.core.data.processing.BigDataProcessor
import com.launcher.core.data.shizuku.ShizukuConnectionManager
import com.launcher.core.ui.DiscordAuthGateway
import com.launcher.core.ui.DiscordAuthState
import com.launcher.core.ui.LauncherDashboard
import com.launcher.core.ui.MainViewModel
import com.launcher.core.ui.MainViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: MainViewModel
    private val pickMod = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) copyAndImport(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shizuku = ShizukuConnectionManager(applicationContext)
        val discord = DiscordGatewayPlaceholder()
        viewModel = ViewModelProvider(
            this,
            MainViewModelFactory(shizuku, PerformanceOptimizer(applicationContext), discord)
        )[MainViewModel::class.java]

        setContent {
            MaterialTheme {
                Surface {
                    LauncherDashboard(viewModel = viewModel, onPickMod = {
                        pickMod.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    })
                }
            }
        }
    }

    private fun copyAndImport(uri: Uri) {
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                val dir = File(cacheDir, "selected-mods").apply { mkdirs() }
                val output = File.createTempFile("selected-", ".zip", dir)
                contentResolver.openInputStream(uri)?.use { input -> output.outputStream().use(input::copyTo) }
                    ?: error("Không mở được file đã chọn")
                output
            }
            viewModel.importMod(file)
        }
    }
}

/** Build-safe adapter. Replace with real Discord OAuth2 PKCE/Rich Presence implementation. */
private class DiscordGatewayPlaceholder : DiscordAuthGateway {
    private val mutableState = MutableStateFlow<DiscordAuthState>(DiscordAuthState.SignedOut)
    override val state: StateFlow<DiscordAuthState> = mutableState
    override suspend fun authenticateIfNeeded(): Boolean = false
    override suspend fun updateRichPresence(details: String) = Unit
}
