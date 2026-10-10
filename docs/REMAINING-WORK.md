# Gearslip work handoff

Status checked on 10 October 2026. This document lives on the fork-only
`docs/remaining-work` branch. Feature implementations live on the branches below;
checking out this documentation branch does not combine them.

## Already implemented

| Work | Fork branch | Upstream status |
| --- | --- | --- |
| Media template discovery and Music category | `feature/host-media-category` | [PR #10](https://github.com/Seb3thehacker/gearslip/pull/10) merged |
| Toasts on the projected display | `feature/host-toasts` | [PR #11](https://github.com/Seb3thehacker/gearslip/pull/11) merged; upstream has subsequent refinements |
| Navigation app location updates | `feature/host-app-location` | [PR #13](https://github.com/Seb3thehacker/gearslip/pull/13) open, draft |
| Navigation suggestions in the launcher | `feature/host-suggestions` | [PR #14](https://github.com/Seb3thehacker/gearslip/pull/14) open, draft |
| Bundled projection certificates and selection UI | `feature/bundled-projection-identity` | [PR #15](https://github.com/Seb3thehacker/gearslip/pull/15) open, ready for review |
| Kotlin System Bridge module and selective app visibility | `feature/system-car-host` | Pushed to the fork; no upstream PR |

The System Bridge handles system-side identity and permission mediation. Gearslip
still needs to implement the car APIs below. An active bridge is not evidence that
an app works end to end. Keep the module optional: upstream requires ordinary
Android permissions and consent dialogs for its features.

## Remaining feature work, roughly least to most effort

These are estimates, not measured schedules. All seven branches below are pushed
placeholders at `246f571`, with no implementation commits. Refresh each from fresh
upstream main before starting, as originally requested. Upstream PRs target
`experimental`; account for changes already there without reverting merged work.

1. **Car app launch requests — `feature/host-app-launch`.**
   `ICarHost.startCarApp()` currently only logs the request. Resolve supported
   navigation/app intents, open or switch the appropriate car app, and handle
   unsupported requests clearly. Validate the destination and respect Android's
   launch restrictions and consent requirements.
2. **App alerts — `feature/host-alerts`.**
   Implement `showAlert()` and `dismissAlert()`, currently no-ops. Render the alert,
   deliver action/dismissal callbacks, handle timeout and replacement, and clear
   alerts when their owning app disconnects.
3. **Navigation trip integration — `feature/host-navigation-trip`.**
   The host stores `Trip` updates but needs useful integration into Gearslip's
   navigation UI and supported head-unit outputs. Handle start/end state, steps,
   distance and arrival data, and clear stale routes. Advertise only capabilities
   that are actually implemented.
4. **Media playback host — `feature/host-media-playback`.**
   Spotify requests `media_playback`, for which Gearslip currently returns no
   Binder. Implement `IMediaPlaybackHost` and connect its supplied session token to
   `CarMedia`, `CarServices`, and audio routing. Preserve metadata, artwork, queue,
   playback state, transport controls, shuffle and repeat. Verify that the caller
   and controller belong to the connected car app, and clean up callbacks on
   replacement/disconnect. Acknowledging the token without attaching a controller
   does not make the playback template work.
5. **Microphone input — `feature/host-microphone`.**
   `openMicrophone()` currently returns null. Implement the requested audio stream,
   permission prompts, format negotiation, cancellation and descriptor cleanup.
   Coordinate with existing audio capture/focus, and stop capture when the owning
   session ends. Check the AndroidX contract and official implementation before
   choosing the stream source.
6. **Car hardware data — `feature/host-hardware-data`.**
   The current host correctly replies “unsupported” for all hardware requests.
   Supply only data actually available from the head unit or an appropriate phone
   source, with source/status, timestamps, permissions and subscription cleanup.
   Retain honest unsupported responses for unavailable vehicle data.
7. **Device acceptance — `test/host-device-acceptance`.**
   Integrate completed feature branches in a separate test branch and exercise
   unmodified Waze and Spotify with the module scoped only to System Framework.
   Cover discovery, startup, maps/input/routing, location, microphone, media
   browse/search/artwork/playback, permission prompts, cold/warm binds, app/host
   death and reconnect. Check module-disabled behavior, unrelated clients, and
   cleanup of session permissions and component state. Test across supported
   Android versions/ROMs before claiming compatibility. Individual features should
   also be tested as they are built.

Start with **`feature/host-app-launch`**. Do not open further PRs until requested.

### Media implementation reference

The inspected Spotify 9.1.90.2270 APK uses the Binder descriptor
`androidx.car.app.media.IMediaPlaybackHost`. Transaction 1 registers a typed
`Bundleable` containing a `MediaSessionCompat.Token`; transaction 16777215 returns
interface version 1. Both are synchronous and write a successful exception header.
Use the proper compatible AndroidX interface/dependency where possible; verify the
wire contract if implementing an adapter. The existing dependency on the inspected
branch is AndroidX Car App 1.7. Native Media3-only services and custom validators
that reject system hosts are not covered by the existing module.

## Finish and validate existing changes

- Review and exercise location and suggestions from PRs #13 and #14. Their code is
  on independent branches; it is not included automatically in the certificate or
  System Bridge branch.
- Preserve selective bridge detection: only apps hidden for confirmed host
  authorization rejection become visible when the bridge is active. Keep other
  catalog exclusions and compatibility indicators. Revisit Waze's autostart
  exclusion only with evidence that it works; do not mark it verified merely
  because the module is enabled.
- Validate the actual System Framework hooks on rooted devices. The module's
  Android 12/API 31 minimum and compilation against newer SDKs are not runtime
  certification. Existing tests do not prove the complete system-server path.
  See `system-hook/README.md` on `feature/system-car-host` for build/setup details.
- On another computer, build Gearslip and the module with the same signing key.
  The bridge requires matching host/module signatures. Independently generated
  debug keys differ between computers; existing installations may need a deliberate
  signing/install migration. Do not commit signing keys or proprietary APKs.
- Complete certificate UI visual checks and head-unit testing for PR #15. The last
  emulator attempt crashed during startup, so the dialog has no visual verification.
  Current certificate branch head: `6efb386`.
- Update the external stats backend to accept `cert` values `android_auto`, `dhu`
  and the empty value before a TLS attempt, plus boolean `cert_expired`. That backend
  is outside this repository and has not been changed here.
- Replace the bundled Android Auto identity through an app update before
  **20 January 2027, 22:48:17 UTC** to avoid rejection by date-checking head units.
  The DHU alternative expires **1 August 2048, 17:21:23 UTC**. It is not the aasdk
  certificate, which expires **29 April 2045, 21:28:38 UTC**.
- Consider a separate fix for the existing APK-renaming Gradle task: deleting AGP's
  original output can produce an incomplete incremental APK. Until fixed, use a
  clean build when producing an APK for installation.

The intended certificate behavior is settled: try Android Auto even when expired;
show an expiry warning that explains switching manually to Head unit (DHU) if it
fails. Settings persists the selection for the next connection, and stats report
the identity actually attempted. **Do not restore automatic fallback.** The latest
UI puts expiry in the tappable Certificate entry and uses padded selection cards.

Keep useful explanatory comments when refactoring. Do not restore the removed
`system-hook/HOST-INTEGRATION.md` or `system-hook/VALIDATION.md` documents.

## Resume on another computer

```sh
git clone https://github.com/iscle/gearslip.git
cd gearslip
git remote add upstream https://github.com/Seb3thehacker/gearslip.git
git fetch --all
git show origin/docs/remaining-work:docs/REMAINING-WORK.md
git switch --track origin/feature/host-app-launch
```

Check branch history and refresh the untouched placeholder against upstream main
before implementing. Do not reset branches containing work. Fetch again before
reviewing PR status; the table above is a snapshot.

Use JDK 21 and the Android SDK. Build requirements and APK location are in
`CONTRIBUTING.md`; the module has additional instructions in its branch's README.

```sh
./gradlew :gearslip:clean :gearslip:assembleDebug :gearslip:testDebugUnitTest
./gradlew :gearslip:lintDebug
```

The APK is `build/Gearslip-debug.apk`. Last certificate validation: 51 unit tests
passed before the final UI-only change; the final clean debug build passed. Lint
reported 105 existing errors and no new findings from that change. Recheck the
baseline on whichever branch is being changed; lint is not globally clean.
