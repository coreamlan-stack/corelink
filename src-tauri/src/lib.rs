use serde::Serialize;
use std::{process::{Child, Command, Stdio}, sync::Mutex, net::Ipv4Addr};
use tauri::State;
use tauri_plugin_autostart::MacosLauncher;
use std::{io::Read, time::Duration};
use wait_timeout::ChildExt;

#[derive(Default)]
struct MirrorState(Mutex<Option<Child>>);

impl Drop for MirrorState {
    fn drop(&mut self) {
        if let Ok(slot) = self.0.get_mut() {
            if let Some(child) = slot.as_mut() {
                let _ = child.kill();
                let _ = child.wait();
            }
        }
    }
}

#[derive(Serialize)]
struct Tool { found: bool, path: Option<String> }
#[derive(Serialize)]
struct Device { serial: String, state: String, model: Option<String> }
#[derive(Serialize)]
struct Snapshot { adb: Tool, scrcpy: Tool, devices: Vec<Device>, mirroring: bool }

#[cfg(windows)]
use std::os::windows::process::CommandExt;

fn hidden_command(program: &str) -> Command {
    let mut command = Command::new(program);
    #[cfg(windows)]
    command.creation_flags(0x08000000); // CREATE_NO_WINDOW
    command
}

fn tool_location(name: &str) -> Tool {
    #[cfg(windows)]
    let result = hidden_command("where.exe").arg(format!("{name}.exe")).output();
    #[cfg(not(windows))]
    let result = hidden_command("which").arg(name).output();
    let path = result.ok().filter(|r| r.status.success())
        .and_then(|r| String::from_utf8(r.stdout).ok())
        .and_then(|s| s.lines().next().map(str::trim).map(str::to_owned))
        .filter(|s| !s.is_empty());
    Tool { found: path.is_some(), path }
}

fn adb_output(args: &[&str]) -> Result<String, String> {
    let tool = tool_location("adb");
    let path = tool.path.ok_or("ADB not found in PATH. Install Android SDK Platform-Tools and restart CoreLink.")?;
    let mut child = hidden_command(&path).args(args).stdin(Stdio::null()).stdout(Stdio::piped()).stderr(Stdio::piped()).spawn().map_err(|e| format!("Failed to run ADB: {e}"))?;
    let mut stdout_pipe = child.stdout.take().ok_or("Could not capture ADB output")?;
    let mut stderr_pipe = child.stderr.take().ok_or("Could not capture ADB errors")?;
    let stdout_reader = std::thread::spawn(move || { let mut bytes = Vec::new(); let _ = stdout_pipe.read_to_end(&mut bytes); bytes });
    let stderr_reader = std::thread::spawn(move || { let mut bytes = Vec::new(); let _ = stderr_pipe.read_to_end(&mut bytes); bytes });
    let status = match child.wait_timeout(Duration::from_secs(30)).map_err(|e| e.to_string())? {
        Some(status) => status,
        None => { let _ = child.kill(); let _ = child.wait(); let _ = stdout_reader.join(); let _ = stderr_reader.join(); return Err("ADB timed out. Check the phone and try again.".into()); }
    };
    let stdout_bytes = stdout_reader.join().map_err(|_| "Could not read ADB output")?;
    let stderr_bytes = stderr_reader.join().map_err(|_| "Could not read ADB errors")?;
    let stdout = String::from_utf8_lossy(&stdout_bytes).trim().to_string();
    let stderr = String::from_utf8_lossy(&stderr_bytes).trim().to_string();
    let combined = format!("{stdout}\n{stderr}");
    if status.success() && !combined.to_ascii_lowercase().contains("failed") && !combined.to_ascii_lowercase().contains("unable to connect") {
        Ok(if stdout.is_empty() { stderr } else { stdout })
    } else { Err(format!("ADB failed: {}", if stderr.is_empty() { stdout } else { stderr })) }
}

fn parse_devices(output: &str) -> Vec<Device> {
    output.lines().filter_map(|line| {
        let line = line.trim();
        if line.is_empty() || line.starts_with("List of devices") || line.starts_with('*') { return None; }
        let mut fields = line.split_whitespace();
        let serial = fields.next()?;
        let state = fields.next()?;
        if !matches!(state, "device" | "unauthorized" | "offline" | "recovery" | "sideload") { return None; }
        let model = fields.find_map(|field| field.strip_prefix("model:").map(|m| m.replace('_', " ")));
        Some(Device { serial: serial.to_string(), state: state.to_string(), model })
    }).collect()
}

fn checked_devices() -> Result<Vec<Device>, String> {
    Ok(parse_devices(&adb_output(&["devices", "-l"])?))
}

fn local_endpoint(value: &str) -> Result<(), String> {
    let (ip, port) = value.rsplit_once(':').ok_or("Enter an IPv4 address and port, like 192.168.1.5:37145")?;
    let address: Ipv4Addr = ip.parse().map_err(|_| "Use a numeric IPv4 address on your local network")?;
    let port: u16 = port.parse().map_err(|_| "Port must be between 1 and 65535")?;
    if port == 0 { return Err("Port cannot be zero".into()); }
    if !(address.is_private() || address.is_loopback() || address.is_link_local()) {
        return Err("CoreLink only allows private-network IP addresses".into());
    }
    Ok(())
}

#[tauri::command]
fn get_snapshot(state: State<'_, MirrorState>) -> Result<Snapshot, String> {
    let adb = tool_location("adb");
    let scrcpy = tool_location("scrcpy");
    let devices = if adb.found { checked_devices()? } else { Vec::new() };
    let mut child = state.0.lock().map_err(|_| "Mirroring state lock failed")?;
    let mirroring = if let Some(p) = child.as_mut() {
        match p.try_wait() {
            Ok(None) => true,
            Ok(Some(_)) => { *child = None; false },
            Err(_) => { *child = None; false }
        }
    } else { false };
    Ok(Snapshot { adb, scrcpy, devices, mirroring })
}

#[tauri::command]
fn pair_device(address: String, code: String) -> Result<String, String> {
    local_endpoint(&address)?;
    if code.len() != 6 || !code.bytes().all(|b| b.is_ascii_digit()) { return Err("Enter the six-digit pairing code".into()); }
    // ADB receives the code as an argument. Never log this argument or persist it.
    adb_output(&["pair", &address, &code])
}

#[tauri::command]
fn connect_device(address: String) -> Result<String, String> {
    local_endpoint(&address)?;
    adb_output(&["connect", &address])
}

#[tauri::command]
fn start_mirroring(serial: String, fps: u16, bitrate: u16, state: State<'_, MirrorState>) -> Result<String, String> {
    if !matches!(fps, 30 | 60 | 90 | 120) || !matches!(bitrate, 2 | 4 | 8 | 12 | 16) { return Err("Unsupported mirror settings".into()); }
    let devices = checked_devices()?;
    if !devices.iter().any(|d| d.serial == serial && d.state == "device") { return Err("Device is not currently authorized by ADB".into()); }
    if let Some((host, _)) = serial.rsplit_once(':') {
        local_endpoint(&format!("{host}:5555"))?; // Restrict TCP ADB devices to local networks.
    }
    let scrcpy = tool_location("scrcpy").path.ok_or("scrcpy not found in PATH. Install scrcpy and restart CoreLink.")?;
    let mut slot = state.0.lock().map_err(|_| "Mirroring state lock failed")?;
    if let Some(child) = slot.as_mut() {
        if child.try_wait().map_err(|e| e.to_string())?.is_none() { return Err("A mirroring session is already active".into()); }
    }
    *slot = None;
    let child = hidden_command(&scrcpy)
        .args(["--serial", &serial, "--max-fps", &fps.to_string(), "--video-bit-rate", &format!("{}M", bitrate), "--window-title", "CoreLink · Samsung Mirror"])
        .stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null())
        .spawn().map_err(|e| format!("Could not launch scrcpy: {e}"))?;
    *slot = Some(child);
    Ok("scrcpy launched. Use the separate phone mirror window to control Android.".into())
}

#[tauri::command]
fn stop_mirroring(state: State<'_, MirrorState>) -> Result<(), String> {
    let mut slot = state.0.lock().map_err(|_| "Mirroring state lock failed")?;
    if let Some(mut child) = slot.take() {
        if child.try_wait().map_err(|e| e.to_string())?.is_none() {
            child.kill().map_err(|e| format!("Could not stop scrcpy: {e}"))?;
            let _ = child.wait();
        }
    }
    Ok(())
}

pub fn run() {
    tauri::Builder::default()
        .manage(MirrorState::default())
        .plugin(tauri_plugin_autostart::init(MacosLauncher::LaunchAgent, None))
        .invoke_handler(tauri::generate_handler![get_snapshot, pair_device, connect_device, start_mirroring, stop_mirroring])
        .run(tauri::generate_context!())
        .expect("Failed to start CoreLink");
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn parses_real_adb_format() {
        let devices = parse_devices("List of devices attached\nR58ABC device product:a model:SM_S918B transport_id:1\nabc unauthorized\n");
        assert_eq!(devices.len(), 2);
        assert_eq!(devices[0].model.as_deref(), Some("SM S918B"));
        assert_eq!(devices[1].state, "unauthorized");
    }
    #[test] fn rejects_public_endpoints() {
        assert!(local_endpoint("192.168.1.5:37021").is_ok());
        assert!(local_endpoint("8.8.8.8:5555").is_err());
        assert!(local_endpoint("192.168.1.5:0").is_err());
        assert!(local_endpoint("192.168.1.5:5555;whoami").is_err());
    }
}
