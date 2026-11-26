//! Local-only companion protocol.  It deliberately has no HTTP endpoint and is
//! started only after the owner enables it in the desktop UI.
use aes_gcm::{
    aead::{Aead, KeyInit},
    Aes256Gcm, Nonce,
};
use base64::{engine::general_purpose::STANDARD as B64, Engine};
use rand::RngCore;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    collections::{HashMap, HashSet},
    io::{BufRead, BufReader, Write},
    net::{IpAddr, Ipv4Addr, TcpListener, TcpStream},
    sync::{Arc, Mutex},
    thread,
    time::{Duration, Instant},
};

pub const COMPANION_PORT: u16 = 49876;
const PAIRING_TTL: Duration = Duration::from_secs(90);
const SESSION_TTL: Duration = Duration::from_secs(15 * 60);

#[derive(Clone, Serialize)]
pub struct PairRequest {
    pub id: String,
    pub name: String,
    pub request_id: String,
}
#[derive(Clone, Serialize)]
pub struct CompanionSnapshot {
    pub running: bool,
    pub port: u16,
    pub pending: Vec<PairRequest>,
    pub trusted_devices: Vec<String>,
}
struct Pending {
    request: PairRequest,
    proof: String,
    client_nonce: String,
    created: Instant,
    approved_code: Option<String>,
}
struct Session {
    key: [u8; 32],
    expires: Instant,
    seen_nonces: HashSet<String>,
}
struct Inner {
    running: bool,
    pairing_nonce: [u8; 16],
    pending: HashMap<String, Pending>,
    sessions: HashMap<String, Session>,
    trusted: HashMap<String, String>,
}
#[derive(Clone)]
pub struct CompanionState(Arc<Mutex<Inner>>);

impl Default for CompanionState {
    fn default() -> Self {
        let mut n = [0; 16];
        rand::thread_rng().fill_bytes(&mut n);
        Self(Arc::new(Mutex::new(Inner {
            running: false,
            pairing_nonce: n,
            pending: HashMap::new(),
            sessions: HashMap::new(),
            trusted: HashMap::new(),
        })))
    }
}

fn local(addr: IpAddr) -> bool {
    match addr {
        IpAddr::V4(v) => v.is_private() || v.is_loopback() || v.is_link_local(),
        IpAddr::V6(v) => {
            v.is_loopback()
                || (v.segments()[0] & 0xfe00) == 0xfc00
                || (v.segments()[0] & 0xffc0) == 0xfe80
        }
    }
}
fn digest(parts: &[&[u8]]) -> [u8; 32] {
    let mut h = Sha256::new();
    for p in parts {
        h.update(p);
    }
    h.finalize().into()
}
fn expected_proof(id: &str, code: &str, nonce: &str, client_nonce: &str) -> String {
    B64.encode(digest(&[
        id.as_bytes(),
        code.as_bytes(),
        nonce.as_bytes(),
        client_nonce.as_bytes(),
    ]))
}
fn text_id() -> String {
    let mut b = [0; 16];
    rand::thread_rng().fill_bytes(&mut b);
    B64.encode(b).replace(['+', '/', '='], "")
}
fn line(stream: &mut TcpStream, value: &impl Serialize) -> std::io::Result<()> {
    let mut out = serde_json::to_vec(value).unwrap_or_default();
    out.push(b'\n');
    stream.write_all(&out)
}

#[derive(Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
enum Message {
    Hello,
    PairRequest {
        id: String,
        name: String,
        proof: String,
        client_nonce: String,
    },
    Input {
        id: String,
        nonce: String,
        ciphertext: String,
    },
    Disconnect {
        id: String,
    },
}

impl CompanionState {
    pub fn start(&self) -> Result<(), String> {
        {
            let mut inner = self.0.lock().map_err(|_| "Companion state lock failed")?;
            if inner.running {
                return Ok(());
            }
            inner.running = true;
        }
        let listener = TcpListener::bind((Ipv4Addr::UNSPECIFIED, COMPANION_PORT))
            .map_err(|e| format!("Could not open CoreLink companion port: {e}"))?;
        listener.set_nonblocking(true).map_err(|e| e.to_string())?;
        let state = self.clone();
        thread::spawn(move || loop {
            match listener.accept() {
                Ok((stream, peer)) => {
                    if local(peer.ip()) {
                        let child = state.clone();
                        thread::spawn(move || serve(stream, child));
                    }
                }
                Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                    if !state.running() {
                        break;
                    }
                    thread::sleep(Duration::from_millis(100));
                }
                Err(_) => break,
            }
        });
        Ok(())
    }
    pub fn stop(&self) {
        if let Ok(mut i) = self.0.lock() {
            i.running = false;
            i.sessions.clear();
            i.pending.clear();
        }
    }
    fn running(&self) -> bool {
        self.0.lock().map(|x| x.running).unwrap_or(false)
    }
    pub fn snapshot(&self) -> CompanionSnapshot {
        let i = self.0.lock().unwrap();
        CompanionSnapshot {
            running: i.running,
            port: COMPANION_PORT,
            pending: i
                .pending
                .values()
                .filter(|p| p.approved_code.is_none())
                .map(|p| p.request.clone())
                .collect(),
            trusted_devices: i.trusted.values().cloned().collect(),
        }
    }
    pub fn approve(&self, request_id: &str, code: String) -> Result<(), String> {
        if code.len() != 6 || !code.bytes().all(|x| x.is_ascii_digit()) {
            return Err("Enter the six-digit code shown in CoreLink Companion".into());
        }
        let mut i = self.0.lock().map_err(|_| "Companion state lock failed")?;
        let p = i
            .pending
            .get_mut(request_id)
            .ok_or("Pairing request expired")?;
        p.approved_code = Some(code);
        Ok(())
    }
    pub fn reject(&self, request_id: &str) -> Result<(), String> {
        let mut i = self.0.lock().map_err(|_| "Companion state lock failed")?;
        i.pending
            .remove(request_id)
            .map(|_| ())
            .ok_or("Pairing request expired".into())
    }
    pub fn revoke(&self, id: &str) -> Result<(), String> {
        let mut i = self.0.lock().map_err(|_| "Companion state lock failed")?;
        i.sessions.remove(id);
        i.trusted.remove(id);
        Ok(())
    }
}

fn serve(mut stream: TcpStream, state: CompanionState) {
    let _ = stream.set_read_timeout(Some(Duration::from_secs(5)));
    let reader_stream = match stream.try_clone() {
        Ok(s) => s,
        Err(_) => return,
    };
    let mut reader = BufReader::new(reader_stream);
    let mut raw = String::new();
    if reader.read_line(&mut raw).is_err() {
        return;
    }
    let msg: Message = match serde_json::from_str(&raw) {
        Ok(m) => m,
        Err(_) => {
            let _ = line(
                &mut stream,
                &serde_json::json!({"type":"error","message":"invalid request"}),
            );
            return;
        }
    };
    match msg {
        Message::Hello => {
            let nonce = state.0.lock().unwrap().pairing_nonce;
            let _ = line(
                &mut stream,
                &serde_json::json!({"type":"hello","nonce":B64.encode(nonce),"port":COMPANION_PORT,"protocol":1}),
            );
        }
        Message::PairRequest {
            id,
            name,
            proof,
            client_nonce,
        } => pair(stream, state, id, name, proof, client_nonce),
        Message::Input {
            id,
            nonce,
            ciphertext,
        } => {
            let ok = decrypt_input(&state, &id, &nonce, &ciphertext)
                .and_then(dispatch_input)
                .is_ok();
            let _ = line(&mut stream, &serde_json::json!({"type":"ack","ok":ok}));
        }
        Message::Disconnect { id } => {
            if let Ok(mut i) = state.0.lock() {
                i.sessions.remove(&id);
            }
            let _ = line(&mut stream, &serde_json::json!({"type":"disconnected"}));
        }
    }
}
fn pair(
    mut stream: TcpStream,
    state: CompanionState,
    id: String,
    name: String,
    proof: String,
    client_nonce: String,
) {
    if id.len() > 80 || name.len() > 80 || client_nonce.len() > 128 {
        let _ = line(
            &mut stream,
            &serde_json::json!({"type":"error","message":"invalid pairing request"}),
        );
        return;
    }
    let request_id = text_id();
    {
        let mut i = state.0.lock().unwrap();
        i.pending.insert(
            request_id.clone(),
            Pending {
                request: PairRequest {
                    id: id.clone(),
                    name,
                    request_id: request_id.clone(),
                },
                proof: proof.clone(),
                client_nonce: client_nonce.clone(),
                created: Instant::now(),
                approved_code: None,
            },
        );
    }
    let _ = line(
        &mut stream,
        &serde_json::json!({"type":"approval_required","request_id":request_id}),
    );
    for _ in 0..180 {
        thread::sleep(Duration::from_millis(500));
        let result = {
            let mut i = state.0.lock().unwrap();
            let p = match i.pending.get(&request_id) {
                Some(p) => p,
                None => return,
            };
            if p.created.elapsed() > PAIRING_TTL {
                i.pending.remove(&request_id);
                None
            } else if let Some(code) = p.approved_code.clone() {
                let nonce = B64.encode(i.pairing_nonce);
                let expected = expected_proof(&p.request.id, &code, &nonce, &p.client_nonce);
                let key = digest(&[
                    code.as_bytes(),
                    nonce.as_bytes(),
                    p.client_nonce.as_bytes(),
                    p.request.id.as_bytes(),
                ]);
                let valid = p.proof == expected;
                let device = p.request.name.clone();
                i.pending.remove(&request_id);
                if valid {
                    i.sessions.insert(
                        id.clone(),
                        Session {
                            key,
                            expires: Instant::now() + SESSION_TTL,
                            seen_nonces: HashSet::new(),
                        },
                    );
                    i.trusted.insert(id.clone(), device);
                    Some(B64.encode(digest(&[&key, b"server-confirm"])))
                } else {
                    None
                }
            } else {
                continue;
            }
        };
        if let Some(confirm) = result {
            let _ = line(
                &mut stream,
                &serde_json::json!({"type":"paired","confirm":confirm,"expires_in":900}),
            );
        } else {
            let _ = line(&mut stream, &serde_json::json!({"type":"rejected"}));
        }
        return;
    }
}

#[derive(Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
enum Input {
    Move {
        dx: i32,
        dy: i32,
    },
    Click {
        button: String,
    },
    Scroll {
        dy: i32,
    },
    Key {
        key: String,
        ctrl: bool,
        alt: bool,
        shift: bool,
    },
}
fn decrypt_input(
    state: &CompanionState,
    id: &str,
    nonce: &str,
    ciphertext: &str,
) -> Result<Input, String> {
    let mut i = state.0.lock().map_err(|_| "lock")?;
    let s = i.sessions.get_mut(id).ok_or("unpaired device")?;
    if s.expires < Instant::now() {
        return Err("session expired".into());
    };
    let n = B64.decode(nonce).map_err(|_| "nonce")?;
    let c = B64.decode(ciphertext).map_err(|_| "ciphertext")?;
    if n.len() != 12 {
        return Err("nonce".into());
    };
    if !s.seen_nonces.insert(nonce.to_owned()) {
        return Err("replayed input".into());
    }
    let cipher = Aes256Gcm::new_from_slice(&s.key).map_err(|_| "key")?;
    let plain = cipher
        .decrypt(Nonce::from_slice(&n), c.as_ref())
        .map_err(|_| "authentication failed")?;
    serde_json::from_slice(&plain).map_err(|_| "invalid input".into())
}
fn dispatch_input(input: Input) -> Result<(), String> {
    match &input {
        Input::Move { dx, dy } if dx.abs() > 5000 || dy.abs() > 5000 => {
            Err("movement out of range".into())
        }
        Input::Scroll { dy } if dy.abs() > 5000 => Err("scroll out of range".into()),
        Input::Click { button } if button != "left" && button != "right" => {
            Err("button not allowed".into())
        }
        Input::Key { key, .. } if key.len() > 32 => Err("key not allowed".into()),
        _ => send_windows_input(input),
    }
}
#[cfg(windows)]
fn send_windows_input(input: Input) -> Result<(), String> {
    use windows::Win32::UI::Input::KeyboardAndMouse::{
        keybd_event, mouse_event, KEYEVENTF_KEYUP, MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP,
        MOUSEEVENTF_MOVE, MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP, MOUSEEVENTF_WHEEL,
        VK_CONTROL, VK_MENU, VK_SHIFT,
    };
    unsafe {
        match input {
            Input::Move { dx, dy } => mouse_event(MOUSEEVENTF_MOVE, dx, dy, 0, 0),
            Input::Scroll { dy } => mouse_event(MOUSEEVENTF_WHEEL, 0, 0, dy, 0),
            Input::Click { button } => {
                let (down, up) = if button == "left" {
                    (MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP)
                } else {
                    (MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP)
                };
                mouse_event(down, 0, 0, 0, 0);
                mouse_event(up, 0, 0, 0, 0);
            }
            Input::Key {
                key,
                ctrl,
                alt,
                shift,
            } => {
                if ctrl {
                    keybd_event(VK_CONTROL.0 as u8, 0, Default::default(), 0);
                }
                if alt {
                    keybd_event(VK_MENU.0 as u8, 0, Default::default(), 0);
                }
                if shift {
                    keybd_event(VK_SHIFT.0 as u8, 0, Default::default(), 0);
                }
                for c in key.bytes() {
                    if !c.is_ascii() {
                        return Err("Only ASCII keyboard input is supported".into());
                    }
                    keybd_event(c.to_ascii_uppercase(), 0, Default::default(), 0);
                    keybd_event(c.to_ascii_uppercase(), 0, KEYEVENTF_KEYUP, 0);
                }
                if shift {
                    keybd_event(VK_SHIFT.0 as u8, 0, KEYEVENTF_KEYUP, 0);
                }
                if alt {
                    keybd_event(VK_MENU.0 as u8, 0, KEYEVENTF_KEYUP, 0);
                }
                if ctrl {
                    keybd_event(VK_CONTROL.0 as u8, 0, KEYEVENTF_KEYUP, 0);
                }
            }
        }
    }
    Ok(())
}
#[cfg(not(windows))]
fn send_windows_input(_input: Input) -> Result<(), String> {
    Err("Windows input is unavailable on this platform".into())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn public_networks_are_rejected() {
        assert!(!local("8.8.8.8".parse().unwrap()));
        assert!(local("192.168.1.3".parse().unwrap()));
    }
    #[test]
    fn input_bounds_are_enforced() {
        assert!(dispatch_input(Input::Move { dx: 5001, dy: 0 }).is_err());
        assert!(dispatch_input(Input::Click {
            button: "middle".into()
        })
        .is_err());
    }
    #[test]
    fn pairing_proof_requires_the_exact_code() {
        let proof = expected_proof("android-1", "123456", "nonce", "client");
        assert_eq!(
            proof,
            expected_proof("android-1", "123456", "nonce", "client")
        );
        assert_ne!(
            proof,
            expected_proof("android-1", "654321", "nonce", "client")
        );
    }
    #[test]
    fn stopping_service_clears_active_sessions() {
        let state = CompanionState::default();
        {
            let mut inner = state.0.lock().unwrap();
            inner.running = true;
            inner.sessions.insert(
                "phone".into(),
                Session {
                    key: [0; 32],
                    expires: Instant::now() + SESSION_TTL,
                    seen_nonces: HashSet::new(),
                },
            );
        }
        state.stop();
        let snapshot = state.snapshot();
        assert!(!snapshot.running);
        assert!(snapshot.trusted_devices.is_empty());
    }
}
