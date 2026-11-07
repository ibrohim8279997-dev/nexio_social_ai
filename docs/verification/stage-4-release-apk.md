# Stage 4: release APK

Run on 2026-11-07 with the existing `keystore.properties` and `nexio-release.jks` (both gitignored,
neither printed). No new key was generated.

| Test | Result | Evidence | Issue | Fix | Retest |
|---|---|---|---|---|---|
| `assembleRelease` | PASSED | `./gradlew :app:assembleRelease --console=plain`: `BUILD SUCCESSFUL in 9m 40s` | None | None | Not needed |
| APK path | PASSED | `app/build/outputs/apk/release/app-release.apk`, 6,920,442 bytes, file SHA-256 `85a26817106c24c88a3ef55f184b608f4e903eeb141b46ebf39bda9d15924475`. The same path `docs/BUILD.md` names | None | None | Not needed |
| Signed, not an unsigned stub | PASSED | `apksigner verify --verbose --print-certs` (build-tools 37.0.0): `Verifies`, `Verified using v2 scheme (APK Signature Scheme v2): true`, `Number of signers: 1`. v1 is not needed at minSdk 33 | None | None | Not needed |
| Signer | PASSED | `certificate DN: CN=NEXIO LAB, O=NEXIO LAB, C=UZ`, RSA 4096. Certificate SHA-256: `2eaea01c48550251f7db6c94c94ef92cb1d8ae75ba08f660530ddcbecc06c1e2` (`2E:AE:A0:1C:48:55:02:51:F7:DB:6C:94:C9:4E:F9:2C:B1:D8:AE:75:BA:08:F6:60:53:0D:DB:CE:CC:06:C1:E2`) | None | None | Not needed |
| Not debuggable | PASSED | `aapt2 dump xmltree --file AndroidManifest.xml` of the APK: no `android:debuggable` attribute (defaults to false) and no `testOnly` | None | None | Not needed |
| Minify and resource shrinking | PASSED | `app/build.gradle.kts` release: `isMinifyEnabled = true`, `isShrinkResources = true`, `proguard-android-optimize.txt` + `proguard-rules.pro`. Build log: `> Task :app:minifyReleaseWithR8` ran | None | None | Not needed |
| Package identity | PASSED | `aapt2 dump badging`: `package: name='com.nexiolab.socialai' versionCode='1' versionName='0.1.0'`, `minSdkVersion:'33'`, `targetSdkVersion:'36'` | None | None | Not needed |
| Install of the signed APK on a real device | NOT RUN | `adb devices` listed no device | - | - | - |
| Google Play Protect | NOT RUN | Needs installation on a real phone with Play Protect and observing its verdict. Not performed. No compatibility is claimed | - | - | - |
| Samsung Knox | NOT RUN | Needs a Samsung device. Not performed. No compatibility is claimed | - | - | - |
