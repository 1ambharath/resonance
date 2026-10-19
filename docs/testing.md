# Resonance Verification & Testing Guide

This guide describes how to run automated tests, build APK artifacts, verify application behavior on emulators, and conduct physical dual-device audio tests.

---

## 1. Automated Unit Testing

Resonance includes automated unit tests under `app/src/test/java/com/br/resonance/`:
- `AudioPipelineTest`: Verifies 40-byte binary packet encoding/decoding, legacy raw PCM decoding, silence detection, RMS dBFS math, and `JitterDelayBuffer` dynamic offset adjustments.
- `AppUpdateManagerTest`: Verifies GitHub Releases API payload parsing, semantic version comparisons, draft/prerelease filtering, and update detection logic.

### Running Unit Tests
```bash
./gradlew testDebugUnitTest
```

Expected output:
```
BUILD SUCCESSFUL
24 actionable tasks: 2 executed, 22 up-to-date
```
Test results are output to `app/build/reports/tests/testDebugUnitTest/index.html`.

---

## 2. Building Release & Debug APKs

### Debug APK
```bash
./gradlew assembleDebug
```
Output artifact: `app/build/outputs/apk/debug/app-debug.apk` (~4.06 MB)

### Release APK
```bash
./gradlew assembleRelease
```
Output artifact: `app/build/outputs/apk/release/app-release.apk` (~3.19 MB)

### Inspecting Signatures & Checksums
```bash
# Generate SHA-256 Checksum (PowerShell)
Get-FileHash -Algorithm SHA256 app\build\outputs\apk\release\app-release.apk

# Verify APK Signature (Android build-tools)
apksigner verify --verbose app\build\outputs\apk\release\app-release.apk
```

---

## 3. Emulator & Single-Device Smoke Testing

### Prerequisites
- Android SDK installed (`platform-tools/adb` and `emulator`).
- Android Virtual Device (AVD) with API 29 or higher.

### Installation and Launch
```bash
# Install onto connected device or running emulator
adb install -r app/build/outputs/apk/release/app-release.apk

# Launch Resonance
adb shell am start -n com.br.resonance/.ResonanceActivity
```

### Capturing Screenshots
```bash
adb shell screencap -p /sdcard/screen.png
adb pull /sdcard/screen.png docs/screenshots/my_test_screen.png
```

---

## 4. Dual-Device Audio Sharing Verification Protocol

To verify audio sharing between two physical devices:

### Step 1: Network Setup
- Connect both devices to the same 5 GHz Wi-Fi router, **OR**
- Enable **Mobile Hotspot** on the Sender device and connect the Receiver device to that hotspot.

### Step 2: Receiver Preparation
1. Open Resonance on the receiving device.
2. Select **RECEIVER MODE**.
3. Tap **START LISTENING**.
4. Observe the status change to `Listening on UDP port 50005 (Background active)`.
5. Connect your Bluetooth headset to the Receiver.

### Step 3: Sender Preparation
1. Open Resonance on the sending device.
2. Select **SENDER MODE**.
3. Verify that the Receiver device appears automatically in the receiver slots via auto-discovery (or enter the Receiver's IP manually and tap **+ ADD**).
4. Tap **START AUDIO SHARING**.
5. When prompted with the Android system consent dialog ("Resonance will capture audio..."), tap **Start now**.
6. Connect your Bluetooth headset to the Sender.

### Step 4: Media Playback & Calibration
1. Open a media player that allows playback capture (e.g., VLC, MX Player, or a YouTube video in browser).
2. Play video/audio.
3. Check the Sender's **Transmission Diagnostics**:
   - Ensure the badge displays `Non-Silent Audio Detected` (green).
   - Ensure RMS dBFS shows active levels (e.g., `-18 dBFS`).
   - Ensure `Packets Transmitted` is continuously incrementing (~50 packets/sec).
4. Listen to both headsets:
   - If the Receiver audio lags slightly behind the Sender, tap the negative delay nudges (`-10ms`, `-1ms`).
   - If the Receiver audio leads the Sender, use the positive delay slider/nudges (`+10ms`, `+50ms`).
   - Calibrate until dialogue in both headsets sounds seamless without echo.
