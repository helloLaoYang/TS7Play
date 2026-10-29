# TS7 source-only delivery validation

Validated on 2026-10-29. This record describes local automated checks and artifact
verification, not TS7 hardware acceptance.

## Delivered application

- Application ID: `com.hilllaoya.ts7play`.
- Version: `0.1.0`, version code `1`.
- Minimum API: 25; target/compile API: 37.
- Native ABIs: `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`.
- APK size: 8,742,330 bytes.
- APK SHA-256: `8c607e88d1f71dccbf6d47ad61a0a99ab142a57ce18d0c41c0111c3f44c52a51`.
- Android APK Signature Scheme v2 verification passed, with one signer.
- No `offline-mfi` assets or private-key/credential containers are included.
- Upstream base: `615b32ce0b40cd0b99ce94ba23a512001553684f`.
- The checks below were performed on the delivered sources before repository publication.

The application ID follows the owner's explicit selection. It does not overwrite
an installed `com.shihab.diplay` package automatically. The intended workflow uses
TS7 Play as the receiver; removal of the previous DiPlay installation is a manual
device action. No software was installed or removed on a TS7 during this work.

## TS7 checks

The final package configuration passed:

```sh
./gradlew :ts7:testDebugUnitTest :ts7:lintDebug :ts7:assembleDebug
```

All 34 tests passed, with zero skipped tests. Tests cover bounded and delayed
retries, single-attempt admission, explicit cancellation, configuration errors,
settings-edit suppression, stable-session budget renewal, persisted budgets after
process recreation, manual-disconnect persistence, boot changes, HOME/Back,
Surface ratio, touch edges and cancellation, API-gated permissions, diagnostic
redaction, document export, disabling automatic connection with incomplete
configuration, and first-picture timeout suspension while the Surface is hidden.

Android-facing tests use Robolectric API 25 and 36. Native-rendered previews use
API 35, 1.3 font scale, 800 x 480 and 1280 x 720 layouts, plus an Arabic locale
with an explicitly applied RTL preview direction. The UI uses English fallback
text. Preview images were inspected; they are simulated renders, not device
screenshots or proof of CarPlay operation.

TS7 Lint finished with zero errors and six warnings. Warnings cover the older-API
handling of the back-navigation manifest attribute, fixed-orientation behavior
on newer Android, two inherited TLS trust-manager warnings in the shared
receiver dependency, the existing repository pattern for the Robolectric version
declaration, and accessibility semantics of the remote SurfaceView. No lint
baseline or suppression was introduced to hide errors.

The source tree credential-container check and `git diff --check` passed.
The negative authentication guard check intentionally failed with
`Set DIPLAY_AUTH_ASSETS_DIR for a standalone car-test APK` when the environment
variable was absent. This confirms that a source-only build is not represented
as an authenticated standalone package.

## Upstream checks

The repository's full CI command was run locally, including the new TS7 module.

| Module | Tests | Initial failures | Skipped |
| --- | ---: | ---: | ---: |
| Shared receiver | 1,798 | 1 | 1 |
| Common upstream UI | 1,729 | 0 | 0 |
| Upstream Home sample | 11 | 0 | 0 |
| TS7 at that checkpoint | 32 | 0 | 0 |

The initial full command ended with a failure in the unchanged upstream test
`BufferedAudioStreamTest.malformedLengthClosesTheTcpStreamWithoutReadingANewPrefix`:
the socket read expected `-1` and observed `72`. A single isolated rerun of that
exact test passed. The cause of the initial failure was not established; the
initial full run must not be described as clean. The shared implementation and
test were not modified.

The remaining lint/build tasks then passed together with the final 34 TS7 tests:

```sh
./gradlew :ts7:testDebugUnitTest :ts7:lintDebug :ts7:assembleDebug \
  :mobile:lintDebug :maphost:lintDebug :mobile:assembleDebug \
  :home:assembleDebug :maphost:assembleDebug --max-workers=2
```

The upstream Home lint check had already passed in the initial run. The final
command reported `BUILD SUCCESSFUL`. This record covers local commands only
and does not claim a remote GitHub CI result.

## Required device evidence

Runtime accessory authentication has not been supplied. Connection to an iPhone,
touch on a live CarPlay picture, audio routing, calls, Siri, real ACC wake,
reverse-camera behavior, continuous operation, same-signature upgrades and
uninstall/default-HOME recovery remain unverified. Follow the device acceptance
matrix in [TS7_IMPLEMENTATION.md](TS7_IMPLEMENTATION.md) before daily use.
