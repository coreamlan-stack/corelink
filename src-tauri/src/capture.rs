use flate2::{write::ZlibEncoder, Compression};
use serde::Serialize;
use std::io::Write;
use std::sync::{
    atomic::{AtomicBool, Ordering},
    Arc, Mutex, OnceLock,
};
use std::time::Duration;
use windows::{
    core::Interface,
    Foundation::TypedEventHandler,
    Graphics::{
        Capture::{
            Direct3D11CaptureFramePool, GraphicsCaptureItem, GraphicsCapturePicker,
            GraphicsCaptureSession,
        },
        DirectX::{Direct3D11::IDirect3DDevice, DirectXPixelFormat},
    },
    Win32::{
        Foundation::{HMODULE, HWND},
        Graphics::{
            Direct3D::D3D_DRIVER_TYPE_HARDWARE,
            Direct3D11::{
                D3D11CreateDevice, ID3D11Device, ID3D11Texture2D, D3D11_CPU_ACCESS_READ,
                D3D11_CREATE_DEVICE_BGRA_SUPPORT, D3D11_MAPPED_SUBRESOURCE, D3D11_MAP_READ,
                D3D11_SDK_VERSION, D3D11_TEXTURE2D_DESC, D3D11_USAGE_STAGING,
            },
            Dxgi::IDXGIDevice,
        },
        System::{
            SystemInformation::GetTickCount64,
            WinRT::{
                Direct3D11::{CreateDirect3D11DeviceFromDXGIDevice, IDirect3DDxgiInterfaceAccess},
                RoInitialize, RoUninitialize, RO_INIT_MULTITHREADED,
            },
        },
        UI::Shell::IInitializeWithWindow,
    },
};

#[derive(Clone)]
pub struct VideoFrame {
    pub width: u32,
    pub height: u32,
    pub number: u64,
    pub data: Arc<Vec<u8>>,
    owner: std::sync::Weak<AtomicBool>,
}
impl VideoFrame {
    pub fn same_capture(&self, other: &Self) -> bool {
        self.owner.ptr_eq(&other.owner)
    }
}
static VIDEO: OnceLock<Mutex<Option<VideoFrame>>> = OnceLock::new();
static ACTIVE_STOP: OnceLock<Mutex<Option<std::sync::Weak<AtomicBool>>>> = OnceLock::new();
pub fn stop_active_capture() {
    if let Ok(active) = ACTIVE_STOP.get_or_init(|| Mutex::new(None)).lock() {
        if let Some(flag) = active.as_ref().and_then(|flag| flag.upgrade()) {
            flag.store(true, Ordering::SeqCst);
        }
        clear_frame();
    }
}
pub fn stop_for_frame(frame: &VideoFrame) {
    if let Ok(active) = ACTIVE_STOP.get_or_init(|| Mutex::new(None)).lock() {
        if active
            .as_ref()
            .is_some_and(|owner| owner.ptr_eq(&frame.owner))
        {
            if let Some(flag) = frame.owner.upgrade() {
                flag.store(true, Ordering::SeqCst);
            }
            clear_frame();
        }
    }
}
pub fn latest_frame() -> Option<VideoFrame> {
    VIDEO.get_or_init(|| Mutex::new(None)).lock().ok()?.clone()
}
fn clear_frame() {
    if let Ok(mut f) = VIDEO.get_or_init(|| Mutex::new(None)).lock() {
        *f = None;
    }
}

#[derive(Clone, Default, Serialize)]
pub struct CaptureSnapshot {
    pub running: bool,
    pub starting: bool,
    pub source: String,
    pub frames: u64,
    pub error: String,
}
#[derive(Default)]
pub struct CaptureState {
    snapshot: Arc<Mutex<CaptureSnapshot>>,
    stop: Mutex<Option<(Arc<AtomicBool>, Arc<AtomicBool>)>>,
}
impl CaptureState {
    pub fn snapshot(&self) -> CaptureSnapshot {
        self.snapshot.lock().unwrap().clone()
    }
    pub fn stop(&self) {
        if let Some((flag, _)) = self.stop.lock().unwrap().as_ref() {
            flag.store(true, Ordering::SeqCst);
        }
        clear_frame();
    }
    pub fn start(&self, hwnd: isize) -> Result<(), String> {
        let mut slot = self.stop.lock().map_err(|e| e.to_string())?;
        if slot.is_some() {
            return Err("Capture is already starting or running".into());
        }
        let stop = Arc::new(AtomicBool::new(false));
        *ACTIVE_STOP
            .get_or_init(|| Mutex::new(None))
            .lock()
            .map_err(|e| e.to_string())? = Some(Arc::downgrade(&stop));
        let finished = Arc::new(AtomicBool::new(false));
        *slot = Some((stop.clone(), finished.clone()));
        let snapshot = self.snapshot.clone();
        *snapshot.lock().unwrap() = CaptureSnapshot {
            starting: true,
            ..Default::default()
        };
        std::thread::spawn(move || {
            let result = capture(hwnd, stop, snapshot.clone());
            clear_frame();
            let mut state = snapshot.lock().unwrap();
            state.running = false;
            state.starting = false;
            if let Err(e) = result {
                state.error = e;
            }
            finished.store(true, Ordering::SeqCst);
        });
        Ok(())
    }
    pub fn clear_finished(&self) {
        let mut slot = self.stop.lock().unwrap();
        if slot.as_ref().is_some_and(|(_, f)| f.load(Ordering::SeqCst)) {
            *slot = None;
        }
    }
}
impl Drop for CaptureState {
    fn drop(&mut self) {
        self.stop();
    }
}

fn capture(
    hwnd: isize,
    stop: Arc<AtomicBool>,
    state: Arc<Mutex<CaptureSnapshot>>,
) -> Result<(), String> {
    struct Apartment;
    impl Drop for Apartment {
        fn drop(&mut self) {
            unsafe {
                RoUninitialize();
            }
        }
    }
    unsafe { RoInitialize(RO_INIT_MULTITHREADED) }
        .map_err(|e| format!("Could not initialize Windows capture: {e}"))?;
    let _apartment = Apartment;
    let run = || -> windows::core::Result<()> {
        if !GraphicsCaptureSession::IsSupported()? {
            return Err(windows::core::Error::from_win32());
        }
        let picker = GraphicsCapturePicker::new()?;
        unsafe {
            picker
                .cast::<IInitializeWithWindow>()?
                .Initialize(HWND(hwnd as *mut _))?;
        }
        let item = picker.PickSingleItemAsync()?.get()?;
        if stop.load(Ordering::SeqCst) {
            return Ok(());
        }
        let mut d3d: Option<ID3D11Device> = None;
        unsafe {
            D3D11CreateDevice(
                None,
                D3D_DRIVER_TYPE_HARDWARE,
                HMODULE::default(),
                D3D11_CREATE_DEVICE_BGRA_SUPPORT,
                None,
                D3D11_SDK_VERSION,
                Some(&mut d3d),
                None,
                None,
            )?;
        }
        let d3d = d3d.unwrap();
        let dxgi: IDXGIDevice = d3d.cast()?;
        let context = unsafe { d3d.GetImmediateContext()? };
        let device: IDirect3DDevice =
            unsafe { CreateDirect3D11DeviceFromDXGIDevice(&dxgi)? }.cast()?;
        let mut size = item.Size()?;
        let pool = Direct3D11CaptureFramePool::CreateFreeThreaded(
            &device,
            DirectXPixelFormat::B8G8R8A8UIntNormalized,
            2,
            size,
        )?;
        let session = pool.CreateCaptureSession(&item)?;
        let closed = stop.clone();
        item.Closed(&TypedEventHandler::<
            GraphicsCaptureItem,
            windows::core::IInspectable,
        >::new(move |_, _| {
            closed.store(true, Ordering::SeqCst);
            Ok(())
        }))?;
        session.StartCapture()?;
        {
            let mut s = state.lock().unwrap();
            s.running = true;
            s.starting = false;
            s.source = item.DisplayName()?.to_string();
            s.frames = 0;
            s.error.clear();
        }
        let mut last_tick = unsafe { GetTickCount64() };
        let mut last_video = std::time::Instant::now() - Duration::from_secs(1);
        let mut encoder = FrameEncoder::default();
        while !stop.load(Ordering::SeqCst) {
            let now = unsafe { GetTickCount64() };
            if now.saturating_sub(last_tick) > 5_000 {
                state.lock().unwrap().error = "Capture stopped after the PC slept or capture was interrupted. Choose a source to start again.".into();
                break;
            }
            last_tick = now;
            if let Ok(frame) = pool.TryGetNextFrame() {
                let next = frame.ContentSize()?;
                if last_video.elapsed() >= Duration::from_millis(100)
                    && next.Width == size.Width
                    && next.Height == size.Height
                {
                    let video = encoder.encode_frame(
                        &frame,
                        &d3d,
                        &context,
                        state.lock().unwrap().frames + 1,
                        &stop,
                    )?;
                    if !stop.load(Ordering::SeqCst) {
                        if let Ok(mut slot) = VIDEO.get_or_init(|| Mutex::new(None)).lock() {
                            if !stop.load(Ordering::SeqCst) {
                                *slot = Some(video);
                            }
                        }
                    }
                    last_video = std::time::Instant::now();
                }
                frame.Close()?;
                state.lock().unwrap().frames += 1;
                if next.Width > 0
                    && next.Height > 0
                    && (next.Width != size.Width || next.Height != size.Height)
                {
                    size = next;
                    pool.Recreate(&device, DirectXPixelFormat::B8G8R8A8UIntNormalized, 2, size)?;
                }
            }
            std::thread::sleep(Duration::from_millis(16));
        }
        session.Close()?;
        pool.Close()?;
        Ok(())
    };
    run().map_err(|e| format!("Capture ended: {e}"))
}

#[derive(Default)]
struct FrameEncoder {
    staging: Option<ID3D11Texture2D>,
    source_desc: Option<D3D11_TEXTURE2D_DESC>,
}

fn scaled_size(width: u32, height: u32) -> (u32, u32) {
    let numerator = 960_u64 * u64::from(height);
    let denominator = 540_u64 * u64::from(width);
    if width <= 960 && height <= 540 {
        (width, height)
    } else if numerator <= denominator {
        (
            960,
            ((u64::from(height) * 960) / u64::from(width)).max(1) as u32,
        )
    } else {
        (
            ((u64::from(width) * 540) / u64::from(height)).max(1) as u32,
            540,
        )
    }
}

impl FrameEncoder {
    fn encode_frame(
        &mut self,
        frame: &windows::Graphics::Capture::Direct3D11CaptureFrame,
        device: &ID3D11Device,
        context: &windows::Win32::Graphics::Direct3D11::ID3D11DeviceContext,
        number: u64,
        stop: &Arc<AtomicBool>,
    ) -> windows::core::Result<VideoFrame> {
        let source: ID3D11Texture2D = unsafe {
            frame
                .Surface()?
                .cast::<IDirect3DDxgiInterfaceAccess>()?
                .GetInterface()?
        };
        let mut desc = D3D11_TEXTURE2D_DESC::default();
        unsafe {
            source.GetDesc(&mut desc);
        }
        if desc.Width == 0 || desc.Height == 0 || desc.Width > 16384 || desc.Height > 16384 {
            return Err(windows::core::Error::from_win32());
        }
        desc.Usage = D3D11_USAGE_STAGING;
        desc.BindFlags = 0;
        desc.CPUAccessFlags = D3D11_CPU_ACCESS_READ.0 as u32;
        desc.MiscFlags = 0;
        if self.source_desc != Some(desc) {
            let mut staging = None;
            unsafe {
                device.CreateTexture2D(&desc, None, Some(&mut staging))?;
            }
            self.staging = staging;
            self.source_desc = Some(desc);
        }
        let staging = self
            .staging
            .as_ref()
            .ok_or_else(windows::core::Error::from_win32)?;
        let mut mapped = D3D11_MAPPED_SUBRESOURCE::default();
        unsafe {
            context.CopyResource(staging, &source);
            context.Map(staging, 0, D3D11_MAP_READ, 0, Some(&mut mapped))?;
        }
        let (width, height) = scaled_size(desc.Width, desc.Height);
        let mut raw = Vec::with_capacity((width * height * 4) as usize);
        unsafe {
            for y in 0..height {
                let source_y = (u64::from(y) * u64::from(desc.Height) / u64::from(height)) as usize;
                let row = (mapped.pData as *const u8).add(source_y * mapped.RowPitch as usize);
                for x in 0..width {
                    let source_x =
                        (u64::from(x) * u64::from(desc.Width) / u64::from(width)) as usize;
                    let pixel = std::slice::from_raw_parts(row.add(source_x * 4), 4);
                    raw.extend_from_slice(&[pixel[0], pixel[1], pixel[2], 255]);
                }
            }
            context.Unmap(staging, 0);
        }
        let mut encoder = ZlibEncoder::new(Vec::new(), Compression::fast());
        encoder
            .write_all(&raw)
            .map_err(|_| windows::core::Error::from_win32())?;
        let data = encoder
            .finish()
            .map_err(|_| windows::core::Error::from_win32())?;
        Ok(VideoFrame {
            width,
            height,
            number,
            data: Arc::new(data),
            owner: Arc::downgrade(stop),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::scaled_size;
    #[test]
    fn scales_to_fit_without_upscaling_or_distorting_the_source() {
        assert_eq!(scaled_size(1920, 1080), (960, 540));
        assert_eq!(scaled_size(1280, 720), (960, 540));
        assert_eq!(scaled_size(1080, 1920), (303, 540));
        assert_eq!(scaled_size(640, 480), (640, 480));
    }
}
