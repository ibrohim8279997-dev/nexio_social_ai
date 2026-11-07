# Verification reports

- **Date:** 2026-11-07
- **Git revision:** none. The repository has no commit yet (`git rev-parse HEAD`: "unknown revision"),
  and every file is untracked. These reports describe the working tree on that date. The release
  APK they describe has file SHA-256
  `85a26817106c24c88a3ef55f184b608f4e903eeb141b46ebf39bda9d15924475`.
- **Rule:** PASSED needs a command, file or device observation from that session. NOT RUN means the
  check was not performed, and it does not count as passed.

| Stage | Report | Status | Checks |
|---|---|---|---|
| 1. Source and dependencies | [stage-1-source-and-dependencies.md](stage-1-source-and-dependencies.md) | **PASSED** | 11 PASSED, 0 FAILED, 0 NOT RUN |
| 2. Permissions and privacy | [stage-2-permissions-and-privacy.md](stage-2-permissions-and-privacy.md) | **PASSED** | 9 PASSED, 0 FAILED, 0 NOT RUN |
| 3. API, database, carousel, backup, resume | [stage-3-api-database-carousel-backup-resume.md](stage-3-api-database-carousel-backup-resume.md) | **Partly run, not passed as a stage** | 16 PASSED, 0 FAILED, 4 NOT RUN (Keystore token storage, credential-free backup and restore on a device, two-phone transfer) |
| 4. Release APK | [stage-4-release-apk.md](stage-4-release-apk.md) | **Partly run, not passed as a stage** | 7 PASSED, 0 FAILED, 3 NOT RUN (device install, Play Protect, Samsung Knox) |
| 5. Real device | [stage-5-real-device.md](stage-5-real-device.md) | **NOT RUN** | 0 PASSED, 0 FAILED, 9 NOT RUN (no device attached) |

Stages passed: 1 and 2. Stages 3 and 4 passed every check that was run, but each has NOT RUN checks
that need a device. Stage 5 was not run.

## Still needed from the owner

- Connect an Android 13+ phone (`adb devices` must list it) and run
  `.\gradlew :data:connectedDebugAndroidTest` for the Keystore and backup device tests.
- Install `app/build/outputs/apk/release/app-release.apk` and record what Play Protect and, on a
  Samsung phone, Knox actually show.
- Publish one single post and one 2–10 image carousel with the owner's own Instagram account and
  hosting.
- Run a phone-to-phone transfer, break the link midway, and retry from Data & Migration.
