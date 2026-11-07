# Stage 3: API, database, carousel, backup, resume

All JVM rows come from one run on 2026-11-07:

```
./gradlew :domain:test :data:testDebugUnitTest --rerun-tasks --console=plain
BUILD SUCCESSFUL in 1m 29s
domain: 124 tests, 0 failures, 0 errors, 0 skipped
data:    20 tests, 0 failures, 0 errors, 0 skipped
```

Per-test results were read from `*/build/test-results/*/TEST-*.xml`: 144 test cases, every one
`passed`. Test files: `domain/src/test/kotlin/com/nexiolab/socialai/domain/ai/` (BackupTest,
InstagramPublisherTest, PipelineTest, AiGatewayTest), `domain/src/test/.../domain/transfer/ChunkedTransferTest.kt`,
`data/src/test/.../data/db/MigrationTest.kt`, `data/src/test/.../data/instagram/InstagramAdapterTest.kt`,
`data/src/test/.../data/ai/AdaptersTest.kt`. Device tests live in `data/src/androidTest/.../DeviceDataTest.kt`.

| Test | Result | Evidence | Issue | Fix | Retest |
|---|---|---|---|---|---|
| Token storage at rest (Android Keystore) | NOT RUN | `DeviceDataTest.keystoreRoundTripWithMaskingAndTamperDetection` and `credentialBoundToItsAlias` need `:data:connectedDebugAndroidTest`. `adb devices` listed no device or emulator this session | - | - | - |
| Tokens and keys sent only where they belong | PASSED | `AdaptersTest.keyIsSentOnlyToTheSameHost`, `AdaptersTest.geminiKeepsKeyOutOfUrlAndCountsThinkingAsOutput`, `InstagramAdapterTest.reelContainerUsesOfficialParametersAndHeaderToken`, `InstagramAdapterTest.expiredTokenIsRecognised`: passed | None | None | Not needed |
| Credentials excluded from backup archive | NOT RUN | `DeviceDataTest.backupContainsNoCredentialFiles` is a device test; no device this session | - | - | - |
| Publish state machine (single post) | PASSED | InstagramPublisherTest: `happyPathPublishesOnceAndCleansHosting`, `networkFailureOnPublishIsNeverRetriedBlindly`, `uncertainPostThatActuallyWentThroughBecomesPublishedWithoutRepublishing`, `uncertainPostStillUnusedIsSafeToPublish`, `processDeathMidPublishResumesFromUncertainNotFromTheStart`, `ownerResolvesUncertainPost`, `failedPostWhoseContainerIsActuallyPublishedIsNotRepublished`, `reapprovalAfterAnUncertainPostResumesInsteadOfCreating`: passed | None | None | Not needed |
| Carousel state order | PASSED | `InstagramPublisherTest.carouselFlowRunsInTheDocumentedOrderAndPersistsUncertainBeforePublishing` (uploads → child containers with `is_carousel_item` → children FINISHED → CAROUSEL parent → `PUBLISH_UNCERTAIN` stored before the publish call → PUBLISHED), `carouselChildrenKeepTheOwnersOrder`, `carouselResumesWithoutRecreatingFinishedChildren`: passed | None | None | Not needed |
| Carousel 2–10 image rule | PASSED | `InstagramPublisherTest.carouselNeedsTwoToTenImagesAndOnlyImages`, `carouselRuleLivesInTheWorkflow` (1 and 11 refused, 2 and 10 accepted, a video child refused, approval blocked): passed | None | None | Not needed |
| Carousel change withdraws approval | PASSED | `InstagramPublisherTest.changingTheCarouselImagesOrOrderWithdrawsApproval`: passed | None | None | Not needed |
| No partial carousel | PASSED | `InstagramPublisherTest.oneFailingChildFailsThePostWithoutAParentAndDeletesEveryHostedFile`, `anExpiredChildFailsTheWholeCarousel`, `anUploadFailureMidwayFailsThePostCreatesNothingAndCleansWhatWasUploaded`: passed | None | None | Not needed |
| Uncertain carousel is never republished | PASSED | `InstagramPublisherTest.anUncertainCarouselIsNeverPublishedAgainWhileTheAnswerIsUnclear`, `anUncertainCarouselThatWentThroughIsConfirmedNotRepublished`, `anUncertainCarouselMayBePublishedOnlyWhenInstagramSaysFinished`: passed | None | None | Not needed |
| Carousel Graph API requests | PASSED | MockWebServer: `InstagramAdapterTest.carouselChildIsAnImageContainerMarkedAsCarouselItem`, `carouselParentListsTheChildrenInOrderWithTheCaption`, `carouselCallsKeepTheDeliveredSemantics`: passed. Not checked against the real Instagram API (stage 5) | None | None | Not needed |
| Planner proposes a carousel only with ≥2 library images | PASSED | `PipelineTest.plannerProposesACarouselOnlyWhenTheLibraryHasTwoImages`, `plannerKeepsASingleImagePostWithFewerThanTwoLibraryImages`: passed | None | None | Not needed |
| Room migration 1→2 keeps posts | PASSED | `MigrationTest.version1To2KeepsExistingPostsAndMatchesTheSchema` (seeds a v1 post and an uncertain publication, migrates, Room validates against `data/schemas/.../2.json`) and `aVersion1FileOpensThroughTheAppBuilderAndKeepsItsData` (the path a restored v1 backup takes): passed | Failed twice mid-session while the schema work was unfinished (stage 1) | Migration and schema completed | Passed in the final run |
| Backup checksums and archive safety | PASSED | BackupTest: `tamperedFileFailsChecksum`, `anyFlippedByteIsDetected`, `truncationAtAChunkBoundaryIsDetected`, `reorderedChunksAreDetected`, `pathTraversalIsRejected`, `unexpectedFileIsRejected`, `compressionBombStopsAtDeclaredSize`, `wrongPasswordIsRefused`, `passwordIsNotStoredAndPlaintextDoesNotLeak`: passed | None | None | Not needed |
| Backup and restore on a device | NOT RUN | `DeviceDataTest.backupRestoreBringsDataBackAndLocalisesIt` needs a device; none this session | - | - | - |
| Transfer chunk manifest verification | PASSED | ChunkedTransferTest: `goodManifestBuildsRoundTripsAndPassesValidation`, `tamperedManifestIsRejected`, `traversalAndOddNamesAreRejected`, `controlMessagesRoundTripAndGarbageIsRejected`, `theKeyNeverAppearsInTheManifest`: passed | None | None | Not needed |
| Missing middle chunk | PASSED | `ChunkedTransferTest.missingMiddleChunkRefusesAssemblyAndPlannerListsExactlyThatIndex`: passed | None | None | Not needed |
| Bad chunk checksum | PASSED | `ChunkedTransferTest.badChecksumChunkIsDiscardedAndRequestedAgain`, `aBadChunkIsDiscardedAndSentAgain`, `aChunkThatKeepsFailingStopsTheTransferButKeepsTheGoodChunks`, `finalArchiveHashIsCheckedEvenWhenEveryChunkMatches`: passed | None | None | Not needed |
| Retry does not resend completed chunks | PASSED | `ChunkedTransferTest.aRetryAfterADropSendsOnlyTheRemainingChunks` (fake link, drop at chunk 3 of 5, the retry sends exactly `[3, 4]`), `everyDropPointResumesWithoutResendingFinishedChunks`, `whenEverythingIsAlreadyKeptNothingIsSentAndTheArchiveIsDelivered`: passed | None | None | Not needed |
| Re-pairing after expiry keeps verified chunks | PASSED | `ChunkedTransferTest.anExpiredPairingCodeIsRejectedAndARetryGetsAFreshOne`, `keptChunksKeepTheirOriginalKeyWhenTheSecondPairingBringsANewOne`, `aDestinationWithoutTheKeptChunksAsksTheOwnerToStartOver`, `cancellingDeletesEverythingKept`: passed | None | None | Not needed |
| Chunked transfer between two real phones | NOT RUN | Needs two physical phones with Nearby Connections. Not performed. The Nearby adapter, `KeystoreKeyWrapper` and the Data & Migration retry UI are compile-checked only | - | - | - |
