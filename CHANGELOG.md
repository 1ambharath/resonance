# Changelog

All notable changes to **Resonance** will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
