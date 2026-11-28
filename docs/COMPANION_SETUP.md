# CoreLink Companion

The companion service is a private-LAN TLS 1.3 service. Start CoreLink on Windows
and scan its one-time QR code in CoreLink Android to connect. Anyone who scans
the QR can connect, so keep it private. Do not use an older build of the companion:
the six-digit protocol was removed because it was vulnerable to offline guessing
and unauthenticated disconnects.

After pairing, the PC keeps its identity and trusted devices protected by
Windows user-bound encryption. The phone keeps its PC credential in encrypted
storage and retries while CoreLink is open. Both devices need a reachable
private network; LAN discovery can find the PC after its address changes.
The dashboard opens first; screen capture begins only after selecting View PC.
Revoking a phone on Windows removes its saved access.

Android mirroring through ADB and scrcpy is separate and remains available.
The companion currently supports bounded authenticated pointer, click, scroll
and text messages. The experimental screen preview sends compressed frames over
an authenticated, certificate-pinned TLS connection. The primary display is
shared after a phone connects. While connected, the phone's volume buttons
adjust Windows volume; PC audio stays on the PC.

Windows input is subject to integrity levels and cannot bypass the secure desktop,
lock screen, or UAC restrictions. A future local-network service may require a
user-managed Windows Firewall rule for the trusted network; CoreLink must not
silently create one.
