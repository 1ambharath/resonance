# Resonance Limitations & Known Issues

Resonance prioritizes technical transparency. This document honestly outlines architectural boundaries, operating system constraints, and practical trade-offs.

---

## 1. Android Audio Playback Capture Limitations

### Granting Permission Does Not Guarantee Audio Capture
Granting the Android `MediaProjection` consent dialog allows the application to attempt capture, but Android enforces strict OS-level privacy and playback policies at runtime:

1. **Explicit Opt-Out (`setAllowAudioPlaybackCapture(false)`)**:
   Under Android 10+ (API 29+), any app developer can explicitly prohibit third-party apps from capturing their audio by setting `AudioAttributes.setAllowedCapturePolicy(ALLOW_CAPTURE_BY_NONE)` or specifying `android:allowAudioPlaybackCapture="false"` in their manifest. If an app sets this, the capture stream produces silence even while audio is actively playing from the device speakers.

2. **Digital Rights Management (DRM / Widevine L1)**:
   Content protected by hardware DRM (such as Netflix, Amazon Prime Video, Disney+, Apple TV+, or paid movie rentals on YouTube) routes audio through hardware-protected pipelines that completely bypass user-space capture. Resonance **cannot capture DRM-protected streams** due to Android OS architecture.

3. **Compatible Applications**:
   Audio capture works reliably with applications that permit standard media capture, including:
   - **VLC for Android**
   - **MX Player** (with standard software/hardware audio decoders)
   - **YouTube** (free standard media playback)
   - **Chromium / Web Browsers** (HTML5 video and audio elements)
   - **Local media players** and open podcast apps

---

## 2. Synchronization & Latency Realities

### The Delay Control Can Only Add Delay, Not Remove It
Resonance provides a continuous manual delay control on the receiver side. It is critical to understand the physics of relative audio delay:
- **Relative Offset Only**: If the Receiver's audio is playing **earlier** than the Sender's Bluetooth headset (e.g., due to higher internal latency on the Sender's Bluetooth codec), adding delay on the Receiver can bring the two into exact phase synchronization.
- **Minimum Latency Floor**: If the Receiver's audio is playing **later** than the Sender's audio, reducing the Receiver's delay can only reduce it down to the baseline hardware latency floor (network transit time + minimum hardware buffer ~20–40 ms). The Receiver **cannot play audio before it was captured and transmitted by the Sender**.
- **Bluetooth Codec Variance**: Different Bluetooth headsets introduce drastically different hardware latencies (e.g. 40 ms for aptX Low Latency vs. 200–300 ms for standard SBC/AAC). Aligning two distinct headsets requires manual calibration via the slider.

---

## 3. Network Transport & Wi-Fi Reliability

### Unencrypted UDP without Retransmission
- **Protocol Choice**: Resonance uses raw UDP datagrams without ACKs or packet retransmissions. This is an intentional engineering trade-off: in real-time audio synchronization, a late packet is useless and retransmissions cause catastrophic cascading delays.
- **Packet Drop Behavior**: If Wi-Fi packets drop due to interference or range, the receiver's jitter buffer will briefly starve, causing an audible click or momentary dropout.
- **Public Wi-Fi & AP/Client Isolation**: Many public Wi-Fi networks (trains, hotels, universities, cafes) enable **Client Isolation (AP Isolation)**, which blocks peer-to-peer UDP and mDNS communication between connected devices.
  > [!TIP]
  > **Recommended Setup**: In public locations, create an **Android Mobile Hotspot** on the Sender device and connect the Receiver device directly to it. This eliminates client isolation and provides jitter-free 5 GHz transmission.

---

## 4. Audio Quality & Format Constraints

- **Mono Channel Downmix**: Audio is captured and transmitted in **Mono 16-bit PCM at 24,000 Hz**. While voice, dialogue, and general video audio are crisp and highly intelligible, full stereo separation is not currently preserved in transit.
- **Android Resampling**: Android typically mixes internal audio at 48,000 Hz. The capture API downsamples audio to the requested 24,000 Hz capture format, which may introduce minor filtering artifacts compared to uncompressed studio master audio.

---

## 5. Hardware Verification Status

The project was originally motivated by an exploration between a **OnePlus Pad 2** (Sender) and a **Nothing Phone (3)** (Receiver), pairing **Nothing Ear (2)** and **Sony WH-CH520** headsets.

- **Verified**:
  - The software builds, passes 13 automated unit tests, and packages into valid signed release APKs.
  - The app launches and runs natively on Android emulators (API 36).
  - Playout buffering, byte trimming, binary packet encoding, and fallback decoding logic are computationally verified.
- **Unverified Hardware Claims**:
  - Specific dual-headset hardware combinations (e.g. exact millisecond offset values for Sony WH-CH520 vs. Nothing Ear (2)) depend heavily on firmware, OS vendor optimizations, and environmental RF conditions and have not been certified under laboratory conditions.

---

## 6. Feature Classification Matrix

| Feature | Status | Notes |
| :--- | :--- | :--- |
| Single-APK Mode Toggle | **Implemented and Tested** | Runtime switch between Sender & Receiver |
| Audio Playback Capture | **Implemented and Tested** | Validated on Android 10+ for non-DRM media |
| 1 ms Jitter Buffer Playout | **Implemented and Tested** | Unit tested and verified in JVM & Android runtime |
| UDP Multi-Receiver Stream | **Implemented but Not Tested (Multi-device)** | Code supports 5 targets; tested on single local loops |
| mDNS / Beacon Discovery | **Implemented but Not Tested (Multi-device)** | Dual-advertiser and scanner implemented |
| In-App Update Engine | **Implemented and Tested** | Validated with unit tests and GitHub API schemas |
| In-App APK Sharing | **Implemented and Tested** | Android FileProvider and share chooser |
| Stereo Audio Support | **Planned** | Requires increasing payload bandwidth |
| Opus Compression | **Planned** | Would reduce bandwidth for noisy 2.4GHz channels |
| DRM / Widevine L1 Capture | **Unsupported** | Strictly prohibited by Android OS security policy |
