# Windows desktop streaming implementation plan

Video streaming is intentionally not enabled by the touchpad service. Screen capture must remain opt-in per session and use a separate, authenticated media channel.

## Architecture

1. The Windows UI requests capture and obtains user consent. Create a `Windows.Graphics.Capture.GraphicsCaptureItem` for the selected monitor/window; never begin capture at app launch or after a phone reconnect.
2. Feed a D3D11 frame pool into a hardware H.264 encoder chosen in this order: Media Foundation hardware MFT (NVENC, Intel Quick Sync, AMD AMF exposed through MF), then supported software H.264 as an explicit fallback.
3. Send access units through a low-latency QUIC media stream (MsQuic/Rust `quinn`) authenticated by the already-approved companion identity. Use DTLS/TLS 1.3-equivalent QUIC encryption, certificate pinning and per-session key rotation. Keep control input on its independently authenticated reliable channel.
4. On Android, feed Annex-B/AVCC H.264 buffers into `MediaCodec` backed by a `Surface`. Start with 720p60/8 Mbps and negotiate resolution/FPS/bitrate from measured Wi-Fi conditions.
5. Transmit codec config (SPS/PPS) before IDR frames, request an IDR after loss/reconnect, and use adaptive bitrate plus a bounded jitter buffer. Stop capture, encoder, QUIC stream and Android decoder immediately on either endpoint's Disconnect.

## Milestones and verification

- A local capture prototype is present: choose a source in the Windows picker, count captured frames, and stop manually or on source closure. It does not transmit frames. Interactive Windows verification and sleep/resume handling are still required before this milestone is complete.
- Prototype Windows Graphics Capture with consent, selected-source indicator, sleep/resume handling and no remote transport.
- Add hardware encoder telemetry and verify hardware selection on NVIDIA, Intel and AMD devices; measure encode latency/CPU.
- Add pinned QUIC transport and Android `MediaCodec` decode; test loss, roaming and Wi-Fi recovery.
- Run end-to-end latency, battery, thermal and memory tests on actual Samsung hardware before enabling it in production.

No Sunshine, Moonlight, Electron or Flutter is part of this plan.
