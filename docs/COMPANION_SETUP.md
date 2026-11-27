# CoreLink Companion

The companion service is a private-LAN TLS 1.3 service. Start it in the Windows
app, scan the generated QR code in CoreLink Android, then explicitly approve the
named phone from the Windows UI. Do not use an older build of the companion:
the six-digit protocol was removed because it was vulnerable to offline guessing
and unauthenticated disconnects.

Android mirroring through ADB and scrcpy is separate and remains available.
The companion currently supports bounded authenticated pointer, click, scroll
and ASCII shortcut messages. It does not capture or stream the Windows desktop.

Windows input is subject to integrity levels and cannot bypass the secure desktop,
lock screen, or UAC restrictions. A future local-network service may require a
user-managed Windows Firewall rule for the trusted network; CoreLink must not
silently create one.
