# Flarial Launcher — GitHub Actions build scaffold

## Build
Push this project to GitHub. The workflow `.github/workflows/build-apk.yml` runs on pushes to `main`, pull requests, and manual dispatch. Download `Flarial-Launcher-Debug-APK` from the completed workflow's Artifacts section.

## Important scope notes
- This is a build scaffold around the source archive, not a verified production app.
- `DiscordGatewayPlaceholder` deliberately reports signed-out. Replace it with the real Discord OAuth2 PKCE/Rich Presence implementation; that source and credentials were not included in the original archive.
- Run a real-device test with Shizuku before relying on filesystem operations. Shizuku API behavior and Android/OEM access policies vary.
- A debug APK is not a release-signed APK.
