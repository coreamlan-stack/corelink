//! WASAPI loopback source. The stream exists only while a user-approved screen
//! capture and authenticated companion session are both active.
use std::{io::Write, time::{Duration, Instant}};
use windows::{core::GUID, Win32::{Media::Audio::{eConsole, eRender, IAudioCaptureClient, IAudioClient, IMMDeviceEnumerator, MMDeviceEnumerator, AUDCLNT_BUFFERFLAGS_SILENT, AUDCLNT_SHAREMODE_SHARED, AUDCLNT_STREAMFLAGS_LOOPBACK, WAVEFORMATEXTENSIBLE}, System::Com::{CoCreateInstance, CoInitializeEx, CoTaskMemFree, CoUninitialize, CLSCTX_ALL, COINIT_MULTITHREADED}}};

pub fn stream<W: Write>(out: &mut W, active: impl Fn() -> bool) -> Result<(), String> {
    unsafe { CoInitializeEx(None, COINIT_MULTITHREADED).ok() }.map_err(|e| e.to_string())?;
    struct Apartment;
    impl Drop for Apartment { fn drop(&mut self) { unsafe { CoUninitialize() } } }
    let _apartment = Apartment;
    unsafe {
        let enumerator: IMMDeviceEnumerator = CoCreateInstance(&MMDeviceEnumerator, None, CLSCTX_ALL).map_err(|e| format!("Audio device unavailable: {e}"))?;
        let endpoint = enumerator.GetDefaultAudioEndpoint(eRender, eConsole).map_err(|e| format!("No default audio output: {e}"))?;
        let client: IAudioClient = endpoint.Activate(CLSCTX_ALL, None).map_err(|e| e.to_string())?;
        let format = client.GetMixFormat().map_err(|e| e.to_string())?;
        if format.is_null() { return Err("No audio format".into()); }
        let wave = std::ptr::read_unaligned(format);
        let tag = wave.wFormatTag;
        let channels = wave.nChannels;
        let sample_rate = wave.nSamplesPerSec;
        let bits = wave.wBitsPerSample;
        let align = wave.nBlockAlign;
        let float_guid = GUID::from_u128(0x00000003_0000_0010_8000_00aa00389b71);
        let pcm_guid = GUID::from_u128(0x00000001_0000_0010_8000_00aa00389b71);
        let subtype = if tag == 0xfffe && wave.cbSize >= 22 { Some(std::ptr::addr_of!((*(format as *const WAVEFORMATEXTENSIBLE)).SubFormat).read_unaligned()) } else { None };
        let encoding = if bits == 32 && (tag == 3 || subtype == Some(float_guid)) { "float32" } else if bits == 16 && (tag == 1 || subtype == Some(pcm_guid)) { "pcm16" } else { "unsupported" };
        if !(channels == 1 || channels == 2) || !(8000..=192000).contains(&sample_rate) || encoding == "unsupported" || align != channels * (bits / 8) {
            CoTaskMemFree(Some(format.cast()));
            return Err("Unsupported Windows audio output format (use stereo PCM16 or float32)".into());
        }
        let result = client.Initialize(AUDCLNT_SHAREMODE_SHARED, AUDCLNT_STREAMFLAGS_LOOPBACK, 1_000_000, 0, format, None);
        CoTaskMemFree(Some(format.cast()));
        result.map_err(|e| format!("Could not start audio capture: {e}"))?;
        let capture: IAudioCaptureClient = client.GetService().map_err(|e| e.to_string())?;
        client.Start().map_err(|e| e.to_string())?;
        let header = serde_json::json!({"type":"audio_started","sample_rate":sample_rate,"channels":channels,"format":encoding});
        let mut line = serde_json::to_vec(&header).map_err(|e| e.to_string())?; line.push(b'\n');
        let result = (|| -> Result<(), String> {
            out.write_all(&line).map_err(|e|e.to_string())?; out.flush().map_err(|e|e.to_string())?;
            let mut keepalive = Instant::now();
            while active() {
                while capture.GetNextPacketSize().map_err(|e|e.to_string())? > 0 {
                    let mut ptr = std::ptr::null_mut(); let mut frames = 0; let mut flags = 0;
                    capture.GetBuffer(&mut ptr, &mut frames, &mut flags, None, None).map_err(|e|e.to_string())?;
                    let len = (frames as usize).checked_mul(align as usize).ok_or("Audio packet too large")?;
                    let bytes = if len > 1024 * 1024 { Err("Audio packet too large".to_string()) } else if flags & AUDCLNT_BUFFERFLAGS_SILENT.0 as u32 != 0 { Ok(vec![0; len]) } else if ptr.is_null() { Err("Invalid audio packet".to_string()) } else { Ok(std::slice::from_raw_parts(ptr, len).to_vec()) };
                    capture.ReleaseBuffer(frames).map_err(|e|e.to_string())?;
                    let bytes = bytes?;
                    out.write_all(&(bytes.len() as u32).to_be_bytes()).and_then(|_|out.write_all(&bytes)).and_then(|_|out.flush()).map_err(|e|e.to_string())?;
                    keepalive = Instant::now();
                }
                if keepalive.elapsed() >= Duration::from_secs(5) { out.write_all(&0_u32.to_be_bytes()).and_then(|_|out.flush()).map_err(|e|e.to_string())?; keepalive = Instant::now(); }
                std::thread::sleep(Duration::from_millis(10));
            }
            Ok(())
        })();
        let _ = client.Stop();
        result
    }
}
