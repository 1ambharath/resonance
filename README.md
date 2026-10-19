# Resonance

<p align="center">
  <strong>Low-Latency Multi-Device Audio Sharing for Android</strong><br>
  <em>"SAME SOUND. SAME SECOND. Together forever. ❤️"</em>
</p>

---

## 📖 Overview

**Resonance** is an open-source, low-latency audio sharing application built for Android. It enables an Android device (phone or tablet) playing media to capture and stream its playback audio in real time over local Wi-Fi to up to five receiver devices simultaneously.

### Why Resonance Was Created
Watching a movie, series, or video together in public spaces (trains, flights, cafes, study rooms) often requires sharing one pair of earbuds or carrying bulky hardware audio splitters. Bluetooth multi-sharing is notoriously restricted on many Android devices and vendor-locked. Resonance eliminates this barrier with a pure software solution: one device plays the video and streams the audio across the local Wi-Fi network to companion devices running Resonance in Receiver mode with sub-50ms synchronization.

Resonance is distributed independently and exclusively through **GitHub Releases**—free of logins, tracking, or Google Play Store dependencies.

---

## ✨ Features

- **Unified Sender & Receiver**: Switch between audio broadcasting (Sender Mode) and listening (Receiver Mode) with a single tap.
- **Auto-Discovery via mDNS**: Receivers on the same local Wi-Fi network are automatically discovered and displayed without manual configuration.
- **Manual IP Fallback**: Direct IP entry ensures connectivity even on complex or restricted Wi-Fi networks.
- **Multi-Device Broadcast**: Stream to up to 5 concurrent receivers in real time.
- **Real-Time Audio Diagnostics**: Live RMS dBFS level monitoring, silence detection, frame counters, and throughput bandwidth meters.
- **Built-in APK Sharing**: Share the installed Resonance APK directly to nearby friends via Bluetooth or Quick Share with a single tap.
- **GitHub-Based Updates**: In-app notifications when a newer stable version is published on GitHub Releases, directing users to download the update with zero friction.
- **Cream Neobrutalist UI**: Handcrafted, tactile design system with bold outlines, subtle shadows, and responsive micro-interactions.

---

## ⚠️ Limitations & Prerequisites

1. **Local Wi-Fi Network**: Both sender and receiver devices must be connected to the same Wi-Fi network that allows device-to-device communication (disable Client/AP Isolation on the router).
2. **Audio Playback Capture**: Android's `AudioPlaybackCapture` captures standard media playback from apps that allow it (e.g., VLC, MX Player, Chrome, YouTube). Content protected by DRM (Widevine L1 hardware DRM) cannot be captured per Android OS security policies.
3. **Android Version**: Requires Android 10 (API 29) or higher for audio capture support.
4. **Network Jitter**: For optimal latency (<50ms), a 5 GHz Wi-Fi band or mobile hotspot hosted directly from the sender device is recommended.

---

## 📥 Installation

Resonance is distributed exclusively as an APK via GitHub Releases:

1. Navigate to the official releases page:  
   👉 **[Download Latest Resonance APK](https://github.com/bharath9-r/resonance/releases/latest)**
2. Download the `Resonance-vX.Y.Z.apk` file.
3. When prompted by Android, tap the downloaded file to install.
4. If installing an APK from your browser for the first time:
   - Go to **Settings > Apps > Special app access > Install unknown apps**.
   - Select your browser (e.g., Chrome) and enable **Allow from this source**.
5. Open Resonance and start sharing audio!

---

## 🔄 How In-App Updates Work

Resonance implements a lightweight, login-free update notification system:

1. **Automatic Startup Check**: When the app launches, Resonance checks the public GitHub Releases API asynchronously in the background. It never blocks startup or delays streaming.
2. **Manual Check**: Tap **🔄 CHECK UPDATES** under the **ABOUT & UPDATES** card on the main screen anytime.
3. **Smart Caching**: API responses are cached locally for 6 hours to prevent hitting GitHub unauthenticated rate limits. Manual checks bypass the cache.
4. **Update Available Dialog**: When a newer version is detected:
   - Displays the installed version vs. the latest available version.
   - Shows formatted release notes directly inside the dialog.
   - Tapping **UPDATE NOW** opens the release page in your default browser.
   - Tapping **LATER** dismisses the prompt for that entire session.
5. **Native Android Installation**: The user downloads the APK from GitHub and confirms installation through Android's standard package installer. Existing app data, preferences, and receiver lists are preserved.

---

## 🛠️ Maintainer Guide: Versioning, Signing & Releases

### 1. Release Signing Identity
> [!IMPORTANT]
> Always use the **exact same signing keystore** for all releases. If the signing certificate changes, Android will refuse to install updates over existing installations (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`).

To create or configure your release keystore:
1. Generate a keystore (if not already done):
   ```bash
   keytool -genkey -v -keystore resonance-release.jks -alias resonance -keyalg RSA -keysize 2048 -validity 10000
   ```
2. Store the keystore securely. **Never commit `.jks`, `.keystore`, or signing passwords to Git.**
3. Configure `gradle.properties` (or CI environment variables):
   ```properties
   RELEASE_STORE_FILE=/path/to/resonance-release.jks
   RELEASE_STORE_PASSWORD=your_keystore_password
   RELEASE_KEY_ALIAS=resonance
   RELEASE_KEY_PASSWORD=your_key_password
   ```

### 2. Versioning Conventions
Update `app/build.gradle.kts` for every release:
- **`versionCode`** *(Integer)*: Increment by 1 for every published release (e.g., `1`, `2`, `3`). This is the primary value used by Android and `AppUpdateManager` to detect updates.
- **`versionName`** *(String)*: Follow [Semantic Versioning](https://semver.org) (`MAJOR.MINOR.PATCH`, e.g., `1.0.0`, `1.0.1`, `1.1.0`).

### 3. Step-by-Step GitHub Release Workflow

1. **Prepare the codebase**:
   - Increment `versionCode` and update `versionName` in `app/build.gradle.kts`.
   - Update `UpdateConfig.kt` if the repository owner or name changes.
   - Run unit tests:
     ```bash
     ./gradlew testDebugUnitTest
     ```
2. **Build and sign the release APK**:
   ```bash
   ./gradlew assembleRelease
   ```
   The signed APK will be generated at:
   `app/build/outputs/apk/release/app-release.apk`
   Rename it descriptively (e.g., `Resonance-v1.0.1.apk`).

3. **Tag the commit**:
   ```bash
   git tag -a v1.0.1 -m "Release v1.0.1"
   git push origin v1.0.1
   ```

4. **Create GitHub Release**:
   - Go to your repository's **Releases** page (`https://github.com/<owner>/resonance/releases/new`).
   - Select tag `v1.0.1`.
   - Title the release: `Resonance v1.0.1`.
   - In the release description, include the `versionCode` for instant parsing:
     ```markdown
     versionCode: 2

     ### Changes in this release
     - Ultra-low latency stereo audio pipeline optimizations
     - Improved Wi-Fi reconnection handling
     - Fixed occasional buffer underrun on Android 14
     ```
   - Attach the signed APK (`Resonance-v1.0.1.apk`).
   - Ensure **Set as a pre-release** and **Set as a draft** are **unchecked**.
   - Click **Publish release**.

5. **Verify**:
   - Existing installations of Resonance will automatically detect the update on next launch (or when tapping **🔄 CHECK UPDATES**) and present the update dialog linking directly to the new release.

---

## 🤖 Automated CI/CD (GitHub Actions)

Resonance includes an automated GitHub Actions workflow in [`.github/workflows/release.yml`](.github/workflows/release.yml).

Whenever a tag starting with `v*` is pushed:
1. Sets up JDK 17 and Android SDK.
2. Runs unit tests (`./gradlew testDebugUnitTest`).
3. Assembles the release APK.
4. Creates a GitHub Release and attaches the APK automatically.

To enable automated signing in CI:
- Add secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD` in your GitHub repository's **Settings > Secrets and variables > Actions**.

---

## 🩺 Troubleshooting

### "App not installed as package appears to be invalid" / Incompatible Signature
- **Cause**: The newly downloaded APK was signed with a different key than the installed version (e.g., upgrading from a debug build to a release build).
- **Fix**: Uninstall the existing debug version first, then install the release APK. All future release APKs signed with the same key will update seamlessly without data loss.

### Update check shows "Could not connect to GitHub"
- **Cause**: The device has no active internet connection, or GitHub's unauthenticated API rate limit (60 requests/hour per IP) has been reached.
- **Fix**: Check Wi-Fi/mobile data. Resonance caches responses for 6 hours to minimize API usage. The app remains fully functional even when offline.

### "No active receivers found"
- Ensure both devices are connected to the same Wi-Fi network.
- Disable AP/Client Isolation in your Wi-Fi router settings, or create a mobile hotspot on the sender device and connect the receiver to it.
- Use the **Manual IP** input if your router blocks UDP multicast/mDNS.

---

## 📄 License

Resonance is free and open-source software distributed under the Apache 2.0 License.
