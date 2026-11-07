# Build instructions

## Requirements

- JDK 17
- Android SDK with platform 37 and build tools 36 or newer
- Android Studio (current stable), or only the command line tools
- 8 GB of RAM is workable; close the emulator during full builds

Create `local.properties` in the project root if Android Studio has not:

```properties
sdk.dir=C:/Android/Sdk
```

## Debug build

```powershell
.\gradlew :app:assembleDebug
```

The APK is at `app/build/outputs/apk/debug/app-debug.apk`.

## Tests

```powershell
.\gradlew :domain:test :data:testDebugUnitTest      # JVM tests, no device needed
.\gradlew :data:connectedDebugAndroidTest           # needs a device or emulator
```

## Dependency vulnerability check

The check scans the resolved release dependency graph of `:app` (which includes `:data` and
`:domain`) with [OSV-Scanner](https://github.com/google/osv-scanner) v2. It fails on a **critical**
advisory in a dependency our own modules declare directly, and lists every high and critical
advisory, direct or transitive, in `build/osv/report.md`.

One-time setup: download `osv-scanner_windows_amd64.exe` (or your platform's binary) from the
OSV-Scanner GitHub releases, check it against the release's `osv-scanner_SHA256SUMS`, and point
`OSV_SCANNER` at it (or put it on `PATH` as `osv-scanner`).

```powershell
$env:OSV_SCANNER = "C:\tools\osv-scanner.exe"
.\gradlew -I tools\osv\resolved-deps.init.gradle.kts :app:osvLockfile   # writes build\osv\gradle.lockfile
python tools\osv\scan.py --download-db   # first run: fetches the OSV database for Maven
python tools\osv\scan.py                 # later runs: fully offline against that local copy
```

Exit code 0 means no critical advisory in a direct dependency; 1 means at least one, listed in the
output. The OSV database is cached in the user's cache directory by OSV-Scanner, not in the
repository. Re-run with `--download-db` to refresh it before a release.

The Gradle part is an init script, so it does not change the normal build. A suppression goes in
`tools/osv/suppressions.txt` as `ADVISORY-ID | reason | https://link-to-advisory`; the script
refuses a line without a reason and a link.

## Release build

Release signing reads `keystore.properties` in the project root. That file and all keystores are in
`.gitignore`. Never commit them.

Create a key once and keep it safe. **Losing it means updates can no longer be installed over the
existing app.**

```powershell
keytool -genkeypair -v -keystore nexio-release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias nexio
```

Then create `keystore.properties`:

```properties
storeFile=nexio-release.jks
storePassword=YOUR_PASSWORD
keyAlias=nexio
keyPassword=YOUR_PASSWORD
```

Build it:

```powershell
.\gradlew :app:assembleRelease
```

The APK is at `app/build/outputs/apk/release/app-release.apk`. Without `keystore.properties` the
build still runs, but produces an unsigned APK that cannot be installed.

## Installing on a phone

```powershell
adb install app\build\outputs\apk\release\app-release.apk
```

A sideloaded APK from a new signing key may trigger a Play Protect or Samsung Knox reputation
warning. That is expected for any new app and is not a defect. Do not try to bypass it.

## Regenerating the launcher icon

```powershell
pip install pillow numpy scipy
python tools\generate_icons.py
```
