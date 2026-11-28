//! Windows user-bound encryption for the desktop identity and paired devices.
use serde::{Deserialize, Serialize};
use std::{collections::HashMap, path::Path};
use windows::{core::PCWSTR, Win32::{Foundation::{HLOCAL, LocalFree}, Security::Cryptography::{CryptProtectData, CryptUnprotectData, CRYPT_INTEGER_BLOB, CRYPTPROTECT_UI_FORBIDDEN}, Storage::FileSystem::{MoveFileExW, MOVEFILE_REPLACE_EXISTING, MOVEFILE_WRITE_THROUGH}}};

#[derive(Clone, Serialize, Deserialize)]
pub struct StoredDevice { pub name: String, pub secret: [u8; 32] }
#[derive(Serialize, Deserialize)]
pub struct StoredTrust { pub version: u32, pub certificate: Vec<u8>, pub private_key: Vec<u8>, pub devices: HashMap<String, StoredDevice> }

fn crypt(data: &[u8], encrypt: bool) -> Result<Vec<u8>, String> {
    let input = CRYPT_INTEGER_BLOB { cbData: data.len().try_into().map_err(|_| "Trust data too large")?, pbData: data.as_ptr() as *mut u8 };
    let mut output = CRYPT_INTEGER_BLOB::default();
    unsafe {
        if encrypt { CryptProtectData(&input, PCWSTR::null(), None, None, None, CRYPTPROTECT_UI_FORBIDDEN, &mut output) }
        else { CryptUnprotectData(&input, None, None, None, None, CRYPTPROTECT_UI_FORBIDDEN, &mut output) }
            .map_err(|e| format!("Windows credential protection failed: {e}"))?;
        let result = std::slice::from_raw_parts(output.pbData, output.cbData as usize).to_vec();
        let _ = LocalFree(HLOCAL(output.pbData.cast()));
        Ok(result)
    }
}

pub fn load(path: &Path) -> Result<Option<StoredTrust>, String> {
    let encrypted = match std::fs::read(path) { Ok(v) => v, Err(e) if e.kind()==std::io::ErrorKind::NotFound => return Ok(None), Err(e) => return Err(format!("Cannot read paired devices: {e}")) };
    if encrypted.len()>1024*1024 { return Err("Paired device storage is too large".into()); }
    let mut plain=crypt(&encrypted,false)?;
    let parsed=serde_json::from_slice::<StoredTrust>(&plain).map_err(|e|format!("Invalid paired device storage: {e}"));
    plain.fill(0);
    let saved=parsed?;
    if saved.version != 1 || saved.certificate.is_empty() || saved.private_key.is_empty() { return Err("Unsupported paired device storage".into()); }
    Ok(Some(saved))
}

pub fn save(path: &Path, value: &StoredTrust) -> Result<(), String> {
    use std::{io::Write, os::windows::ffi::OsStrExt};
    let parent=path.parent().ok_or("Invalid credential storage path")?;
    std::fs::create_dir_all(parent).map_err(|e|format!("Cannot create credential directory: {e}"))?;
    let mut plain=serde_json::to_vec(value).map_err(|e|e.to_string())?;
    let encrypted=crypt(&plain,true); plain.fill(0); let encrypted=encrypted?;
    let temp=path.with_extension(format!("tmp-{:016x}",rand::random::<u64>()));
    let result=(|| -> Result<(), String> {
        let mut file=std::fs::OpenOptions::new().write(true).create_new(true).open(&temp).map_err(|e|e.to_string())?;
        file.write_all(&encrypted).and_then(|_|file.sync_all()).map_err(|e|e.to_string())?;
        let from:Vec<u16>=temp.as_os_str().encode_wide().chain(Some(0)).collect();
        let to:Vec<u16>=path.as_os_str().encode_wide().chain(Some(0)).collect();
        unsafe { MoveFileExW(PCWSTR(from.as_ptr()), PCWSTR(to.as_ptr()), MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH) }.map_err(|e|e.to_string())?;
        Ok(())
    })();
    if result.is_err() { let _=std::fs::remove_file(&temp); }
    result.map_err(|e|format!("Cannot save paired devices: {e}"))
}
