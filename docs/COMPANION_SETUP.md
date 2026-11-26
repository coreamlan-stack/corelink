# CoreLink Companion setup

This is a local-network remote input companion. It is separate from the existing ADB/scrcpy Samsung mirroring workflow.

1. Build/install `apps/android/app/build/outputs/apk/debug/app-debug.apk` on the Samsung after an Android build succeeds. Android 8+ is required.
2. Connect both devices to the same trusted Wi-Fi. Do not use a guest network, hotspot with client isolation, or the public internet.
3. Open CoreLink on Windows, scroll to **Phone touchpad companion**, and select **Start companion service**. CoreLink shows its local port (49876).
4. Find the Windows machine's private IPv4 address with `ipconfig` (for example, `192.168.1.10`). Enter it in the Companion app, enter a new six-digit code, and request pairing.
5. CoreLink displays the device request. Re-enter the same six-digit code in CoreLink and select **Approve**. Pairing expires after 90 seconds.
6. Use the touchpad, buttons, scrolling and keyboard field. Select **Disconnect now** on Android or **Stop & disconnect all** on Windows to terminate immediately.

## Security boundaries

- The listener only accepts RFC1918, loopback, link-local IPv4 and local IPv6 peers; it is not an internet service.
- Pairing uses a code-derived proof, requires a Windows approval, and establishes an AES-256-GCM authenticated input session. Pairing/session secrets are memory-only: restarting CoreLink requires a new approval.
- Each input packet is authenticated, validated for bounds and expires after 15 minutes. Public-address, malformed and unauthenticated packets are rejected.
- Windows input uses the normal Windows input path; it does not bypass the secure desktop or UAC.

## Discovery note

The Android client currently supports manual private-IP connection because this first companion transport does not yet advertise an mDNS service. It does not show invented PCs. mDNS/NSD discovery is the next transport increment, not a simulated UI feature.
