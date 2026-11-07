# Stage 2: permissions and privacy

Run on 2026-11-07. The permission list below is the **merged** manifest, read from the built
release APK with `aapt2 dump badging app/build/outputs/apk/release/app-release.apk`, so it includes
what libraries add:

```
uses-permission: name='android.permission.INTERNET'
uses-permission: name='android.permission.ACCESS_NETWORK_STATE'
uses-permission: name='android.permission.POST_NOTIFICATIONS'
uses-permission: name='android.permission.ACCESS_WIFI_STATE'
uses-permission: name='android.permission.CHANGE_WIFI_STATE'
uses-permission: name='android.permission.BLUETOOTH_ADVERTISE'
uses-permission: name='android.permission.BLUETOOTH_CONNECT'
uses-permission: name='android.permission.BLUETOOTH_SCAN' usesPermissionFlags='neverForLocation'
uses-permission: name='android.permission.NEARBY_WIFI_DEVICES' usesPermissionFlags='neverForLocation'
uses-permission: name='android.permission.USE_BIOMETRIC'
uses-permission: name='android.permission.WAKE_LOCK'
uses-permission: name='android.permission.RECEIVE_BOOT_COMPLETED'
uses-permission: name='com.nexiolab.socialai.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
```

`app/src/main/AndroidManifest.xml` declares the first nine. `USE_BIOMETRIC` comes from
androidx.biometric. `WAKE_LOCK` and `RECEIVE_BOOT_COMPLETED` come from WorkManager. The last entry is
the app-private permission AndroidX core generates for non-exported dynamic receivers. The source
manifest removes `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`, `FOREGROUND_SERVICE` and
`USE_FINGERPRINT` with `tools:node="remove"`. `minSdkVersion:'33'`, `targetSdkVersion:'36'`.

| Test | Result | Evidence | Issue | Fix | Retest |
|---|---|---|---|---|---|
| No Location permission | PASSED | Merged list above has no `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` or `ACCESS_BACKGROUND_LOCATION`. Bluetooth scan and Nearby Wi-Fi carry `neverForLocation` | None | None | Not needed |
| No SMS, Contacts, call log permission | PASSED | Merged list has no `*SMS*`, `READ_CONTACTS`, `WRITE_CONTACTS`, `READ_CALL_LOG`, `WRITE_CALL_LOG`, `READ_PHONE_STATE` | None | None | Not needed |
| No Accessibility service | PASSED | No `BIND_ACCESSIBILITY_SERVICE` in the merged list; `grep -rn "AccessibilityService"` over sources finds nothing | None | None | Not needed |
| No device admin | PASSED | No `BIND_DEVICE_ADMIN` and no `DeviceAdminReceiver` in sources or merged permissions | None | None | Not needed |
| Cleartext blocked | PASSED | `app/src/main/res/xml/network_security_config.xml`: `<base-config cleartextTrafficPermitted="false">` with system trust anchors only; no `domain-config` exceptions. The APK manifest references it (`networkSecurityConfig=@0x7f120003`) | None | None | Not needed |
| No certificate bypass | PASSED | `grep -rnE "X509TrustManager\|HostnameVerifier\|hostnameVerifier\|checkServerTrusted\|sslSocketFactory\|TrustAll\|ALLOW_ALL"` over `*.kt` outside `build/`: no hits. No `<certificates src="user">` and no `debug-overrides` | None | None | Not needed |
| No WebView added | PASSED | `grep -rnE "WebView\|webkit"` over `*.kt, *.xml, *.kts` outside `build/`: no hits. Instagram login uses Custom Tabs (`androidx.browser`) | None | None | Not needed |
| Android backup off, credentials not backed up | PASSED | APK manifest: `allowBackup=false`; source manifest also sets `fullBackupContent="false"` and `dataExtractionRules`. Credentials staying out of the app's own backup archive is tested on a device by `DeviceDataTest.backupContainsNoCredentialFiles`, which was not run this session (stage 3) | None | None | Not needed |
| QR code carries no credentials | PASSED | `BackupTest.pairingCodeIsTemporaryAndCarriesOnlyTransferFields` and `ChunkedTransferTest.theKeyNeverAppearsInTheManifest` passed in the stage 1 run | None | None | Not needed |
