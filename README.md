# Launcher Core — merged Shizuku + streaming import + UI bridge

## What changed
- `IPrivilegedFileService.aidl`: added `beginWrite/writeChunk/commitWrite/abortWrite`. A large archive is never passed as one `byte[]` Binder transaction.
- `PrivilegedFileService`: guards canonical paths under `GamePaths.GAME_DATA` or `PAYLOAD_DIR`, writes to a temp file, `fsync`s, then `rename`s the completed file atomically. Active sessions are cleaned up on abort/service destruction.
- `PrivilegedFs`: gained the transport-neutral `AtomicWriteSession` API. `ShizukuPrivilegedFs` implements it using 48 KiB transactions.
- `BigDataProcessor`: streams SHA-256 and ZIP content with bounded buffers, verifies optional expected hash, validates ZIP entries before writing (Zip Slip, duplicates, limits), checks Minecraft is closed, takes an inter-process lock, then writes each file through Shizuku IPC.
- `MainViewModel` + `LauncherDashboard`: combines Shizuku state, Discord state, busy state and `ProcessingState` into the UI progress model.
- `PerformanceOptimizer`: cleans only a dedicated `cacheDir/launcher-generated-temp` folder. It deliberately does not wipe every cache directory or change the launcher thread priority and pretend that changes Minecraft's priority.

## Integrating into the actual Android app
The uploaded archive contains source files only; it has no `settings.gradle`, app `build.gradle`, Manifest, Discord OAuth implementation or previous Compose dashboard, so a full Gradle build could not be run here. Merge these sources into the existing app module and keep the dependency versions consistent with that project.

Required capabilities/dependencies:
- Shizuku API + provider, plus the existing Manifest entries for `ShizukuProvider` and `<queries>`.
- `android { buildFeatures { aidl = true; compose = true } }` if Compose is enabled in this app.
- AndroidX lifecycle ViewModel, Compose Material 3 and kotlinx-coroutines Android dependencies.

### Discord adapter
Implement `DiscordAuthGateway` around the project's existing Discord OAuth2 PKCE / Rich Presence code and inject it into `MainViewModel`. The actual Discord OAuth source was not included in the provided ZIP, so this archive supplies the typed integration contract, not fabricated OAuth credentials or a fake successful login.

### UI/file picker wiring
Pass `LauncherDashboard(viewModel, onPickMod = ...)` from the Activity. The Activity's document picker should copy the selected `content://` URI into an app-owned cache file before calling `viewModel.importMod(file, expectedSha256)`; the processor accepts a readable local `File`.

### Important limitations
- The current package classifier retains the supplied heuristic (`.mcpack` -> `resource_packs`, `.mcaddon` -> `behavior_packs`, `.zip` -> `resource_packs`). Real `.mcaddon` files can contain nested `.mcpack` archives and may require a dedicated recursive importer/classification by `manifest.json`; that behavior is not inferred from an extension alone.
- Each output file is atomically replaced, but the whole archive is not one directory-level transaction. A failure after several files were committed may leave those already completed files in place.
- Shizuku shell access to `Android/data` varies by Android/OEM policy. Runtime permission, service binding, and a real device test remain required.
- This code does not inject code into Minecraft or modify another app's process. It performs filesystem operations through the permitted Shizuku service only.
