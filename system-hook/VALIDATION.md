# Validation status

The initial implementation was built locally on 2026-10-09.

| Check | Result |
| --- | --- |
| Module debug APK | Built and signature verified |
| Module release APK | Built with R8; unsigned without a configured release key |
| Module lint | No issues |
| Module JVM tests | 18 passed: 12 Android parcel cases across API 31 and 36, 4 policy tests and 2 reflection-failure tests |
| Device-test APK | Compiles; shares the parcel tests with the JVM suite |
| Original Gearslip tests | 28 passed |
| Original Gearslip debug APK | Clean build; signature matches the module debug APK |
| Gearslip application source | Unchanged from upstream |
| Xposed execution in system_server | **Not yet runtime-tested** |
| Waze / Spotify end-to-end operation | **Not yet runtime-tested** |

The available Android emulator repeatedly crashed in its host process before
instrumentation could run, with hardware and software execution configurations.
Robolectric verifies parcel adaptation and rejection behavior but does not exercise
LSPosed/Vector, framework hook interception, actual cross-process caller identity,
or URI-permission enforcement. A rooted device acceptance run is still required.

The module is an implementation for testing and integration, not a certification
of universal Android Auto compatibility. See [README.md](README.md) for supported
boundaries and [HOST-INTEGRATION.md](HOST-INTEGRATION.md) for the Gearslip work that
permission and identity hooks cannot perform.
