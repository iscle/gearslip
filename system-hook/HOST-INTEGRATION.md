# Remaining Gearslip implementation

The system module supplies access and identity. It deliberately does not implement
car rendering, car protocol services or media playback. This branch leaves
`gearslip/src/` unchanged at upstream commit
`246f571a86e6a6b7f20e05e651eae12257317685`.

## Detect the running bridge

Use `context.packageManager.hasSystemFeature("app.seb3thehacker.gearslip.SYSTEM_CAR_HOST")`.
The system hook returns true only to a host with the allowed package and matching
module signing identity. Merely finding the installed module APK is insufficient:
it may be disabled, scoped incorrectly, or require a reboot.

The existing explicit `bindService` calls for `CarAppService` and
`MediaBrowserService` need no package spoofing or protocol changes. Keep using
Gearslip's real package in the AndroidX handshake and browser connect call. The
system bridge validates that identity before rewriting it on the selected connection.

## Make template apps reachable

In `car/CarLauncher.kt`, condition the broken-template/player-only filter on the
bridge being unavailable. It currently removes Spotify's template tile even if
the system returns an enabled service. This is a local Kotlin predicate and cannot
be fixed by a system permission hook.

In `host/CarAppCatalog.kt`, include `androidx.car.app.category.MEDIA` in category
discovery. A returned disabled-by-default template service is bindable through the
bridge; do not change its persistent component state from Gearslip.

In `car/CarServices.kt`, review the known-broken navigation exclusion used for
autostart, which currently excludes Waze. Presence of the bridge can allow trying
the connection, but should not mark an application as tested/verified automatically.
Preserve useful failure feedback when a custom validator still rejects the host.

## Implement the media playback host

Spotify 9.1.90.2270 calls `ICarHost.getHost("media_playback")` and expects an
`androidx.car.app.media.IMediaPlaybackHost` Binder. Current Gearslip returns null.
Its AndroidX 1.7 dependency does not provide this newer host interface, so either
upgrade the compatible model/stub dependency or add the small Binder implementation
in Gearslip. Do not put this host implementation in `system-hook`.

Wire contract verified from the supplied APK:

| Transaction | Input | Result |
| --- | --- | --- |
| `INTERFACE_TRANSACTION` | none | descriptor string |
| `1` (`registerMediaSessionToken`) | interface token, typed `Bundleable` containing `android.support.v4.media.session.MediaSessionCompat.Token` | synchronous `writeNoException()` |
| `16777215` (`getInterfaceVersion`) | interface token | synchronous `writeNoException()`, integer `1` |

Registration must do real work: decode the token, verify the caller belongs to the
currently connected car application, and verify the resulting MediaController's
package is that application. Post controller/UI changes to Gearslip's main thread;
discard callbacks belonging to a replaced/disconnected template connection.

Attach the supplied framework session token to `media/CarMedia.kt`, preserving
metadata, playback state, queue, artwork and transport controls. Attach a compatible
controller too for shuffle/repeat. Unregister old controller callbacks before
replacement and clean them up when the car session ends. Update the selected media
application in `car/CarServices.kt` and connect the existing audio-routing flow.
Use the supplied session token rather than requiring notification access just to
discover a session that the template app has already shared.

Do not merely acknowledge registration: `car/TemplateContent.kt` renders its media
playback content from `CarServices.media`, so swallowing the token leaves that UI
disconnected from Spotify's player.

## Continue host development separately

Template rendering, navigation surfaces, lifecycle, foreground/location capability
lending, microphone/location permission prompts, hardware data, audio routing and
USB projection remain Gearslip responsibilities. Complete any unsupported
`getHost()` interfaces in that project, backed by their actual semantics; returning
dummy successful results does not enable full functionality.

The module's media-browser adapter includes both the framework connection and the
returned support-library Messenger used for search. Native Media3 session protocols
are not implemented by this adapter. Keep using the classic enabled browser service
where an app exposes both; the module does not expose disabled experimental browser
services during normal catalog discovery.

## Acceptance run after integration

Use unmodified application APKs and System Framework scope only. Verify Spotify
template discovery, startup, populated lists, private artwork, session registration,
play/pause/skip/seek, search, queue, shuffle/repeat and reconnection. Separately test
its legacy media-browser path. Test Waze handshake, map surface, input, routing,
audio, permissions and foreground operation. Repeat cold and warm binds, host/app
process death, unplug/replug and module-disabled fallback. Permission-layer success
is not an end-to-end acceptance result.
