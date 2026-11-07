# Stage 5: real device

On 2026-11-07, `C:\Android\Sdk\platform-tools\adb.exe devices` printed `List of devices attached`
followed by an empty list, both before and after the release build. No phone or emulator was
connected, so the release APK was not installed and nothing below was observed.

| Test | Result | Evidence | Issue | Fix | Retest |
|---|---|---|---|---|---|
| Install release APK | NOT RUN | No device in `adb devices` | - | - | - |
| Open the app and the main screens | NOT RUN | No device | - | - | - |
| Calendar: build, reorder and approve a carousel | NOT RUN | No device. Covered only by JVM tests (stage 3) | - | - | - |
| Data & Migration: retry and cancel an unfinished transfer | NOT RUN | No device; a real resume needs two phones | - | - | - |
| Battery use | NOT RUN | Not performed | - | - | - |
| Storage use | NOT RUN | Not performed | - | - | - |
| Network drop during publish or transfer | NOT RUN | Not performed | - | - | - |
| Instagram publish (single post and carousel) | NOT RUN | Needs the owner's Instagram professional account, Meta app and media hosting. Not performed | - | - | - |
| AI calls with the owner's keys | NOT RUN | Needs the owner's API keys. Not performed | - | - | - |
