# AI Reviewer notes

## Gaps found in the supplied sources
1. Gemini's `targetDir.mkdirs()` and `newFile.outputStream()` run as the launcher app UID. On Android 11+ the app generally cannot directly write into another app's `Android/data` tree; the operation must go through the authorized Shizuku UserService.
2. Passing a full archive/entry as AIDL `byte[]` is not a streaming protocol and can exceed Binder transaction limits. The new interface uses a small write session protocol and 48 KiB chunks.
3. The original extraction routine did not reject ZIP path traversal and emitted no real progress because its callback was only commented out.
4. `LauncherDashboard` cannot directly consume the state from the other module without a bridge. `MainViewModel` combines Shizuku, processing and Discord status into a UI `StateFlow`.
5. The source also had an invalid `android.content.IBinder` import and a stale-`Ready` reconnection path; both were corrected.
6. Blanket deletion of `cacheDir`/`externalCacheDir` can delete the selected archive if it was staged there. The merged optimizer only cleans a dedicated launcher-owned temp directory.

## Verification performed
- Kotlin 1.9 compilation succeeded for `Domain.kt`, `LaunchOrchestrator.kt`, `ProcessingState.kt` and `BigDataProcessor.kt`.
- Smoke test with a fake `PrivilegedFs` passed: a sample pack streamed into a dedicated pack folder and a ZIP Slip entry (`../../escape.txt`) was rejected.
- Shizuku service/connection and ViewModel sources passed Kotlin compilation against lightweight local stubs. These are source-level checks, not a real Android build.

## Not verified
The uploaded archive has no Gradle wrapper/build files, no Android SDK configuration/Manifest, no generated AIDL bindings and no real Discord OAuth/Rich Presence implementation. Therefore a full APK build, Shizuku permission test on device and real Discord OAuth flow cannot be claimed as complete.
