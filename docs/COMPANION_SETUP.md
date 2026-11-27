# CoreLink Companion

The companion service is unavailable pending a secure pairing implementation.
Do not use an older build of the companion service: its six-digit pairing proof
can be guessed offline, and its disconnect request is not authenticated. Android's
disconnect button only clears the phone's local key. The old listener binds all
IPv4 interfaces and filters peers after accepting connections.

Android mirroring through ADB and scrcpy is separate and remains available.
The companion's touchpad and keyboard are not ready for use. Secure pairing,
authenticated session commands, resource limits, lifecycle handling, and
end-to-end control tests are required before the service can be enabled.

Windows input is subject to integrity levels and cannot bypass the secure desktop,
lock screen, or UAC restrictions. A future local-network service may require a
user-managed Windows Firewall rule for the trusted network; CoreLink must not
silently create one.
