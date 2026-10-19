# Security Policy

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 1.0.x   | :white_check_mark: |
| < 1.0   | :x:                |

---

## Reporting a Vulnerability

If you discover a security vulnerability in Resonance, please do not file a public issue. Instead, report it privately to the project maintainers via GitHub Security Advisories:

👉 **[Report a Security Vulnerability](https://github.com/1ambharath/resonance/security/advisories/new)**

Include the following in your report:
- A description of the vulnerability.
- Steps to reproduce or proof-of-concept code.
- Potential impact and affected configurations.

You should receive an acknowledgment within 48 hours.

---

## Architecture & Threat Model Notes

1. **Local-Only UDP Protocol**:
   - Resonance streams unencrypted raw PCM audio over local UDP (port 50005) directly between devices on the same Wi-Fi network.
   - It is designed exclusively for private local-area networks (home Wi-Fi or mobile hotspot).
   - Resonance never establishes connections to external servers for audio streaming.

2. **Network Service Discovery & Beacons**:
   - Device discovery broadcasts device name and IP over local mDNS (`_dualaudio._udp.`) and UDP beacon (port 50006).
   - No sensitive information (passwords, contacts, credentials) is ever broadcast or collected.

3. **In-App Update Verification**:
   - Update checks communicate solely via HTTPS with the public GitHub REST API (`https://api.github.com/repos/1ambharath/resonance/releases/latest`).
   - Downloads are redirected to GitHub Releases, and the Android Package Manager enforces cryptographic signature matching before allowing any update to install over an existing installation.
