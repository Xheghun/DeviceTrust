# DeviceTrust

DeviceTrust is an Android library for collecting device integrity signals. It detects indicators associated with rooted devices, runtime instrumentation, emulators, unlocked bootloaders, custom system images, and weakened platform security settings.

The library includes:

- A Kotlin API based on coroutines
- Native Android NDK checks
- Configurable risk thresholds
- Versioned evidence models
- Consumer R8 rules
- Native binaries for ARM64, ARMv7, x86, and x86_64

## Requirements

- Android API 26 or later
- Kotlin coroutines
- JitPack repository access

## Installation

Add JitPack to `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven(url = "https://jitpack.io")
    }
}
```

Add DeviceTrust to the application or library module:

```kotlin
dependencies {
    implementation("com.github.Xheghun:DeviceTrust:0.1.2")
}
```

## Basic usage

Create a client and run an assessment from a coroutine:

```kotlin
import com.xheghun.devicetrust.DeviceTrust
import com.xheghun.devicetrust.TrustLevel

val deviceTrust = DeviceTrust.create()
val assessment = deviceTrust.assess()

when (assessment.level) {
    TrustLevel.LOW_RISK -> allowNormalFlow()
    TrustLevel.REVIEW -> requireAdditionalVerification()
    TrustLevel.HIGH_RISK -> sendForServerReview()
}
```

`assess()` performs file and system inspection and runs on an I/O dispatcher. Call it from a lifecycle-aware coroutine, such as `viewModelScope`.

```kotlin
viewModelScope.launch {
    val assessment = deviceTrust.assess()
    _state.update { it.copy(assessment = assessment) }
}
```

## Assessment results

`TrustAssessment` contains:

| Property | Description |
|---|---|
| `score` | Risk score from `0` to `100` |
| `level` | `LOW_RISK`, `REVIEW`, or `HIGH_RISK` |
| `signals` | Detected integrity signals ordered by weight |
| `evidence` | Versioned evidence used to produce the assessment |
| `isRootOrHookingSuspected` | Whether root or runtime-hooking signals were found |
| `isEmulatorSuspected` | Whether emulator signals were found |
| `isSystemIntegritySuspected` | Whether boot or system-integrity signals were found |

Each `TrustSignal` provides an identifier, category, weight, title, and detail value.

```kotlin
assessment.signals.forEach { signal ->
    Log.d(
        "DeviceTrust",
        "${signal.category}: ${signal.id} (${signal.weight})"
    )
}
```

Available signal categories are:

- `ROOT`
- `HOOKING`
- `EMULATOR`
- `SYSTEM_INTEGRITY`

## Custom risk policy

The default policy uses review and high-risk thresholds of `25` and `60`. Supply different thresholds when creating the client:

```kotlin
import com.xheghun.devicetrust.DefaultTrustPolicy
import com.xheghun.devicetrust.DeviceTrust

val deviceTrust = DeviceTrust.create(
    policy = DefaultTrustPolicy(
        reviewThreshold = 30,
        highRiskThreshold = 70,
    )
)
```

Applications that require a different scoring model can implement `TrustPolicy`:

```kotlin
val policy = TrustPolicy { evidence ->
    evaluateWithApplicationPolicy(evidence)
}

val deviceTrust = DeviceTrust.create(policy)
```

## Collecting evidence without local scoring

Use `collectEvidence()` to obtain the signals without applying a policy:

```kotlin
val evidence = deviceTrust.collectEvidence()
```

`DeviceEvidence` includes a `schemaVersion`, collection timestamp, and list of signals. Store or transmit the schema version together with the evidence so that server implementations can handle future schema changes.

## Monitor an assessment session

Create one monitor per session, retain it across foreground/background transitions, and collect
its flow while the app is active. Each successful scan includes the original session baseline,
changes against the last known signals, and a separate comparison against the baseline.

```kotlin
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.xheghun.devicetrust.MonitoringEvent
import com.xheghun.devicetrust.monitor
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

val monitor = deviceTrust.monitor(interval = 30.seconds)

lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        monitor.observe().collect { event ->
            when (event) {
                is MonitoringEvent.Assessed -> {
                    val update = event.update
                    showAssessment(update.current)
                    showChanges(update.changes)
                }
                is MonitoringEvent.Failed -> showAssessmentUnavailable()
            }
        }
    }
}
```

Call `monitor.refresh()` from a coroutine to collect a new assessment immediately. It returns
an `AssessmentUpdate` and publishes to the active observer. It throws on collection or policy
failure; cancellation propagates normally. A refresh does not return a cached assessment.
Use the returned value when coordinating a sensitive action with your backend.

Behavior:

- Polling starts when `observe()` is collected and stops when collection is cancelled. The
  interval is the delay after a polling scan completes, defaults to 30 seconds, and must be
  finite and at least one second. No background service or persistent storage is created.
- Only one observer can collect a monitor at a time. Share the flow in your application's
  lifecycle-owned scope if several consumers need it. Default clients serialize evidence
  collection; the monitor also serializes all of its polling and manual refreshes.
- The flow delivers the latest state and can conflate intermediate updates for slow consumers;
  it is not a durable audit log. Resubscribing can replay the last state before the immediate
  fresh scan completes. Use `sequence` to identify replay and `collectedAtEpochMillis` for
  display. The timestamp is device-controlled, not proof of freshness for a backend.
- The first successful scan establishes the baseline and has null changes. Each subsequent
  successful scan emits an update even when nothing changed. Signal order, titles, and
  collection timestamps do not create signal changes. Category, weight, and detail changes do.
- Schema version 2 adds `evaluatedSignalIds`: IDs of checks that completed. An absent signal
  counts as removed only if its check completed. Previously observed signals whose checks
  are unavailable remain `unconfirmedSignals`, including across multiple incomplete scans.
  Custom clients should populate coverage; the default empty set never confirms removal.
- `changes` compares signals with their last known observation, retaining unresolved signals;
  its score delta and risk-level transition compare the two latest successful assessments.
  `changesSinceBaseline` compares against the first successful assessment instead.
- A failed scan emits `MonitoringEvent.Failed`, retains the last successful result and baseline,
  and retries at the next polling interval. Exceptions are not exposed through the event stream.
  An unavailable individual check is partial coverage, not a whole-scan failure.
- A lower score or a removed signal is not proof of recovery. Scores describe currently
  detected evidence and may fall when checks become unavailable. Inspect coverage and
  unconfirmed signals before interpreting a decrease. The baseline may itself be incomplete.
- Create a new monitor to start a new session (for example, after logout). The monitor retains
  only baseline/latest results and last known signals. The sample retains the latest 50 successful
  scans in memory and pauses polling when its screen has no active lifecycle collectors.

Monitoring is periodic observation, not instantaneous detection: changes between scans can be
missed, and local evidence can be manipulated. Continue to authorize sensitive operations on
the server using independently verified evidence.

## Checks

DeviceTrust currently checks for:

- Common `su`, Magisk, KernelSU, APatch, and root-related files
- Suspicious mount and overlay entries
- Frida, Xposed, LSPosed, Zygisk, and Substrate mappings
- Anonymous writable and executable memory mappings
- An attached process tracer
- QEMU, goldfish, ranchu, and other emulator artifacts
- Emulator-related kernel parameters and Android build properties
- Bootloader lock state
- Android Verified Boot state
- Test-key and engineering builds
- SELinux enforcement state

## Security considerations

DeviceTrust provides local risk signals, not proof that a device is trustworthy. An attacker with control of the operating system can modify system properties, filter procfs, hook JNI calls, or patch application code.

Do not use the local result as the only authorization control for payments, account recovery, authentication, or other sensitive operations. Production fraud controls should combine these signals with server-verified Play Integrity results, account history, request context, and transaction risk.

Avoid logging or retaining detailed device evidence unless the application has an appropriate privacy and data-retention policy.

## Project structure

```text
device-trust/   Android library and native detector
sample/         Compose sample application
```

## Building from source

The project requires Android SDK 36, NDK 27.0.12077973 or compatible, CMake 3.22.1, and JDK 17 or 21.

Run the unit tests and build the sample application:

```bash
./gradlew :device-trust:test :sample:assembleDebug
```
## License

DeviceTrust is available under the Apache License 2.0. See [LICENSE](LICENSE).
