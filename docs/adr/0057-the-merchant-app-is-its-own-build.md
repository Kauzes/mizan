# ADR 0057: The merchant app is its own build, shaped like Sentinel Pay, and talks only to the gateway

- Status: accepted
- Date: 2026-09-15
- Jira: MIZ-97

## Context

MIZ-14 adds an Android app for merchants taking payments in person: sign in, take a payment, queue it
without signal, see what became of it, rule on a held payment. The epic asks for Kotlin and Jetpack
Compose, MVVM with coroutines and Flow, "matching the structure of the existing Sentinel Pay app",
and an APK built on every push. It also warns that on the previous Android project, running the app
caught three bugs reading the code did not.

This story is the skeleton the other five build on, so the choices here are the ones the rest inherit.

## Decision

**The app lives in `android/` as a standalone Gradle build with Sentinel Pay's toolchain and layout.
It talks to the platform only through the gateway, at an address set at build time, and it is checked
on an emulator before any behaviour is claimed.**

- **Its own build, not a module of the platform's.** The platform is Java 21 with Spring Boot; the app
  is the Android Gradle Plugin on JDK 17. Nothing on the server side depends on the app, and folding
  them into one build would make every platform build download an Android toolchain. The root
  `settings.gradle.kts` does not include it, and it has its own workflow.
- **Sentinel Pay's versions and shape.** Gradle 9.1.0, AGP 9.0.1, Kotlin 2.3.20, Compose BOM 2026.03.01,
  Navigation 3; one `app` module with `data`, `domain`, `theme` and `ui/<feature>` packages; view models
  reach a single client through the `Application`, with no dependency injection framework. Only what is
  used is declared: Room and KSP arrive with the offline queue (MIZ-100).
- **Only the gateway.** The app never addresses a service directly, exactly as a merchant's own server
  does not. The gateway URL is `-Pmizan.gateway=...`, defaulting to `http://10.0.2.2:8080`, which is how
  an emulator reaches the machine running the local Compose stack.
- **Plain HTTP to two hosts only.** The local stack is HTTP. Allowing cleartext app-wide to make that
  work would also allow it to a deployed gateway, which carries card numbers and tokens, so a network
  security config permits it for `10.0.2.2` and `localhost` and nothing else.
- **No HTTP library yet.** The one call so far is a health check, made with the standard library.
  Choosing a client (and its serialisation and token refresh) belongs with the first call that needs
  one: signing in, MIZ-98.
- **Three answers to "is the platform there", not two.** Up; answered but not up; nothing answered. The
  last is usually the phone (no network, wrong address, cleartext refused), and the second is the
  platform, which are different conversations for whoever is holding it.
- **CI runs the unit tests, builds the APK and compiles the UI tests; the UI tests run on an emulator
  locally.** A hosted runner can only provide an emulator slowly and unreliably, and a flaky UI job is
  one that gets ignored. Every pull request that changes the app says it was run on an emulator.

## Evidence

On a `medium_phone` emulator, Android 16 (API 36), with the Compose stack running on the same machine:

| | |
|---|---|
| Unit tests (`./gradlew test`) | 4 passed |
| Compose UI tests (`./gradlew connectedDebugAndroidTest`, on the emulator) | 4 passed |
| APK installed and launched | first frame in about 6 seconds |
| "Check the platform", default build | **The platform is up.** |
| "Check the platform", built with `-Pmizan.gateway=http://10.0.2.2:9` | **Nothing answered at this address: ConnectException: Failed to connect to /10.0.2.2:9.** |

The default build reached the local gateway through `10.0.2.2` and the cleartext exception. An address
with nothing behind it was reported as unreachable, not as a platform that is down. Both were read off
the device's screen, by screenshot and UI hierarchy, rather than inferred from a successful command.

Driving it also caught a bug in the driving: the first tap was computed from the button's bounds
`[384,661][697,714]` with the brackets deleted, which glued `661` to `697` and sent the tap a third of a
million pixels off screen. Nothing happened, and a check that only looked for errors would have passed.

## Consequences

- **An APK built with the default address only works against a local stack from an emulator.** A
  physical phone on the same network needs `-Pmizan.gateway=http://<machine's LAN address>:8080`, and
  that address would also need adding to the network security config. Deliberate: a build that could
  talk cleartext to any host is the wrong default to ship.
- **UI tests can regress without CI noticing** until someone runs them. The mitigation is procedural
  (every app PR states the emulator run), which is weaker than a gate. If the app grows enough UI for
  that to bite, an emulator job on main is the next step.
- **Two Android projects now share a toolchain by convention, not by a shared file.** Upgrading one
  without the other is possible and should be done together.

## Alternatives

- **A Gradle module in the platform build.** Couples two toolchains that share nothing, and slows every
  platform build.
- **Kotlin Multiplatform, or a cross-platform framework.** The epic asks for Compose and Sentinel Pay's
  structure; there is no second client to share code with.
- **Retrofit or Ktor from the start.** Reasonable, and exactly the decision deferred to the story that
  first needs more than one GET.
