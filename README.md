# NEXIO SOCIAL AI

Native Android marketing assistant by NEXIO LAB. *Future starts here.*

It interviews a business owner, then runs a Main Agent and six specialist agents to plan content,
write posts, make videos, publish to Instagram through Meta's official API, and track AI spending.
Data stays on the phone; only AI requests and Instagram calls go over the network.

| Item | Value |
| --- | --- |
| Language and UI | Kotlin, Jetpack Compose |
| Android | minSdk 33 (Android 13), targetSdk 36 |
| Storage | Room / SQLite, private files, Android Keystore |
| Background work | WorkManager |
| Video | Media3 Transformer |
| Nearby transfer | Google Nearby Connections |

## Documents

- [Build instructions](docs/BUILD.md)
- [Configuration](docs/CONFIGURATION.md)
- [Instagram setup](docs/INSTAGRAM.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Verification reports](docs/verification/README.md)

## Modules

- `domain`: pure Kotlin. Agents, cost rules, publishing state machine, backup format.
- `data`: Android implementations. Room, Keystore, provider adapters, Instagram, renderer, Nearby.
- `app`: Compose UI, navigation, workers, dependency wiring.
