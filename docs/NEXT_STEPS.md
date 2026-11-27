# CoreLink: next steps

The goal is an opt-in Windows desktop stream to the paired Android companion. The existing Android-to-Windows scrcpy flow remains independent. Complete each milestone and its checks before enabling the next one for regular use.

## 1. Finish local Windows capture

- Use the Windows source picker on explicit user request; show the selected source and a clear stop control.
- Handle cancellation, source closure, resizing, app shutdown, capture errors, and Windows sleep/resume without silently restarting capture.
- Verify on Windows with a window and a monitor: start, cancel, stop, close the source, resize, sleep/resume, and restart the app. Confirm no capture starts on launch or phone reconnect.
- Exit criterion: actual frames arrive locally, state reflects reality throughout, and all resources stop promptly. No video leaves the PC.

## 2. Encode locally

- Connect captured D3D11 frames to a Media Foundation H.264 encoder. Prefer available hardware encoders; explicitly report software fallback and errors.
- Make resolution, frame rate, and bitrate configurable within bounded limits. Handle resize and encoder reinitialization.
- Verify playable local output, SPS/PPS and keyframes, cleanup, CPU usage, and latency. Test available GPU hardware; record untested vendors.

## 3. Authenticated media transport

- Define a versioned media protocol and bind each stream to an approved, live companion session. Use pinned TLS 1.3 QUIC, fresh session keys, bounded buffers, and a private-LAN endpoint.
- Define codec configuration, timestamps, keyframe requests, limits, disconnect, expiry, and failure behavior. Stop capture and media when authorization ends.
- Test authentication failures, replay, revoked devices, disconnects, loss, reconnect, and resource limits before connecting real video.

## 4. Android video playback

- Add a Surface-backed MediaCodec decoder and a screen for the authorized stream. Negotiate supported format and sensible initial settings.
- Handle codec configuration, keyframes, lifecycle, rotation, reconnect, and immediate teardown on disconnect. Keep the existing touchpad usable.
- Test with a real Android device and measure startup, latency, frame drops, battery, thermal behavior, and memory.

## 5. Adaptation and release readiness

- Add congestion-aware bitrate adaptation and bounded buffering, then exercise poor Wi-Fi, roaming, sleep, and recovery.
- Test end to end on representative Windows GPUs and Samsung hardware, document prerequisites and known limitations, and verify installer and Android builds.
- Enable streaming only after the consent, security, stability, and hardware checks above pass.

## Immediate follow-up

Finish and manually verify milestone 1 first. The current frame-count prototype is not a video stream, and a successful build alone does not establish capture or sleep/resume behavior.
