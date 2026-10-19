# Contributing to Resonance

Thank you for your interest in contributing to **Resonance**! We welcome bug reports, feature requests, documentation improvements, architectural feedback, and pull requests.

## Code of Conduct

By participating in this project, you agree to abide by our [Code of Conduct](CODE_OF_CONDUCT.md).

---

## How to Contribute

### 1. Reporting Bugs
Before filing a bug report:
- Check existing issues in the [Issue Tracker](https://github.com/bharath9-r/resonance/issues) to avoid duplicates.
- Ensure the issue is reproducible on a supported setup (Android 10+ / API 29+).
- Review [docs/limitations.md](docs/limitations.md) to verify whether the behavior is an intentional OS policy restriction (such as Widevine DRM audio capture blocking or app-level capture opt-out).

When submitting a bug report:
- Use the [Bug Report Template](.github/ISSUE_TEMPLATE/bug_report.md).
- Include device models, Android versions, audio playback source app (e.g. VLC, YouTube, MX Player), Wi-Fi topology (same router, mobile hotspot), and diagnostic metrics from the app screen.

### 2. Suggesting Features
- Open a feature request via the [Feature Request Template](.github/ISSUE_TEMPLATE/feature_request.md).
- Clearly explain the use case, why existing features do not suffice, and any architectural trade-offs (e.g., latency vs. packet loss resilience).

### 3. Submitting Pull Requests
1. **Fork and Branch**: Fork the repository and create a feature branch off `main`:
   ```bash
   git checkout -b feature/my-new-feature
   ```
2. **Follow Code Conventions**:
   - Write clean, idiomatic Kotlin.
   - Maintain the Neobrutalist visual identity where UI changes are made.
   - Keep network transport lightweight and non-blocking. Audio threads must run at maximum priority without memory allocation in tight audio loops.
3. **Add Tests**:
   - Write unit tests under `app/src/test/java/com/br/resonance/` for pure logic and protocol changes.
   - Run the full unit test suite before opening a PR:
     ```bash
     ./gradlew testDebugUnitTest
     ```
4. **Run Static Checks & Build Verification**:
   ```bash
   ./gradlew assembleDebug assembleRelease
   ```
5. **Open Pull Request**: Use the [Pull Request Template](.github/PULL_REQUEST_TEMPLATE.md), clearly detailing what changed, verified test outcomes, and any breaking changes.

---

## Local Development Setup

### Requirements
- **JDK 21**
- **Android SDK** with platform-tools and build-tools 35.0.0+
- **Android Studio Ladybug (or newer)** / CLI with Gradle 8.13+
- Target testing device or Android emulator with API 29 or higher.

### Building from Command Line
```bash
# Set SDK path if not already in environment
echo "sdk.dir=/path/to/android/sdk" > local.properties

# Run unit tests
./gradlew testDebugUnitTest

# Assemble debug APK
./gradlew assembleDebug

# Assemble release APK
./gradlew assembleRelease
```

---

## Audio Pipeline Guidelines
- **Zero Allocations in Playout Loops**: The `AudioRecord` reading loop and `AudioTrack` write loop run on dedicated high-priority threads. Avoid object allocations in the hot path to eliminate GC pauses.
- **Protocol Integrity**: Any changes to `AudioPacket` or `ReceivedAudioPacket` must bump or negotiate `PROTOCOL_VERSION` to preserve backward compatibility.
- **Diagnostics Accuracy**: Diagnostic counters must reflect exact bytes and frame counts. Do not fake metrics.
