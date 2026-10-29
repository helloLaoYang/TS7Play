# TS7 CarPlay launcher v0.1

For the current Chinese interface and built-in DiPlay pages, see
[version 0.2.0](TS7_0.2.0.md). This document preserves the initial implementation
and its device-acceptance baseline.

The `:ts7` application is a single-APK launcher and CarPlay receiver based on
DiPlay commit `615b32ce0b40cd0b99ce94ba23a512001553684f` (upstream version 0.2.22).
It reuses `:shared` for the existing Wi-Fi transport, authentication, main-screen
decoding, audio, microphone and touch protocols. It does not embed another app or
use the Android 11 cross-process dashboard-map surface API.

This is a source-only test implementation until a valid accessory identity is
provided and the same TS7/iPhone/network combination passes the device checklist.
The APK has no bundled authentication identity. Android signing keys and accessory
authentication are separate inputs.

## Build

Use the tool versions in [BUILD.md](BUILD.md): JDK 25, the repository Gradle
wrapper, Android SDK Platform 37.0, Build Tools 36.0.0 and NDK 28.2.13676358.
Set `JAVA_HOME` and `ANDROID_HOME` for your installation. No project-local secret
configuration is needed.

```sh
./gradlew :ts7:testDebugUnitTest :ts7:lintDebug :ts7:assembleDebug
```

The source-only debug APK is `ts7/build/outputs/apk/debug/ts7-debug.apk`.
Its application ID is `com.hilllaoya.ts7play`, with minSdk 25 and targetSdk 37,
as explicitly selected by the owner. This app replaces DiPlay in the intended
workflow and does not depend on the original DiPlay app. Android treats the two
package IDs as separate apps, so installing this APK does not automatically
uninstall or update DiPlay. Record any required configuration before manually
removing DiPlay; uninstalling it removes its private settings and authentication
data. The original vehicle launcher remains installed and independently selectable.
The supplied package uses a local debug signing key. Future updates to this TS7
package must retain both its package ID and signing key. Run only this receiver
during a connection test.

For a standalone car-test APK, provide your valid external runtime directory:

```text
runtime-assets/
  offline-mfi/
    identity.pk8
    certificate.p7b
```

Set `DIPLAY_AUTH_ASSETS_DIR` to that directory in the build process environment,
then run `./gradlew :ts7:assembleStandaloneDebug`. This task fails when the
authentication input is absent. Never commit the directory, private keys or
Android signing material. Use the same Android debug signing key for test-package
updates that preserve settings. Production signing and version migration are
separate release work; the test module does not configure a production key.

## First use

1. Record the original DiPlay configuration and confirm that the vehicle launcher
   and system settings can still be opened. Install this test APK and remove the
   original DiPlay manually when switching to the new receiver. Keep the vehicle
   launcher installed. The source-only APK cannot replace working CarPlay until
   valid runtime authentication has been provided and verified.
2. Open TS7 CarPlay. Confirm **System settings** and **Original home** work.
   If no unambiguous original HOME is detected, select it in Settings.
3. Put the head unit and iPhone on the existing Wi-Fi network. Pair the phone in
   Android Bluetooth settings. The app never creates a hotspot or changes the
   system default network.
4. Open Settings, enter the exact SSID and password, select the paired iPhone,
   and grant connection and microphone permissions. An empty password means an
   open network. WPA2 and compatible WPA2/WPA3 mixed networks use the upstream path.
5. Save, then connect manually. A source-only package reports missing runtime
   authentication instead of claiming a successful connection.
6. After a standalone package passes connection, touch, audio and manual
   disconnect checks, enable automatic connection if wanted.
7. Use **Choose default home app** to select TS7 CarPlay. This is a user choice;
   the app does not remove or replace the original launcher package.

The Apps list contains launchable components returned by Android. Tap an app to
open it; long-press to assign one of two persistent shortcuts. The original HOME
selection stores its real component rather than a hard-coded vendor package.
Long-press either shortcut to replace it if the target app changes.

## Ownership and recovery

`Ts7SessionService` owns the controller and media sink. The Activity observes
state and lends a Surface while visible. No protocol callback retains the
Activity. Opening local apps removes view observers, releases touches and
detaches video output while the explicitly started foreground service keeps the
session. Returning Home attaches the Surface and requests a fresh picture; it
does not create a second controller.

The main picture is fixed at 960 x 540, 30 fps, H.264. The native SurfaceView is
letterboxed to that aspect ratio. Touch mapping uses that exact View rectangle,
and a pointer release is sent before detaching. Dashboard-map, entertainment
video, dynamic resolution, widgets and arbitrary third-party embedding are not
enabled by this module.

Each explicit connection or eligible startup allows one initial attempt and at
most three retries after 2, 5 and 15 seconds. Attempts time out after 60 seconds;
an accepted session with no first picture times out after 15 seconds with an
attached display. This picture timeout is suspended while another app hides the
launcher and resumed when its Surface returns.
The retry budget is renewed only after a picture-producing session has remained
active for 60 seconds. Attempts are persisted before starting a receiver and are
not replenished by process recreation. Configuration and authentication errors do not retry.
Network availability is based on Wi-Fi link addresses, without requiring an
Internet-validated capability. Address changes retire the old receiver before
another attempt begins.

Controller creation and teardown run on one lifecycle worker. Generation tokens
discard obsolete callbacks. Teardown must confirm completion before a replacement
is allowed. A cleanup timeout blocks further connection attempts in that service
instance instead of risking two receiver stacks. Force-stop and reopen the test
app to recover from that exceptional state.

Manual disconnect cancels retries and persists suppression across Activity and
service recreation. Home, network callbacks and setting edits do not clear it.
A new Android boot or an observed screen-off/screen-on cycle with the explicit
resume option enabled can clear it. Screen wake is a generic Android event, not
a verified TS7 ACC signal. When no foreground service is already running,
automatic startup waits until the launcher is visible so modern microphone
foreground-service restrictions are respected. Nothing launches an Activity on
boot, wake, connection or a phone callback, so the app does not intentionally
cover the reverse camera or another OEM screen.

Opening Settings suspends pending connection work. An established session can
continue. **Save for next connection** preserves the live session; **Save and
reconnect** explicitly replaces it. Disabling automatic connection remains
possible even when network configuration is incomplete.

## Diagnostics and local data

The app saves settings and pairing state in private application storage and
disables Android backup. The authentication directory is in `noBackupFilesDir`.
The TS7 settings schema is separate from upstream DiPlay settings. A reinstall
requires setup again; automatic migration of upstream private settings or
authentication is not implemented.

**Export diagnostic report** opens Android's document picker, without requesting
broad storage access. The bounded event history contains timestamps and typed
event names. Reports include API, build display, CPU ABIs, screen and memory,
connection state, attempts and the negotiated display offer. They do not include
SSID, network addresses, phone identifiers, passwords, authentication, pairing
material or raw upstream protocol logs. The upstream receiver may still emit its
own logcat diagnostics; these are not copied into this export.

Generic Android APIs do not identify the TS7 MCU or reliably expose device
temperature. Actual sustained frame rate, dropped frames, MCU firmware and
thermal behavior must be collected during the device test. A first-frame event
is not a sustained performance measurement.

## Requirement coverage and remaining evidence

| Requirement | Implementation | Device evidence required |
| --- | --- | --- |
| F01 HOME | HOME intent, single task, no session creation from Home | Default HOME selection and firmware behavior |
| F02 Main picture and touch | Same-process Surface, fixed aspect, upstream touch mapper | Edge touches, swipes and first picture |
| F03 Local desktop | Clock, app list, two shortcuts, status and system controls | Launcher responsiveness on TS7 |
| F04 Same LAN | Existing Wi-Fi backend; saved SSID, password and paired phone | Actual topology and authentication |
| F05 Failure control | Explicit states, cancellation, timeout, bounded retries | Phone departure, bad credentials and network changes |
| F06 App switching | Service-owned receiver, detachable Surface, touch release | 20 application round trips, no black screen |
| F07 Startup | Opt-in auto start, boot counter, opt-in observed screen wake | 10 cold boots and 20 actual ACC wakes |
| F08 Audio and OEM entry | Upstream focus/audio/microphone, selected original HOME | Music, navigation, call, Siri, radio and reverse camera |
| F09 Settings and diagnostics | Connection/display/audio/startup/diagnostics groups | Export readability and firmware metadata |
| F10 Extensions | Deferred as specified for P1 | Separate design and performance validation |

Complete the real-device checklist before describing the application as ready
for daily driving. Compilation or Robolectric rendering does not prove CarPlay
authentication, protocol compatibility, microphone routing or vehicle safety.

## Device acceptance record

Record the exact TS7 firmware/MCU, Android API, resolution, RAM, ABIs, iPhone model,
iOS version, original DiPlay version and source, network source, package version,
APK SHA-256 and signing certificate fingerprint for every run.

| Scenario | Required run | Result for this source delivery |
| --- | --- | --- |
| Install, select HOME, return to OEM/system settings | Same TS7 | Pending hardware |
| Manual Same LAN, touch, basic audio | Same TS7 and iPhone; valid authentication | Pending identity and hardware |
| Cold boot | 10 runs | Pending hardware |
| ACC sleep/wake | 20 runs | Pending hardware |
| Wi-Fi loss, IP change, phone departure | 5 runs each | Pending hardware |
| App switch and return | 20 runs | Pending hardware |
| Reverse camera and return | 10 runs | Pending hardware |
| Music, prompts, calls, Siri and radio | Each scenario | Pending hardware |
| Continuous run | At least 60 minutes; memory, temperature, frame metrics | Pending hardware |
| Android 7.1, 8.1, 10, 11 and modern Android | Install and actual connection on target systems | Pending hardware |
| Same-signature update and uninstall/default-HOME recovery | Preserve settings and restore OEM path | Pending hardware |

Retain the upstream [license](../LICENSE) and [third-party notices](THIRD_PARTY_NOTICES.md)
with any distribution. This module uses the shared receiver, not the separate
AGPL Home sample UI. Review the full applicable dependency notices when shipping
a binary. No Apple certification is claimed.
