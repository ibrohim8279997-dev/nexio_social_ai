# Architecture

## Layers

```
app  (Compose screens, ViewModels, WorkManager workers, AppContainer)
 └─ data  (Room, Keystore, OkHttp adapters, Media3, Nearby)
     └─ domain  (pure Kotlin: rules, interfaces, models)
```

`domain` has no Android dependency, so its rules run as plain JVM tests. The Gradle module boundary
enforces the direction of the arrows.

## Decisions

**minSdk 33.** Nearby Connections needs the Location permission on Android 12 and below, and the
product forbids Location. On Android 13+ it uses `NEARBY_WIFI_DEVICES` and Bluetooth permissions
declared `neverForLocation`. Supporting older phones means adding Location back.

**Media3 Transformer instead of FFmpeg.** ffmpeg-kit is retired and unmaintained, and FFmpeg builds
carry LGPL or GPL distribution duties. Media3 is Google-maintained under Apache 2.0.

**Records double as Room entities.** The records in `domain/model` carry Room annotations from the
annotation-only `room-common` artifact. This keeps 24 hand-written mapper pairs out of a single-user
app. The cost is that `domain` knows table names. If a backend arrives, split them then.

**Hand-written dependency graph.** `AppContainer` is one short file; Hilt would add annotation
processing to a build that is already slow on modest machines.

**OkHttp directly for providers.** Adapters translate between very different JSON APIs, so Retrofit
interfaces would add little. `kotlinx.serialization` handles the JSON.

**No server.** Provider keys are the owner's own, stored on the phone. This suits one owner and
does not suit a commercial multi-user product: there, keys and subscription checks must move to a
trusted backend. `AiClient`, `AiClientFactory`, `CredentialStore` and the repository interfaces are
the seams for that move.

## AI requests

Every request goes through `AiGateway`:

1. Picks the route: the agent's model, its fallback, then each enabled provider's first selected model.
2. Stops if the monthly or provider spending limit is reached.
3. Retries rate limits, server errors and network errors up to three times.
4. Moves to the next route on errors a fallback can help with.
5. Writes an `ExpenseRecord` for every attempt, with the prices in force at that moment.

Costs have three levels. `MEASURED` is provider-reported usage times the configured price.
`ESTIMATED` means usage or duration was inferred and is labelled so in the UI. `UNPRICED` means no
price is configured; the request is counted but never treated as free. Video length is ESTIMATED
because providers do not return a billed duration through these APIs.

Discovered models are not used until the owner selects them, so an arbitrary model never runs by
accident.

## Agents

The Main Agent turns an instruction into a JSON plan of specialist steps. Each specialist's work
goes to the Quality Control Agent, which can reject it up to two times with notes. Work still
rejected is parked as `WAITING_APPROVAL` for the owner. An unreadable verdict is never a pass. A plan
cannot call the Main Agent or skip Quality Control.

Brand facts reach the agents as delimited data rather than instructions. Agents have no code path
that writes to the Brand Profile.

## Publishing

`ContentWorkflow` allows `SCHEDULED` only after `APPROVED`, and any change to text or media after
approval withdraws the approval.

`InstagramPublisher` stores each state before the next step:

```
PENDING → MEDIA_UPLOADED → CONTAINER_CREATED → READY_TO_PUBLISH → PUBLISH_UNCERTAIN → PUBLISHED
                                                                        └→ FAILED
```

`PUBLISH_UNCERTAIN` is written *before* the final call. If the app dies or the connection drops
there, the record shows it, and the only thing that releases it is the container's status from
Instagram:

| Container status | Meaning | Action |
| --- | --- | --- |
| `PUBLISHED` | The post went out | Mark published, no second call |
| `FINISHED` | The container is unused | Safe to publish |
| `ERROR`, `EXPIRED` | Nothing was posted | Mark failed |
| anything else | Unknown | Stay uncertain and ask the owner |

A unique `idempotencyKey` per approval stops a second row for the same intent. Each scheduled post
gets its own one-time timer, so nothing polls in the background.

## Backup and transfer

Both use one pipeline:

```
VACUUM INTO snapshot → manifest with SHA-256 → ZIP → AES-256-GCM chunks
```

Chunks follow the STREAM construction: each nonce has a counter and a last-chunk flag, and the
header is authenticated with every chunk, so editing, reordering or truncating fails. Backups use
PBKDF2-HMAC-SHA256 with 600,000 iterations. Transfers use a random 256-bit key from the QR code.

Restoring:

1. Decrypts into `restore-staging`, enforcing the manifest: safe paths, declared sizes, SHA-256.
2. Runs `PRAGMA integrity_check` and compares schema versions.
3. Shows a summary and waits for confirmation.
4. Swaps under a journal, so `BackupService.recover` can finish or undo it at the next start.

Restored `SCHEDULED` posts return to `APPROVED`. Otherwise two phones would publish the same post.

API keys and Instagram tokens stay out of all of this. They live in `noBackupFilesDir`, sealed by a
device-bound Keystore key, and are entered again on the new phone.

### Phone-to-phone transfer and resume

Nearby Connections cannot resume a payload, so the transfer does not rely on that. The sealed
archive is cut into ordered chunks (4 MiB, or larger for very big archives so the plan stays under
256 chunks), and each chunk is its own payload. The logic is plain Kotlin in
`domain/transfer` (`ChunkManifest`, `ChunkPlanner`, `ChunkVerifier`, `ChunkAssembler`,
`SourceSession`, `DestinationSession`); `NearbyTransfer` only moves bytes and can be replaced by a
fake link in tests.

Protocol, in order (control messages are small ASCII BYTES payloads, chunks are STREAM payloads):

1. Both owners confirm the Nearby authentication digits.
2. Source sends `Manifest`: migration id, total size, SHA-256 of the whole sealed archive, a key
   check, and for each chunk its index, size and SHA-256.
3. Destination loads any kept chunks for that migration id, re-hashes them, and shows the owner an
   offer ("n of m parts already here"). On accept it replies `Have` with the index and hash of each
   verified chunk it holds.
4. Source sends only the missing chunks, one at a time, each framed as a 4-byte index plus the
   chunk. The destination checks size and SHA-256 against the manifest, stores it as
   `chunk-<index>.part` (written to a temp file and renamed only after the check), then replies
   `Ack`. A chunk that fails is deleted and answered with `Nak`; the source resends it, and gives up
   on that chunk after three refusals.
5. When every chunk is present the destination joins them in order, re-checks each chunk and the
   whole-archive SHA-256, and only then hands the file to the normal restore path (decrypt, unpack
   to staging, verify, review). It replies `Done` and deletes the chunks. If a chunk turns out to be
   bad at that point, it sends `Want` for just those indices.

If the link drops, the source's data is untouched and both phones keep their state: the source
keeps the sealed archive and its manifest, the destination keeps the verified chunks. The owner
sees Retry and Cancel in Data & Migration. Retry always means a new pairing (new QR code, new
session, both owners confirm the digits again); Cancel deletes the kept archive and chunks. A chunk
that was in flight when the link dropped starts over, because a payload cannot continue in the middle.

Key handling across a re-pairing. The archive is sealed once, with the key of the first QR code.
Chunks are slices of those exact bytes, so the source never re-seals for the same migration id.
The key is **not** re-sent and **not** reused from the new QR code: the destination stores it, wrapped
by a device-bound Keystore key (`KeystoreKeyWrapper`, bound to the migration id), in
`noBackupFilesDir/transfer-in/<id>/` beside the chunks, and uses it for the final decrypt. The source
does not keep the key at all; it keeps only an HMAC of the migration id under it (the key check) so
the destination can tell, before any data moves, whether kept chunks and the offered manifest belong
together. The new QR code carries a fresh session name, key and expiry; it only decides which phone is
found and expires after 10 minutes. If the destination has lost its kept state, it cannot open the
old archive and tells the owner to cancel and start over. Nothing secret appears in the QR code beyond
the one-time key and session, in the manifest, or in logs. Only one unfinished transfer is kept per
phone; starting another removes the old one.

Names from the other phone are untrusted. A migration id must be exactly 32 lowercase hex characters
and a chunk file is only ever `chunk-<n>.part` with n below 256, built from an integer, so no
received string becomes part of a path. The manifest is validated in its constructor: ordered
indices, sizes that add up to the total, hex hashes, limits on count and chunk size. A chunk is
read at most size+1 bytes so an oversized one cannot fill the disk.

What is not covered: a transfer cannot continue after the destination has deleted its kept chunks,
and if the link drops after the destination assembled the archive but before the source heard
`Done`, the source still shows the transfer as unfinished while the destination already has the data.

## Database upgrades

`NexioDatabase.MIGRATIONS` is where each schema change goes. There is no destructive fallback, so a
missing migration fails loudly instead of wiping data. Schema files in `data/schemas` are committed.

- Version 2 adds `content_item_media` (carousel images: content item, media file, position) and the
  nullable `instagram_post.carouselProgress` column (per-image upload and container state, so a
  restarted publish does not recreate finished children). Single-image, Reel and Story posts keep
  using `content_item.mediaId`. `MigrationTest` checks the 1→2 upgrade against `2.json` and opens a
  version 1 file through `NexioDatabase.build`, the same path a restored older backup takes.
