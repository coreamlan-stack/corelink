//! TLS is the transport protection.  A separate, fresh application key is used
//! for every control session so a packet from an earlier connection is useless.
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use hmac::{Hmac, Mac};
use qrcodegen::{QrCode, QrCodeEcc};
use rand::RngCore;
use rcgen::{CertificateParams, DistinguishedName, DnType, KeyPair};
use rustls::{
    pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer},
    ServerConfig, ServerConnection, StreamOwned,
};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    collections::{HashMap, HashSet},
    io::{BufRead, BufReader, Read, Write},
    net::{IpAddr, Ipv4Addr, TcpListener, TcpStream, UdpSocket},
    sync::{Arc, Mutex},
    thread,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};
use std::sync::atomic::{AtomicBool, Ordering};
static HELD_LEFT: AtomicBool = AtomicBool::new(false);
static HELD_RIGHT: AtomicBool = AtomicBool::new(false);
pub const COMPANION_PORT: u16 = 49876;
const ENROLLMENT_TTL: Duration = Duration::from_secs(120);
const SESSION_TTL: Duration = Duration::from_secs(15 * 60);
const MAX_LINE: usize = 16384;
type HmacSha256 = Hmac<Sha256>;
#[derive(Clone, Serialize)]
pub struct PairRequest {
    pub request_id: String,
    pub name: String,
    pub device_id: String,
}
#[derive(Clone, Serialize)]
pub struct TrustedDevice {
    pub device_id: String,
    pub name: String,
}
#[derive(Clone, Serialize)]
pub struct CompanionSnapshot {
    pub running: bool,
    pub port: u16,
    pub pending: Vec<PairRequest>,
    pub trusted_devices: Vec<TrustedDevice>,
    pub active_sessions: usize,
    pub qr_payload: Option<String>,
    pub qr_svg: Option<String>,
    pub expires_at: Option<u64>,
}
struct Enrollment {
    token: [u8; 32],
    expires: Instant,
    expires_at: u64,
    used: bool,
}
struct Pending {
    request: PairRequest,
    secret: [u8; 32],
    created: Instant,
}
type Device = crate::trusted_store::StoredDevice;
struct Session {
    device_id: String,
    key: [u8; 32],
    last_sequence: u64,
    expires: Instant,
}
struct Identity {
    cert: CertificateDer<'static>,
    key: PrivateKeyDer<'static>,
    pin: String,
}
struct Inner {
    running: bool,
    enrollment: Option<Enrollment>,
    pending: HashMap<String, Pending>,
    trusted: HashMap<String, Device>,
    sessions: HashMap<String, Session>,
    used_open_nonces: HashSet<String>,
    endpoint: Option<Ipv4Addr>,
    identity: Identity,
    storage: Option<std::path::PathBuf>,
    capture: Option<Arc<crate::capture::CaptureState>>,
}
#[derive(Clone)]
pub struct CompanionState(Arc<Mutex<Inner>>);
fn random<const N: usize>() -> [u8; N] {
    let mut b = [0; N];
    rand::thread_rng().fill_bytes(&mut b);
    b
}
fn b64(v: impl AsRef<[u8]>) -> String {
    URL_SAFE_NO_PAD.encode(v)
}
fn unix() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}
fn private(ip: Ipv4Addr) -> bool {
    ip.is_private() || ip.is_link_local()
}
fn private_peer(ip: IpAddr) -> bool {
    matches!(ip,IpAddr::V4(v)if private(v))
}
fn lan_ip() -> Result<Ipv4Addr, String> {
    let s = std::net::UdpSocket::bind("0.0.0.0:0").map_err(|e| e.to_string())?;
    s.connect("192.0.2.1:9").map_err(|e| e.to_string())?;
    match s.local_addr().map_err(|e| e.to_string())?.ip() {
        IpAddr::V4(v) if private(v) => Ok(v),
        _ => Err("Connect to private Wi-Fi or Ethernet before starting CoreLink Companion.".into()),
    }
}
fn identity() -> Identity {
    let mut p = CertificateParams::new(vec!["CoreLink.local".into()]).expect("cert");
    let mut d = DistinguishedName::new();
    d.push(DnType::CommonName, "CoreLink local companion");
    p.distinguished_name = d;
    let k = KeyPair::generate().expect("key");
    let c = p.self_signed(&k).expect("cert");
    let der = c.der().clone();
    Identity {
        pin: b64(Sha256::digest(der.as_ref())),
        cert: der,
        key: PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(k.serialize_der())),
    }
}
fn config(i: &Identity) -> Result<Arc<ServerConfig>, String> {
    ServerConfig::builder()
        .with_no_client_auth()
        .with_single_cert(vec![i.cert.clone()], i.key.clone_key())
        .map(Arc::new)
        .map_err(|e| e.to_string())
}
fn save_trust(inner: &Inner, devices: &HashMap<String, Device>) -> Result<(), String> {
    if let Some(path)=&inner.storage {
        crate::trusted_store::save(path, &crate::trusted_store::StoredTrust {
            version:1, certificate:inner.identity.cert.as_ref().to_vec(),
            private_key:inner.identity.key.secret_der().to_vec(), devices:devices.clone(),
        })?;
    }
    Ok(())
}
fn send<S: Serialize, W: Write>(w: &mut W, v: &S) -> std::io::Result<()> {
    let mut b = serde_json::to_vec(v)?;
    b.push(b'\n');
    w.write_all(&b)?;
    w.flush()
}
fn read<T: for<'a> Deserialize<'a>, R: BufRead>(r: &mut R) -> Result<T, String> {
    let mut b = Vec::new();
    r.take((MAX_LINE + 1) as u64)
        .read_until(b'\n', &mut b)
        .map_err(|e| format!("read failed: {e}"))?;
    if b.len() > MAX_LINE || b.last() != Some(&b'\n') {
        return Err("invalid message".into());
    };
    serde_json::from_slice(&b).map_err(|e| format!("invalid message: {e}"))
}
fn qr_svg(v: &str) -> String {
    let q = QrCode::encode_text(v, QrCodeEcc::Medium).expect("qr");
    let z = 3;
    let n = q.size() + z * 2;
    let mut p = String::new();
    for y in 0..q.size() {
        for x in 0..q.size() {
            if q.get_module(x, y) {
                p.push_str(&format!("M{} {}h1v1h-1z", x + z, y + z))
            }
        }
    }
    format!(
        r#"<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {n} {n}"><rect width="100%" height="100%" fill="white"/><path fill="black" d="{p}"/></svg>"#
    )
}
fn transcript(parts: &[&str]) -> Vec<u8> {
    let mut s = "corelink-v3".to_string();
    for p in parts {
        s.push('\n');
        s.push_str(p)
    }
    s.into_bytes()
}
fn check(k: &[u8; 32], m: &[u8], tag: &str) -> bool {
    let Ok(t) = URL_SAFE_NO_PAD.decode(tag) else {
        return false;
    };
    let mut h = HmacSha256::new_from_slice(k).expect("hmac");
    h.update(m);
    h.verify_slice(&t).is_ok()
}
fn sign(k: &[u8; 32], m: &[u8]) -> String {
    let mut h = HmacSha256::new_from_slice(k).expect("hmac");
    h.update(m);
    b64(h.finalize().into_bytes())
}
fn session_key(k: &[u8; 32], sid: &str, cn: &str, sn: &str) -> [u8; 32] {
    let mut h = HmacSha256::new_from_slice(k).expect("hmac");
    h.update(&transcript(&["session-key", sid, cn, sn]));
    h.finalize().into_bytes().into()
}
impl Default for CompanionState {
    fn default() -> Self {
        Self(Arc::new(Mutex::new(Inner {
            running: false,
            enrollment: None,
            pending: HashMap::new(),
            trusted: HashMap::new(),
            sessions: HashMap::new(),
            used_open_nonces: HashSet::new(),
            endpoint: None,
            identity: identity(),
            storage: None,
            capture: None,
        })))
    }
}
impl CompanionState {
    pub fn persistent(path: std::path::PathBuf, capture: Arc<crate::capture::CaptureState>) -> Result<Self, String> {
        let saved = crate::trusted_store::load(&path)?;
        let state = Self::default();
        {
            let mut inner=state.0.lock().map_err(|_| "state lock")?;
            if let Some(saved)=saved {
                let cert=CertificateDer::from(saved.certificate);
                inner.identity=Identity { pin:b64(Sha256::digest(cert.as_ref())), cert, key:PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(saved.private_key)) };
                config(&inner.identity)?;
                inner.trusted=saved.devices;
            }
            inner.storage=Some(path);
            inner.capture=Some(capture);
            save_trust(&inner, &inner.trusted)?;
        }
        Ok(state)
    }
    pub fn start(&self) -> Result<(), String> {
        let ip = lan_ip()?;
        // Accept private peers on every adapter so a hotspot still works when
        // Windows prefers Ethernet for its default route. Peer filtering and
        // the authenticated, pinned TLS session remain enforced below.
        let l = TcpListener::bind((Ipv4Addr::UNSPECIFIED, COMPANION_PORT))
            .map_err(|e| format!("Could not start the CoreLink listener: {e}"))?;
        l.set_nonblocking(true).map_err(|e| e.to_string())?;
        {
            let mut i = self.0.lock().map_err(|_| "state lock")?;
            if i.running {
                return Ok(());
            }
            i.running = true;
            i.endpoint = Some(ip);
            i.enrollment = Some(Enrollment {
                token: random(),
                expires: Instant::now() + ENROLLMENT_TTL,
                expires_at: unix() + 120,
                used: false,
            });
        }
        let st = self.clone();
        match UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 49877)) {
            Ok(socket) => { let discovery_state=self.clone(); thread::spawn(move || {
                let _=socket.set_read_timeout(Some(Duration::from_millis(500)));
                let mut packet=[0u8;64];
                while discovery_state.running() {
                    if let Ok((n,peer))=socket.recv_from(&mut packet) {
                        if private_peer(peer.ip()) && &packet[..n]==b"CORELINK_DISCOVER_V1" { let _=socket.send_to(b"CORELINK_HERE_V1:49876",peer); }
                    }
                }
            }); },
            Err(error) => eprintln!("CoreLink LAN discovery unavailable: {error}"),
        }
        thread::spawn(move || loop {
            match l.accept() {
                Ok((s, p)) if private_peer(p.ip()) => {
                    let x = st.clone();
                    thread::spawn(move || serve(s, x));
                }
                Ok(_) => {}
                Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                    if !st.running() {
                        break;
                    }
                    thread::sleep(Duration::from_millis(75))
                }
                Err(_) => break,
            }
        });
        Ok(())
    }
    pub fn stop(&self) {
        crate::capture::stop_active_capture();
        if let Ok(mut i) = self.0.lock() {
            release_held_buttons();
            i.running = false;
            i.enrollment = None;
            i.pending.clear();
            i.sessions.clear();
            i.used_open_nonces.clear()
        }
    }
    pub fn regenerate_enrollment(&self) -> Result<(), String> {
        let mut i = self.0.lock().map_err(|_| "state lock")?;
        if !i.running {
            return Err("Start the companion service first".into());
        }
        i.pending.clear();
        i.enrollment = Some(Enrollment {
            token: random(),
            expires: Instant::now() + ENROLLMENT_TTL,
            expires_at: unix() + 120,
            used: false,
        });
        Ok(())
    }
    fn running(&self) -> bool {
        self.0.lock().map(|mut i| {
            let now=Instant::now();
            if i.sessions.values().any(|s| s.expires <= now) { release_held_buttons(); }
            i.sessions.retain(|_, s| s.expires > now);
            i.running
        }).unwrap_or(false)
    }
    pub fn snapshot(&self) -> CompanionSnapshot {
        let mut i = self.0.lock().expect("state");
        if i.sessions.values().any(|s| s.expires <= Instant::now()) { release_held_buttons(); }
        i.sessions.retain(|_, s| s.expires > Instant::now());
        let (q, svg, e) = match (&i.enrollment, i.endpoint) {
            (Some(x), Some(ip)) if !x.used && x.expires > Instant::now() => {
                let q=format!("corelink://pair?v=3&host={ip}&port={COMPANION_PORT}&pin={}&token={}&expires={}",i.identity.pin,b64(x.token),x.expires_at);
                (Some(q.clone()), Some(qr_svg(&q)), Some(x.expires_at))
            }
            _ => (None, None, None),
        };
        CompanionSnapshot {
            running: i.running,
            port: COMPANION_PORT,
            pending: i.pending.values().map(|p| p.request.clone()).collect(),
            trusted_devices: i
                .trusted
                .iter()
                .map(|(id, d)| TrustedDevice {
                    device_id: id.clone(),
                    name: d.name.clone(),
                })
                .collect(),
            active_sessions: i.sessions.len(),
            qr_payload: q,
            qr_svg: svg,
            expires_at: e,
        }
    }
    pub fn approve(&self, id: &str) -> Result<(), String> {
        let mut i = self.0.lock().map_err(|_| "state lock")?;
        let Some(p) = i.pending.remove(id) else {
            return Err("Pairing request expired".into());
        };
        if p.created.elapsed() > ENROLLMENT_TTL {
            return Err("Pairing request expired".into());
        }
        if i.sessions.values().any(|session| session.device_id == p.request.device_id) { release_held_buttons(); }
        i.sessions
            .retain(|_, session| session.device_id != p.request.device_id);
        let mut updated=i.trusted.clone();
        updated.insert(
            p.request.device_id,
            Device {
                name: p.request.name,
                secret: p.secret,
            },
        );
        save_trust(&i,&updated)?; i.trusted=updated;
        Ok(())
    }
    pub fn reject(&self, id: &str) -> Result<(), String> {
        self.0
            .lock()
            .map_err(|_| "state lock")?
            .pending
            .remove(id)
            .map(|_| ())
            .ok_or("Pairing request expired".into())
    }
    pub fn revoke(&self, id: &str) -> Result<(), String> {
        let mut i = self.0.lock().map_err(|_| "state lock")?;
        let mut updated=i.trusted.clone(); updated.remove(id); save_trust(&i,&updated)?;
        if i.sessions.values().any(|s| s.device_id == id) { release_held_buttons(); }
        i.trusted=updated;
        i.sessions.retain(|_, s| s.device_id != id);
        crate::capture::stop_active_capture();
        Ok(())
    }
}
#[derive(Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
enum Request {
    Enroll {
        token: String,
        device_id: String,
        name: String,
    },
    SessionOpen {
        device_id: String,
        client_nonce: String,
        mac: String,
    },
    Input {
        session_id: String,
        sequence: u64,
        payload: String,
        mac: String,
    },
    Move { session_id: String, sequence: u64, payload: String, mac: String },
    Ping { session_id: String, sequence: u64, mac: String },
    ControlOpen { session_id: String, nonce: String, mac: String },
    Disconnect {
        session_id: String,
        sequence: u64,
        mac: String,
    },
    VideoOpen {
        session_id: String,
        nonce: String,
        mac: String,
    },
    AudioOpen,
}
#[derive(Clone, Deserialize, Serialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum Input {
    Move {
        dx: i32,
        dy: i32,
    },
    Button {
        button: String,
        down: bool,
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
    Text {
        text: String,
    },
}
fn serve(socket: TcpStream, state: CompanionState) {
    // Windows accepted sockets inherit the nonblocking listener mode. TLS reads
    // must wait for the peer instead of treating WSAEWOULDBLOCK as a failure.
    if let Err(error) = socket.set_nonblocking(false) {
        eprintln!("CoreLink companion socket setup failed: {error}");
        return;
    }
    let _ = socket.set_nodelay(true);
    let c = {
        let i = match state.0.lock() {
            Ok(v) => v,
            Err(_) => return,
        };
        match config(&i.identity) {
            Ok(v) => v,
            Err(_) => return,
        }
    };
    let mut tls = StreamOwned::new(
        match ServerConnection::new(c) {
            Ok(v) => v,
            Err(_) => return,
        },
        socket,
    );
    let _ = tls.sock.set_read_timeout(Some(Duration::from_secs(10)));
    let _ = tls.sock.set_write_timeout(Some(Duration::from_secs(5)));
    let req = {
        let mut r = BufReader::new(&mut tls);
        read(&mut r)
    };
    match req {
        Ok(Request::Enroll {
            token,
            device_id,
            name,
        }) => enroll(&mut tls, state, token, device_id, name),
        Ok(Request::SessionOpen {
            device_id,
            client_nonce,
            mac,
        }) => open(&mut tls, state, device_id, client_nonce, mac),
        Ok(Request::Input {
            session_id,
            sequence,
            payload,
            mac,
        }) => input(&mut tls, state, session_id, sequence, payload, mac),
        Ok(Request::Move { .. }) => {},
        Ok(Request::Ping { .. }) => {},
        Ok(Request::ControlOpen { session_id, nonce, mac }) => {
            let _ = tls.sock.set_read_timeout(Some(SESSION_TTL));
            control(&mut tls, state, session_id, nonce, mac)
        },
        Ok(Request::Disconnect {
            session_id,
            sequence,
            mac,
        }) => disconnect(&mut tls, state, session_id, sequence, mac),
        Ok(Request::VideoOpen {
            session_id,
            nonce,
            mac,
        }) => video(&mut tls, state, session_id, nonce, mac),
        Ok(Request::AudioOpen) => { let _ = send(&mut tls, &serde_json::json!({"type":"audio_disabled"})); },
        Err(error) => { eprintln!("CoreLink companion request error: {error}"); }
    }
}
fn enroll<W: Write>(o: &mut W, s: CompanionState, t: String, id: String, name: String) {
    if id.len() > 96 || name.len() > 80 {
        eprintln!("CoreLink companion enrollment rejected: device ID or name is too long");
        let _ = send(o, &serde_json::json!({"type":"rejected"}));
        return;
    }
    let Ok(t) = URL_SAFE_NO_PAD.decode(t) else {
        eprintln!("CoreLink companion enrollment rejected: malformed credential");
        let _ = send(o, &serde_json::json!({"type":"rejected"}));
        return;
    };
    let secret = random();
    let ok = {
        let mut i = match s.0.lock() {
            Ok(v) => v,
            Err(_) => return,
        };
        if !i.running { false } else { match i.enrollment.as_mut() {
            Some(e) if !e.used && e.expires > Instant::now() && t.as_slice() == e.token => {
                e.used = true;
                let mut updated=i.trusted.clone(); updated.insert(id.clone(), Device { name, secret });
                match save_trust(&i,&updated) {
                    Ok(()) => {
                        if i.sessions.values().any(|session| session.device_id == id) { release_held_buttons(); }
                        i.sessions.retain(|_, session| session.device_id != id);
                        i.trusted=updated; true
                    },
                    Err(error) => { eprintln!("CoreLink could not save pairing: {error}"); false }
                }
            }
            _ => false,
        }}
    };
    if !ok {
        let _ = send(o, &serde_json::json!({"type":"rejected"}));
        return;
    }
    let _ = send(o, &serde_json::json!({"type":"paired","secret":b64(secret),"session_ttl":SESSION_TTL.as_secs()}));
}
fn open<W: Write>(o: &mut W, s: CompanionState, did: String, cn: String, tag: String) {
    if URL_SAFE_NO_PAD
        .decode(&cn)
        .ok()
        .filter(|v| v.len() == 32)
        .is_none()
    {
        let _ = send(o, &serde_json::json!({"type":"session_rejected"}));
        return;
    }
    let r = {
        let mut i = match s.0.lock() {
            Ok(v) => v,
            Err(_) => return,
        };
        if !i.running || i.used_open_nonces.contains(&cn) {
            None
        } else {
            let Some(d) = i.trusted.get(&did) else { return };
            if !check(&d.secret, &transcript(&["session-open", &did, &cn]), &tag) {
                None
            } else {
                let sid = b64(random::<24>());
                let sn = b64(random::<32>());
                let k = session_key(&d.secret, &sid, &cn, &sn);
                let proof = sign(&k, &transcript(&["session-opened", &did, &sid, &sn]));
                if i.sessions.values().any(|x| x.device_id == did) { release_held_buttons(); }
                i.sessions.retain(|_, x| x.device_id != did);
                i.used_open_nonces.insert(cn);
                i.sessions.insert(
                    sid.clone(),
                    Session {
                        device_id: did.clone(),
                        key: k,
                        last_sequence: 0,
                        expires: Instant::now() + SESSION_TTL,
                    },
                );
                Some((sid, sn, proof))
            }
        }
    };
    match r {
        Some((id, sn, m)) => {
            let _ = send(
                o,
                &serde_json::json!({"type":"session_opened","session_id":id,"server_nonce":sn,"expires_in":SESSION_TTL.as_secs(),"mac":m}),
            );
        }
        None => {
            let _ = send(o, &serde_json::json!({"type":"session_rejected"}));
        }
    }
}
fn control<T: Read + Write>(stream: &mut T, state: CompanionState, id: String, nonce: String, tag: String) {
    if URL_SAFE_NO_PAD.decode(&nonce).ok().filter(|v| v.len() == 32).is_none() { return; }
    let accepted = if let Ok(mut inner) = state.0.lock() {
        let ok = inner.running && !inner.used_open_nonces.contains(&nonce) && inner.sessions.get(&id).is_some_and(|session|
            session.expires > Instant::now() && check(&session.key, &transcript(&["control-open", &id, &nonce]), &tag));
        if ok { inner.used_open_nonces.insert(nonce); }
        ok
    } else { false };
    if !accepted { let _ = send(stream, &serde_json::json!({"type":"control_rejected"})); return; }
    if send(stream, &serde_json::json!({"type":"control_ready"})).is_err() { return; }
    let mut reader = BufReader::new(stream);
    loop {
        let request = read::<Request, _>(&mut reader);
        match request {
            Ok(Request::Input { session_id, sequence, payload, mac }) if session_id == id => {
                if !state.0.lock().ok().is_some_and(|inner| inner.running && inner.sessions.get(&id).is_some_and(|s| s.expires > Instant::now())) { break; }
                input(reader.get_mut(), state.clone(), session_id, sequence, payload, mac);
            }
            Ok(Request::Move { session_id, sequence, payload, mac }) if session_id == id => {
                if !matches!(serde_json::from_str::<Input>(&payload), Ok(Input::Move { .. })) || !apply_input(&state, &session_id, sequence, &payload, &mac) { break; }
            }
            Ok(Request::Ping { session_id, sequence, mac }) if session_id == id => {
                let ok=state.0.lock().ok().is_some_and(|mut inner| {
                    if !inner.running { return false; }
                    let Some(session)=inner.sessions.get_mut(&id) else { return false; };
                    if session.expires<=Instant::now() || sequence<=session.last_sequence || !check(&session.key,&transcript(&["ping",&id,&sequence.to_string()]),&mac) { return false; }
                    session.last_sequence=sequence;session.expires=Instant::now()+SESSION_TTL;true
                });
                if send(reader.get_mut(),&serde_json::json!({"type":"pong","ok":ok})).is_err() || !ok { break; }
            }
            _ => break,
        }
    }
    if let Ok(_guard) = state.0.lock() { release_held_buttons(); }
}
fn input<W: Write>(o: &mut W, s: CompanionState, id: String, seq: u64, p: String, tag: String) {
    let ok = apply_input(&s, &id, seq, &p, &tag);
    let _ = send(o, &serde_json::json!({"type":"ack","ok":ok}));
}
fn apply_input(s: &CompanionState, id: &str, seq: u64, p: &str, tag: &str) -> bool {
    {
        let mut i = match s.0.lock() {
            Ok(v) => v,
            Err(_) => return false,
        };
        let now = Instant::now();
        if !i.running { return false; }
        let Some(x) = i.sessions.get_mut(id) else {
            return false;
        };
        if x.expires <= now
            || seq <= x.last_sequence
            || !check(
                &x.key,
                &transcript(&["input", id, &seq.to_string(), p]),
                tag,
            )
        {
            false
        } else if serde_json::from_str::<Input>(p).is_ok_and(|input| dispatch(input).is_ok()) {
            x.last_sequence = seq;
            x.expires = now + SESSION_TTL;
            true
        } else {
            false
        }
    }
}
fn disconnect<W: Write>(o: &mut W, s: CompanionState, id: String, seq: u64, tag: String) {
    let ok = {
        let mut i = match s.0.lock() {
            Ok(v) => v,
            Err(_) => return,
        };
        let Some(x) = i.sessions.get(&id) else { return };
        let valid = x.expires > Instant::now()
            && seq > x.last_sequence
            && check(
                &x.key,
                &transcript(&["disconnect", &id, &seq.to_string()]),
                &tag,
            );
        if valid {
            release_held_buttons();
            let _ = i.sessions.remove(&id);
        }
        valid
    };
    if ok {
        crate::capture::stop_active_capture();
    }
    let _ = send(o, &serde_json::json!({"type":"disconnected","ok":ok}));
}
fn video<W: Write>(o: &mut W, s: CompanionState, id: String, nonce: String, tag: String) {
    if URL_SAFE_NO_PAD
        .decode(&nonce)
        .ok()
        .filter(|v| v.len() == 32)
        .is_none()
    {
        return;
    }
    let authorized = {
        let mut i = match s.0.lock() {
            Ok(v) => v,
            Err(_) => return,
        };
        let valid = i.running
            && !i.used_open_nonces.contains(&nonce)
            && i.sessions.get(&id).is_some_and(|x| {
                x.expires > Instant::now()
                    && check(&x.key, &transcript(&["video-open", &id, &nonce]), &tag)
            });
        if valid {
            i.used_open_nonces.insert(nonce);
        }
        valid
    };
    if !authorized {
        let _ = send(o, &serde_json::json!({"type":"video_rejected"}));
        return;
    }
    let capture = s.0.lock().ok().and_then(|i| i.capture.clone());
    if let Some(capture) = capture {
        capture.clear_finished();
        let snapshot = capture.snapshot();
        if !snapshot.running && !snapshot.starting {
            if let Err(error) = capture.start(0) {
                let current=capture.snapshot();
                if !current.running && !current.starting { let _=send(o,&serde_json::json!({"type":"video_unavailable","message":error})); return; }
            }
        }
        let deadline=Instant::now()+Duration::from_secs(12);
        while crate::capture::latest_frame().is_none() && Instant::now()<deadline {
            let active=s.0.lock().ok().is_some_and(|i|i.running && i.sessions.get(&id).is_some_and(|x|x.expires>Instant::now()));
            if !active { crate::capture::stop_active_capture(); return; }
            let snapshot=capture.snapshot();
            if !snapshot.running && !snapshot.starting { break; }
            thread::sleep(Duration::from_millis(50));
        }
    }
    let first = match crate::capture::latest_frame() {
        Some(f) if f.data.len() <= 8 * 1024 * 1024 => f,
        _ => {
            let _ = send(o, &serde_json::json!({"type":"video_unavailable"}));
            return;
        }
    };
    struct StopCaptureOnDrop(crate::capture::VideoFrame, CompanionState);
    impl Drop for StopCaptureOnDrop {
        fn drop(&mut self) {
            if let Ok(_guard) = self.1.0.lock() { release_held_buttons(); }
            crate::capture::stop_for_frame(&self.0);
        }
    }
    let _stop_on_drop = StopCaptureOnDrop(first.clone(), s.clone());
    if send(o,&serde_json::json!({"type":"video_started","width":first.width,"height":first.height,"format":"bgra-zlib"})).is_err(){return}
    let dimensions = (first.width, first.height);
    let mut last_number = None;
    let mut last_sent = Instant::now() - Duration::from_secs(5);
    loop {
        let active = s.0.lock().ok().is_some_and(|i| {
            i.running
                && i.sessions
                    .get(&id)
                    .is_some_and(|x| x.expires > Instant::now())
        });
        if !active {
            return;
        }
        let Some(frame) = crate::capture::latest_frame() else {
            return;
        };
        if !first.same_capture(&frame)
            || (frame.width, frame.height) != dimensions
            || frame.data.len() > 8 * 1024 * 1024
        {
            return;
        }
        if last_number != Some(frame.number) || last_sent.elapsed() >= Duration::from_secs(5) {
            if o.write_all(&(frame.data.len() as u32).to_be_bytes())
                .and_then(|_| o.write_all(&frame.data))
                .and_then(|_| o.flush())
                .is_err()
            {
                return;
            }
            last_number = Some(frame.number);
            last_sent = Instant::now();
        }
        thread::sleep(Duration::from_millis(5));
    }
}
fn virtual_key(key: &str) -> Option<u8> {
    match key {
        "tab" | "\t" => Some(0x09), "enter" | "\r" | "\n" => Some(0x0d),
        "backspace" | "\u{8}" => Some(0x08), "escape" | "esc" => Some(0x1b),
        "left" => Some(0x25), "up" => Some(0x26), "right" => Some(0x27), "down" => Some(0x28),
        "delete" => Some(0x2e), "volume_up" => Some(0xaf), "volume_down" => Some(0xae),
        "media_play_pause" => Some(0xb3), "media_next" => Some(0xb0), "media_previous" => Some(0xb1), "volume_mute" => Some(0xad),
        _ if key.len() == 1 && key.as_bytes()[0].is_ascii_alphanumeric() => Some(key.as_bytes()[0].to_ascii_uppercase()),
        _ => None,
    }
}
fn dispatch(i: Input) -> Result<(), String> {
    match &i {
        Input::Move { dx, dy } if dx.unsigned_abs() > 5000 || dy.unsigned_abs() > 5000 => {
            Err("movement out of range".into())
        }
        Input::Scroll { dy } if dy.unsigned_abs() > 5000 => Err("scroll out of range".into()),
        Input::Button { button, .. } | Input::Click { button }
            if button != "left" && button != "right" =>
        {
            Err("button not allowed".into())
        }
        Input::Key { key, .. } if virtual_key(key).is_none() => Err("key not allowed".into()),
        Input::Text { text } if text.len() > 2048 => Err("text too long".into()),
        _ => send_input(i),
    }
}
fn release_held_buttons() {
    #[cfg(windows)] unsafe {
        use windows::Win32::UI::Input::KeyboardAndMouse::{mouse_event, MOUSEEVENTF_LEFTUP, MOUSEEVENTF_RIGHTUP};
        if HELD_LEFT.swap(false, Ordering::SeqCst) { mouse_event(MOUSEEVENTF_LEFTUP, 0, 0, 0, 0); }
        if HELD_RIGHT.swap(false, Ordering::SeqCst) { mouse_event(MOUSEEVENTF_RIGHTUP, 0, 0, 0, 0); }
    }
    #[cfg(not(windows))] { HELD_LEFT.store(false, Ordering::SeqCst); HELD_RIGHT.store(false, Ordering::SeqCst); }
}
#[cfg(windows)]
fn send_input(i: Input) -> Result<(), String> {
    use windows::Win32::UI::Input::KeyboardAndMouse::{
        keybd_event, mouse_event, SendInput, INPUT, INPUT_0, INPUT_KEYBOARD, KEYBDINPUT,
        KEYEVENTF_KEYUP, KEYEVENTF_UNICODE, MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP,
        MOUSEEVENTF_MOVE, MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP, MOUSEEVENTF_WHEEL,
        VK_CONTROL, VK_MENU, VK_SHIFT,
    };
    unsafe {
        match i {
            Input::Move { dx, dy } => mouse_event(MOUSEEVENTF_MOVE, dx, dy, 0, 0),
            Input::Scroll { dy } => mouse_event(MOUSEEVENTF_WHEEL, 0, 0, dy, 0),
            Input::Button { button, down } => {
                let f = match (button.as_str(), down) {
                    ("left", true) => MOUSEEVENTF_LEFTDOWN,
                    ("left", false) => MOUSEEVENTF_LEFTUP,
                    ("right", true) => MOUSEEVENTF_RIGHTDOWN,
                    ("right", false) => MOUSEEVENTF_RIGHTUP,
                    _ => return Err("button not allowed".into()),
                };
                mouse_event(f, 0, 0, 0, 0)
                ;if button == "left" { HELD_LEFT.store(down, Ordering::SeqCst); } else { HELD_RIGHT.store(down, Ordering::SeqCst); }
            }
            Input::Click { button } => {
                let (d, u) = if button == "left" {
                    (MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP)
                } else {
                    (MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP)
                };
                mouse_event(d, 0, 0, 0, 0);
                mouse_event(u, 0, 0, 0, 0);
                if button == "left" { HELD_LEFT.store(false, Ordering::SeqCst); } else { HELD_RIGHT.store(false, Ordering::SeqCst); }
            }
            Input::Key {
                key,
                ctrl,
                alt,
                shift,
            } => {
                let code = virtual_key(&key).ok_or("key not allowed")?;
                if ctrl {
                    keybd_event(VK_CONTROL.0 as u8, 0, Default::default(), 0)
                }
                if alt {
                    keybd_event(VK_MENU.0 as u8, 0, Default::default(), 0)
                }
                if shift {
                    keybd_event(VK_SHIFT.0 as u8, 0, Default::default(), 0)
                }
                keybd_event(code, 0, Default::default(), 0);
                keybd_event(code, 0, KEYEVENTF_KEYUP, 0);
                if shift {
                    keybd_event(VK_SHIFT.0 as u8, 0, KEYEVENTF_KEYUP, 0)
                }
                if alt {
                    keybd_event(VK_MENU.0 as u8, 0, KEYEVENTF_KEYUP, 0)
                }
                if ctrl {
                    keybd_event(VK_CONTROL.0 as u8, 0, KEYEVENTF_KEYUP, 0)
                }
            }
            Input::Text { text } => {
                for c in text.encode_utf16() {
                    let down = INPUT {
                        r#type: INPUT_KEYBOARD,
                        Anonymous: INPUT_0 {
                            ki: KEYBDINPUT {
                                wScan: c,
                                dwFlags: KEYEVENTF_UNICODE,
                                ..Default::default()
                            },
                        },
                    };
                    let up = INPUT {
                        r#type: INPUT_KEYBOARD,
                        Anonymous: INPUT_0 {
                            ki: KEYBDINPUT {
                                wScan: c,
                                dwFlags: KEYEVENTF_UNICODE | KEYEVENTF_KEYUP,
                                ..Default::default()
                            },
                        },
                    };
                    if SendInput(&[down, up], std::mem::size_of::<INPUT>() as i32) != 2 {
                        return Err("Windows could not send text input".into());
                    }
                }
            }
        }
    };
    Ok(())
}
#[cfg(not(windows))]
fn send_input(_: Input) -> Result<(), String> {
    Ok(())
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn encrypted_trust_survives_restart_and_revocation() {
        let path=std::env::temp_dir().join(format!("corelink-trust-test-{}.dat",b64(random::<16>())));
        let first=CompanionState::persistent(path.clone(),Arc::new(crate::capture::CaptureState::default())).unwrap();
        let token=[41u8;32];
        let pin={let mut i=first.0.lock().unwrap();i.running=true;i.enrollment=Some(Enrollment{token,expires:Instant::now()+ENROLLMENT_TTL,expires_at:unix()+120,used:false});i.identity.pin.clone()};
        let mut response=Vec::new();enroll(&mut response,first.clone(),b64(token),"phone".into(),"Phone".into());
        let paired:serde_json::Value=serde_json::from_slice(&response).unwrap();assert_eq!(paired["type"],"paired");
        let raw=std::fs::read(&path).unwrap();assert!(!raw.windows(5).any(|window|window==b"phone"));
        let second=CompanionState::persistent(path.clone(),Arc::new(crate::capture::CaptureState::default())).unwrap();
        {let i=second.0.lock().unwrap();assert_eq!(i.identity.pin,pin);assert!(i.trusted.contains_key("phone"));}
        second.revoke("phone").unwrap();
        let third=CompanionState::persistent(path.clone(),Arc::new(crate::capture::CaptureState::default())).unwrap();
        assert!(!third.0.lock().unwrap().trusted.contains_key("phone"));
        std::fs::remove_file(path).unwrap();
    }
    #[test]
    fn control_over_tls_processes_ordered_inputs_and_closes() {
        use rustls::{ClientConfig,ClientConnection,RootCertStore,pki_types::ServerName};
        let state=CompanionState::default(); let key=[8;32];
        let cert={let mut i=state.0.lock().unwrap();i.running=true;i.sessions.insert("s".into(),Session{device_id:"d".into(),key,last_sequence:0,expires:Instant::now()+SESSION_TTL});i.identity.cert.clone()};
        let listener=TcpListener::bind("127.0.0.1:0").unwrap(); let addr=listener.local_addr().unwrap();
        let server=state.clone(); let worker=thread::spawn(move || { let (socket,_)=listener.accept().unwrap(); serve(socket,server); });
        let mut roots=RootCertStore::empty();roots.add(cert).unwrap();
        let config=ClientConfig::builder().with_root_certificates(roots).with_no_client_auth();
        let socket=TcpStream::connect(addr).unwrap();socket.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
        let conn=ClientConnection::new(Arc::new(config),ServerName::try_from("CoreLink.local").unwrap().to_owned()).unwrap();
        let mut tls=StreamOwned::new(conn,socket);let nonce=b64([4u8;32]);
        send(&mut tls,&serde_json::json!({"type":"control_open","session_id":"s","nonce":nonce,"mac":sign(&key,&transcript(&["control-open","s",&nonce]))})).unwrap();
        let reply:serde_json::Value=read(&mut BufReader::new(&mut tls)).unwrap();assert_eq!(reply["type"],"control_ready");
        for seq in [1u64,2] {let payload=r#"{"kind":"move","dx":0,"dy":0}"#; let tag=sign(&key,&transcript(&["input","s",&seq.to_string(),payload]));
            send(&mut tls,&serde_json::json!({"type":"move","session_id":"s","sequence":seq,"payload":payload,"mac":tag})).unwrap();
        }
        for seq in [3u64,4] {let payload=r#"{"kind":"text","text":""}"#; let tag=sign(&key,&transcript(&["input","s",&seq.to_string(),payload]));
            send(&mut tls,&serde_json::json!({"type":"input","session_id":"s","sequence":seq,"payload":payload,"mac":tag})).unwrap();
            let response:serde_json::Value=read(&mut BufReader::new(&mut tls)).unwrap();assert_eq!(response["ok"],true);
        }
        drop(tls);worker.join().unwrap();
        assert_eq!(state.0.lock().unwrap().sessions.get("s").unwrap().last_sequence,4);
    }
    #[test]
    fn persistent_control_authenticates_and_rejects_nonce_replay() {
        struct Duplex { incoming: std::io::Cursor<Vec<u8>>, outgoing: Vec<u8> }
        impl Read for Duplex { fn read(&mut self, data: &mut [u8]) -> std::io::Result<usize> { self.incoming.read(data) } }
        impl Write for Duplex {
            fn write(&mut self, data: &[u8]) -> std::io::Result<usize> { self.outgoing.extend_from_slice(data); Ok(data.len()) }
            fn flush(&mut self) -> std::io::Result<()> { Ok(()) }
        }
        let state=CompanionState::default(); let key=[8;32];
        { let mut i=state.0.lock().unwrap(); i.running=true; i.sessions.insert("s".into(),Session{device_id:"phone".into(),key,last_sequence:0,expires:Instant::now()+SESSION_TTL}); }
        let nonce=b64([9u8;32]); let tag=sign(&key,&transcript(&["control-open","s",&nonce]));
        let payload=r#"{"kind":"text","text":""}"#;
        let input_tag=sign(&key,&transcript(&["input","s","1",payload]));
        let wire=format!("{}\n",serde_json::json!({"type":"input","session_id":"s","sequence":1,"payload":payload,"mac":input_tag}));
        let mut stream=Duplex{incoming:std::io::Cursor::new(wire.into_bytes()),outgoing:Vec::new()};
        control(&mut stream,state.clone(),"s".into(),nonce.clone(),tag.clone());
        let replies:Vec<serde_json::Value>=String::from_utf8(stream.outgoing).unwrap().lines().map(|line|serde_json::from_str(line).unwrap()).collect();
        assert_eq!(replies[0]["type"],"control_ready"); assert_eq!(replies[1]["ok"],true);
        let mut replay=Duplex{incoming:std::io::Cursor::new(Vec::new()),outgoing:Vec::new()};
        control(&mut replay,state,"s".into(),nonce,tag);
        assert_eq!(serde_json::from_slice::<serde_json::Value>(&replay.outgoing).unwrap()["type"],"control_rejected");
    }
    #[test]
    fn supported_keys_map_to_single_virtual_keys() {
        assert_eq!(virtual_key("tab"), Some(0x09));
        assert_eq!(virtual_key("\t"), Some(0x09));
        assert_eq!(virtual_key("enter"), Some(0x0d));
        assert_eq!(virtual_key("backspace"), Some(0x08));
        assert_eq!(virtual_key("escape"), Some(0x1b));
        assert_eq!(virtual_key("left"), Some(0x25));
        assert_eq!(virtual_key("up"), Some(0x26));
        assert_eq!(virtual_key("right"), Some(0x27));
        assert_eq!(virtual_key("down"), Some(0x28));
        assert_eq!(virtual_key("delete"), Some(0x2e));
        assert_eq!(virtual_key("media_play_pause"), Some(0xb3)); assert_eq!(virtual_key("media_next"), Some(0xb0)); assert_eq!(virtual_key("media_previous"), Some(0xb1)); assert_eq!(virtual_key("volume_mute"), Some(0xad));
        assert_eq!(virtual_key("volume_up"), Some(0xaf));
        assert_eq!(virtual_key("volume_down"), Some(0xae));
        assert_eq!(virtual_key("a"), Some(b'A'));
        assert_eq!(virtual_key("F13"), None);
        assert_eq!(virtual_key("é"), None);
    }
    #[test]
    fn tls_enrollment_pairs_immediately() {
        use rustls::{ClientConfig, ClientConnection, RootCertStore, pki_types::ServerName};
        let state=CompanionState::default();
        let token=[42;32];
        let cert={let mut i=state.0.lock().unwrap(); i.running=true; i.enrollment=Some(Enrollment{token,expires:Instant::now()+ENROLLMENT_TTL,expires_at:unix()+120,used:false}); i.identity.cert.clone()};
        let listener=TcpListener::bind("127.0.0.1:0").unwrap();
        let address=listener.local_addr().unwrap();
        let server=state.clone();
        let worker=thread::spawn(move || {let (socket,_)=listener.accept().unwrap();serve(socket,server)});
        let mut roots=RootCertStore::empty();roots.add(cert).unwrap();
        let config=ClientConfig::builder().with_root_certificates(roots).with_no_client_auth();
        let socket=TcpStream::connect(address).unwrap();socket.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
        let connection=ClientConnection::new(Arc::new(config),ServerName::try_from("CoreLink.local").unwrap().to_owned()).unwrap();
        let mut tls=StreamOwned::new(connection,socket);
        send(&mut tls,&serde_json::json!({"type":"enroll","token":b64(token),"device_id":"test-phone","name":"Test phone"})).unwrap();
        let response:serde_json::Value=read(&mut BufReader::new(&mut tls)).unwrap();
        assert_eq!(response["type"],"paired");
        assert_eq!(state.snapshot().trusted_devices.len(),1);
        state.stop();worker.join().unwrap();
    }
    #[test]
    fn qr_pairing_is_immediate_and_one_time() {
        let state=CompanionState::default(); let token=[42;32];
        { let mut i=state.0.lock().unwrap(); i.running=true; i.enrollment=Some(Enrollment{token,expires:Instant::now()+ENROLLMENT_TTL,expires_at:unix()+120,used:false}); }
        let mut first=Vec::new(); enroll(&mut first,state.clone(),b64(token),"phone".into(),"Phone".into());
        assert_eq!(serde_json::from_slice::<serde_json::Value>(&first).unwrap()["type"],"paired");
        assert_eq!(state.snapshot().trusted_devices.len(),1);
        let mut replay=Vec::new(); enroll(&mut replay,state,b64(token),"other".into(),"Other".into());
        assert_eq!(serde_json::from_slice::<serde_json::Value>(&replay).unwrap()["type"],"rejected");
    }
    #[test]
    fn input_authenticates_exact_payload_and_rejects_replay() {
        let state = CompanionState::default();
        let key = [7; 32];
        state.0.lock().unwrap().running = true;
        state.0.lock().unwrap().sessions.insert(
            "s".into(),
            Session {
                device_id: "d".into(),
                key,
                last_sequence: 0,
                expires: Instant::now() + SESSION_TTL,
            },
        );
        let payload = r#"{ "text" : "", "kind" : "text" }"#.to_string();
        let tag = sign(&key, &transcript(&["input", "s", "1", &payload]));
        let mut reply = Vec::new();
        input(
            &mut reply,
            state.clone(),
            "s".into(),
            1,
            payload.clone(),
            tag.clone(),
        );
        assert!(
            serde_json::from_slice::<serde_json::Value>(&reply).unwrap()["ok"]
                .as_bool()
                .unwrap()
        );
        reply.clear();
        input(&mut reply, state, "s".into(), 1, payload, tag);
        assert!(
            !serde_json::from_slice::<serde_json::Value>(&reply).unwrap()["ok"]
                .as_bool()
                .unwrap()
        );
    }
    #[test]
    fn message_reader_rejects_oversize_and_unterminated_messages() {
        let mut huge = std::io::Cursor::new(vec![b'x'; MAX_LINE + 100]);
        assert!(read::<serde_json::Value, _>(&mut huge).is_err());
        assert_eq!(huge.position(), (MAX_LINE + 1) as u64);
        let mut partial = std::io::Cursor::new(br#"{}"#.to_vec());
        assert!(read::<serde_json::Value, _>(&mut partial).is_err());
    }
    #[test]
    fn regenerating_qr_invalidates_pending_but_keeps_sessions() {
        let state = CompanionState::default();
        {
            let mut i = state.0.lock().unwrap();
            i.running = true;
            i.pending.insert(
                "p".into(),
                Pending {
                    request: PairRequest {
                        request_id: "p".into(),
                        name: "n".into(),
                        device_id: "d".into(),
                    },
                    secret: [1; 32],
                    created: Instant::now(),
                },
            );
            i.sessions.insert(
                "s".into(),
                Session {
                    device_id: "d".into(),
                    key: [2; 32],
                    last_sequence: 0,
                    expires: Instant::now() + SESSION_TTL,
                },
            );
        }
        state.regenerate_enrollment().unwrap();
        let i = state.0.lock().unwrap();
        assert!(i.pending.is_empty());
        assert!(i.sessions.contains_key("s"));
        assert!(!i.enrollment.as_ref().unwrap().used);
    }
    #[test]
    fn pairing_approval_creates_a_trusted_credential() {
        let state = CompanionState::default();
        let request = PairRequest {
            request_id: "pair".into(),
            name: "Samsung".into(),
            device_id: "phone".into(),
        };
        state.0.lock().unwrap().pending.insert(
            "pair".into(),
            Pending {
                request,
                secret: [9; 32],
                created: Instant::now(),
            },
        );
        state.approve("pair").unwrap();
        assert_eq!(
            state.0.lock().unwrap().trusted.get("phone").unwrap().secret,
            [9; 32]
        );
    }
    #[test]
    fn reconnect_keys_are_independent() {
        let d = [7; 32];
        assert_ne!(
            session_key(&d, "one", "a", "b"),
            session_key(&d, "two", "c", "d")
        )
    }
    #[test]
    fn replay_from_terminated_session_fails() {
        let d = [7; 32];
        let a = session_key(&d, "old", "a", "b");
        let b = session_key(&d, "new", "c", "d");
        let t = sign(&a, &transcript(&["input", "old", "1", "{}"]));
        assert!(!check(&b, &transcript(&["input", "new", "1", "{}"]), &t))
    }
    #[test]
    fn invalid_credentials_cannot_open_or_control() {
        let good = [1; 32];
        let bad = [2; 32];
        let open = sign(&good, &transcript(&["session-open", "phone", "nonce"]));
        assert!(!check(
            &bad,
            &transcript(&["session-open", "phone", "nonce"]),
            &open
        ));
    }
    #[test]
    fn revocation_removes_every_active_session() {
        let state = CompanionState::default();
        {
            let mut i = state.0.lock().unwrap();
            i.trusted.insert(
                "phone".into(),
                Device {
                    name: "Samsung".into(),
                    secret: [3; 32],
                },
            );
            i.sessions.insert(
                "one".into(),
                Session {
                    device_id: "phone".into(),
                    key: [4; 32],
                    last_sequence: 0,
                    expires: Instant::now() + SESSION_TTL,
                },
            );
        }
        state.revoke("phone").unwrap();
        let i = state.0.lock().unwrap();
        assert!(!i.trusted.contains_key("phone"));
        assert!(i.sessions.is_empty());
    }
    #[test]
    fn expired_sessions_are_not_active() {
        let state = CompanionState::default();
        state.0.lock().unwrap().sessions.insert(
            "old".into(),
            Session {
                device_id: "phone".into(),
                key: [4; 32],
                last_sequence: 0,
                expires: Instant::now() - Duration::from_secs(1),
            },
        );
        assert_eq!(state.snapshot().active_sessions, 0);
    }
    #[test]
    fn disconnect_is_authenticated() {
        let k = [1; 32];
        let t = sign(&k, &transcript(&["disconnect", "s", "2"]));
        assert!(check(&k, &transcript(&["disconnect", "s", "2"]), &t));
        assert!(!check(&k, &transcript(&["disconnect", "s", "3"]), &t))
    }
    #[test]
    fn input_bounds_hold() {
        assert!(dispatch(Input::Move { dx: 5001, dy: 0 }).is_err())
    }
}
