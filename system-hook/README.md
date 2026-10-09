# Gearslip System Bridge

A Kotlin Xposed module for the Android **System Framework (`android`) scope only**.
The module supplies a system-side identity and permission bridge for Gearslip's
connections to AndroidX car apps and classic Android media-browser services.
It never loads hooks into Waze, Spotify, Gearslip, or any other application process.
Car projection, templates, navigation, playback and user interaction remain the
responsibility of Gearslip.

This branch contains the module and its tests. **Gearslip's application source is
unchanged.** The host work required to use Spotify templates fully is described
in [HOST-INTEGRATION.md](HOST-INTEGRATION.md).

## Build and install

Use the repository's Gradle wrapper, Android SDK 37 and Java 21:

```sh
./gradlew :system-hook:assembleDebug :system-hook:testDebugUnitTest :system-hook:lintDebug
./gradlew :gearslip:clean :gearslip:assembleDebug
```

The module APK is `build/system-hook/outputs/apk/debug/system-hook-debug.apk`.
The unmodified host APK is `build/Gearslip-debug.apk`.
Build the host clean: its upstream APK-renaming task deletes Gradle's original
output, which can produce an incomplete APK on a subsequent incremental package.
This branch does not change that host build task.

1. Install both APKs from the same build environment.
2. Enable **Gearslip System Bridge** in LSPosed or Vector.
3. Select only **System Framework / Android** in its scope and reboot.
4. Open Gearslip and the desired car application. Complete their normal sign-in
   and Android runtime-permission prompts.

The module accepts only `app.seb3thehacker.gearslip` and its `.dev` variant,
with exactly one package in the calling UID and a signing certificate matching
the installed module. Both debug artifacts use the same local debug keystore.
For release builds, both modules read the existing `keystore.properties` signing
configuration. An upstream prebuilt host signed by a different developer will
not be authorized by a locally signed module. Release APKs are unsigned when no
release key is configured; this is intentional.

Disable the module and reboot to remove its system hooks. No APK, system image,
SELinux policy, installed certificate, or globally granted permission is modified.

## What the implementation does

* Intercepts the **client-specific service connection**, including already-bound
  services. It does not replace the shared service Binder for unrelated clients.
* For `androidx.car.app.ICarApp`, forwards the connection from `system_server`
  (actual UID 1000), rewrites the AndroidX handshake package to `android`, and
  preserves its API level, callbacks, Binder objects and transaction flags.
  Subsequent calls retain the authenticated system identity.
* For `android.service.media.IMediaBrowserService`, presents the Android Auto
  integration package during `connect`. Active car-app readers receive a matching
  UID-1000 package view. The framework callback is retained across subscriptions,
  item lookups and disconnect. The returned MediaBrowserCompat Messenger is also
  mediated so registration and search use the same identity.
* Clears package-identity caches when sessions open/close, including warm bindings.
  The synthetic package view uses the real platform signing information. It does
  **not** fabricate Google's certificate or change the app's installed signature.
* Expands trusted-host discovery to disabled, exported **template** services and
  permits the selected service to bind without changing its saved enabled state.
  It respects disabled applications and `DISABLED_USER` components. A car app's
  own component-setting requests are deferred while its service is in use, then
  applied after the last connection closes. Other callers' settings are passed
  through and supersede deferred requests.
* Allows the selected exported car service's declared binding permission only
  during that specific trusted-host bind. Other permission checks are unchanged.
* Copies read/prefix URI grants that a car application issues to Google's car
  hosts, to the connected Gearslip UID. It covers both ordinary Context grants
  and owner-based grants, including attempts discarded because Google packages
  are absent. Android still validates the grant against the source provider.
  Each connection gets a separate URI-permission owner; the module handles
  source revocation, owner cleanup, package removal, unbind and process death.
  It never makes private providers globally exported or grants write/persistable
  access. A bounded in-memory grant history supports warm service connections.
* Exposes `app.seb3thehacker.gearslip.SYSTEM_CAR_HOST` through
  `PackageManager.hasSystemFeature()` only to an authorized host when all required
  hook groups were installed. It is a capability signal, not a claim that every
  app or every host feature works.

Protocol selection is based on exported service actions and Binder descriptors,
not a list of Waze/Spotify implementation classes. Every forwarded host transaction
rechecks the session and caller; arbitrary Binder interfaces and unknown
transaction numbers are rejected. Unknown framework hooks fail closed and log a
diagnostic rather than installing broad permission bypasses.

## Compatibility and validation limits

The module's minimum Android version is **12 / API 31**, matching Gearslip. It uses
legacy Xposed API 82, supported by LSPosed and Vector. Hidden system APIs are
discovered at runtime to accommodate the PackageManagerService/ComputerEngine
split and integer/long flag signatures. This is not an OEM-ROM compatibility
certification. Android 17/API 37 compilation is not proof of runtime support.

Currently, sessions are restricted to the **primary Android user**. Secondary
users, work profiles, shared-UID hosts and isolated car services are not supported.
This restriction avoids presenting a cross-user system identity that the inspected
validators cannot consistently authenticate.

The inspected Waze and Spotify AndroidX validators accept actual system UID 1000.
Apps with custom validators that reject system hosts, native Media3-only endpoints,
Google-private APIs, or additional server-side checks need separate adapters or
host support. Real Android Auto and Gearslip should not concurrently host the same
app: the active media-browser package view deliberately represents the relay.
No module can supply Spotify account entitlements or implement a missing Gearslip
host interface by granting a permission.

Unit/parcel tests cover Android 12 and Android 16 with Robolectric: the real
AndroidX serializer, callback and subscription identity, item argument layouts,
Messenger handoff/search, malformed handshakes and unauthorized callers. The same
protocol suite can run on a device:

```sh
./gradlew :system-hook:connectedDebugAndroidTest
```

These tests do not install Xposed or prove the full system-server path. Before
calling a device supported, test cold/warm connections, app/host death, reconnect,
artwork and revocation, browse/search/playback, Waze maps and navigation, permission
dialogs, both host variants, and an unrelated client as a negative control.
Confirm a disconnected host cannot use an old relay or retain session-only image
access. Verify component settings and package identity return to normal.

Diagnostics are prefixed `GearslipBridge:` in the framework's Xposed log. On boot,
check `packages=true grants=true services=true ready=true`. Later `unavailable`
messages indicate a hook or operation requiring investigation on that ROM.
The module intentionally does not log image URIs, credentials or playback queries.

## Implementation references

* [AOSP service binding and ordinary URI grants](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/services/core/java/com/android/server/am/ActivityManagerService.java)
* [AOSP package queries](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/services/core/java/com/android/server/pm/ComputerEngine.java)
* [AOSP URI grant ownership](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/services/core/java/com/android/server/uri/UriGrantsManagerService.java)
* [AndroidX host validation](https://github.com/androidx/androidx/blob/androidx-main/car/app/app/src/main/java/androidx/car/app/validation/HostValidator.java)
* [Vector's supported module APIs](https://github.com/JingMatrix/Vector#developer-resources)

The input APKs and decompiled proprietary application sources are not included.
