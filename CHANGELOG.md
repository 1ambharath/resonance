# Changelog

All notable changes to **Resonance** will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.2.2] - 2026-10-21

### Fixed
- **Late-Starting Audio Capture Initialization**: Fixed an issue where starting audio sharing before playing music caused capture to miss the media stream. `CaptureService` now spins up a lightweight, zero-amplitude keep-alive `AudioTrack` with `USAGE_MEDIA` to actively warm up Android's `AudioPolicyManager` submix routing prior to `AudioRecord` construction. Media started before OR after audio sharing begins is now captured and streamed immediately without restarting sharing.
- **System Playback Watchdog**: Registered an `AudioManager.AudioPlaybackCallback` to monitor global audio playback changes and ensure submix integrity whenever new media/game sessions begin.
- **Web App Negative Delay Slider & Fine Tuning**: Expanded the Web receiver delay slider to `-500 ms to +500 ms` with 1 ms resolution, matching the Android app's precision sync controls. Added `-1 ms` and `+1 ms` micro-nudge buttons, active cushion feedback (`Playout cushion: 80 ms (0 ms sync offset)`), and dynamic instant buffer trimming when decreasing delay.
- **Web Audio Silence Re-anchoring**: Prevented empty `AudioBufferSourceNode` accumulation during prolonged silence on the Web receiver, ensuring that the playback cursor snaps cleanly to the calibrated target delay the instant active audio arrives.

---

## [1.2.1] - 2026-10-21

### Fixed
- **iOS/iPadOS AudioSession & Silent Mode Override (Fixes #3)**: Added HTML5 `<audio>` silent loop unlocker to force iOS WebKit from `Ambient` to `Playback` audio session category, unmuting Web Audio output through speakers and USB-C headphones regardless of the iPad Silent Mode switch.
- **Web Audio Hardware Resampling (Fixes #3)**: Implemented real-time linear interpolation from 24,000 Hz stream to native hardware rate (e.g. 48,000 Hz) before creating `AudioBuffer`, eliminating WebKit internal buffer drops and silent rendering.
- **Audio Output Verification & Volume Control**: Added master volume slider and a synthetic "Test Audio Output" chime button for instant confirmation of speaker/headphone routing.
- **Microphone Secure Context Pre-flight (Fixes #3)**: Added `isSecureContext` and `navigator.mediaDevices` validation to prevent uncaught `TypeError: undefined is not an object` on local HTTP addresses, providing a clear HTTPS context guidance card.

---

## [1.2.0] - 2026-10-20

### Added
- **Zero-Install iOS Safari & Web Receiver**: Stream synchronized audio to iPhone, iPad, Mac, and PC companion devices via standard web browsers with no App Store installation or sideloading required.
- **Embedded Web Relay Server (`WebRelayServer`)**: Android `CaptureService` embeds a microscopic HTTP and WebSocket server running on port `8080`, serving the web client and streaming binary 24 kHz 16-bit PCM frames offline and over personal hotspots.
- **Dynamic Neobrutalist QR Code Pairing**: Android Sender displays a high-contrast QR code modal via ZXing (`QrCodeGenerator` and `IosConnectDialogHelper`), allowing iOS users to scan and connect in seconds with the native Camera app.
- **Dedicated Android Quick Actions**: Added `[ 📱 iOS / Web ]` button in the Sender discovery header and `📱 Connect iOS / Web` in the navigation drawer.
- **Cream Neobrutalist Web Client**: Fully responsive web app packaged inside the APK (`assets/web/`) styled with warm cream `#F5F0E8` canvas, 2px solid `#1A1A1A` borders, 4px hard drop shadows, and Fraunces editorial typography.
- **Web Delay Calibration & Audio Meter**: Real-time RMS dBFS level meter and 0–500 ms playout delay calibration slider with fine-tuning step buttons (`-50ms`, `-10ms`, `0ms`, `+10ms`, `+50ms`).
- **Screen Wake Lock & OLED Cinema Mode**: Automatic `navigator.wakeLock` integration to prevent iOS Safari from sleeping, plus a full-screen pitch black (`#000000`) low-power mode to protect OLED battery life.
- **Microphone Broadcast (iOS to Android)**: Allows an iPhone user to broadcast live microphone audio over WebSocket to Android speakers.

---

## [1.1.0] - 2026-10-20

### Changed
- **Minimal Sidebar Menu**: Replaced oversized cards in the navigation drawer with sleek, tactile individual menu items for "Share Working APK", "Check for Updates", and "GitHub Repository".
- **Compact Drawer Header**: Added inline version pill badge (`v1.1.0`) and cleaner typography layout.

### Fixed
- **Release APK Installation & Signing**: Fixed "App not installed" error on Android devices by establishing a persistent project release keystore with long-term certificate validity.
- **In-Place App Updates**: Enabled seamless updates between releases without signature mismatch conflicts (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`).
- **CI Artifact Packaging**: Corrected GitHub Actions release workflow artifact resolution to guarantee signed release APKs are always packaged.

---

## [1.0.0] - 2026-10-20

### Added
- **Unified Single-APK Architecture**: Swappable Sender and Receiver operational modes within a single lightweight application (`com.br.resonance`).
- **Low-Latency System Audio Capture**: Android `MediaProjection` and `AudioPlaybackCaptureConfiguration` integration capturing non-DRM media audio at 24,000 Hz 16-bit mono.
- **Custom UDP Transport Protocol**: Lightweight 40-byte little-endian binary header with monotonic sequence numbering, session identifiers, timestamps, and raw PCM payloads.
- **Multi-Device Broadcasting**: Sender can stream concurrent audio to up to 5 receiver devices simultaneously.
- **Automatic Device Discovery**: Hybrid discovery using Android Network Service Discovery (mDNS `_dualaudio._udp.`) and UDP broadcast beacons on port 50006 with ping/pong handshakes.
- **Manual IP Fallback**: Direct IP entry with persistent device memory in SharedPreferences for networks with multicast filtering.
- **Byte-Level Linear Jitter Delay Buffer**: Playout buffer with exact 1-millisecond delay tuning resolution, instant byte trimming for negative adjustments, and pre-roll cushion management.
- **Receiver Foreground Resilience**: Dedicated `PlaybackService` holding `PARTIAL_WAKE_LOCK` and `WIFI_MODE_FULL_HIGH_PERF` to ensure continuous background audio when the screen turns off.
- **Live Transmission & Playout Diagnostics**: Real-time meters for RMS dBFS levels, peak amplitude percentages, silence detection, frame counters, throughput, and buffer depths.
- **In-App APK Sharing**: One-tap APK sharing to companion devices via Android Quick Share / Bluetooth using `ApkFileProvider`.
- **GitHub In-App Update Engine**: Asynchronous, rate-limited check against GitHub Releases API with native install dialog.
- **Tactile Cream Neobrutalist UI**: Handcrafted custom theme with bold 2px ink-black outlines, hard offset drop shadows, and responsive micro-nudges.
- **Unit Test Suite**: 13 unit tests covering update parsing, version comparisons, binary packet encode/decode, legacy raw PCM fallback, audio analyzer RMS metrics, and jitter buffer mechanics.

### Known Limitations
- Android system policies strictly block audio capture from apps protected by Widevine L1 DRM (e.g. Netflix) or apps that opt out via `setAllowAudioPlaybackCapture(false)`.
- UDP transport is unencrypted and does not perform packet retransmissions; dropped packets on noisy Wi-Fi are discarded to preserve low latency.
- Playout delay adjustments can only delay the receiver; they cannot make the receiver play earlier than the sender's minimum hardware latency.
- Audio is captured in mono at 24 kHz to ensure packet sizes stay well below network MTU limits without compression overhead.
