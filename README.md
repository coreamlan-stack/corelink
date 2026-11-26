# CoreLink · Windows ↔ Samsung (Phase 1)

A real, local-first Windows desktop workspace for mirroring and controlling Android phones using **ADB + scrcpy**. Written with **Tauri 2, Rust, React JSX and Tailwind CSS 4**. This is Phase 1 only: PC control from phone is **not implemented**.

## What works

- Detects installed `adb.exe` and `scrcpy.exe` via Windows `PATH`, reports real availability.
- Displays actual `adb devices -l` results (authorized/unauthorized/offline) without sample devices.
- Wireless ADB pairing (`adb pair`) and explicit LAN connection (`adb connect`). Addresses are restricted to local/private IPv4.
- Starts and stops an actual scrcpy process using the selected authorized device, FPS and bitrate.
- Starts CoreLink at Windows login on first application launch; **does not start mirroring** or automatically grant debugging access.
- USB fallback: plug in your phone and authorize ADB, then select the device.
- Only local/private network endpoints are accepted by the app. No cloud service, telemetry, account or public port forwarding.

The mirrored Android screen opens in **scrcpy's own native window**. It is not embedded in the Tauri window in Phase 1. CoreLink attempts to stop its managed scrcpy process when the application shuts down normally.

## Prerequisites (Windows 10/11)

1. Install Node.js 22+, Rust stable with MSVC tools, Visual Studio C++ Build Tools (Desktop development with C++), and WebView2 runtime (included in most recent Windows versions).
2. Install **Android SDK Platform-Tools** from https://developer.android.com/tools/releases/platform-tools and **scrcpy** from https://github.com/Genymobile/scrcpy/releases . Download from official release pages.
3. Make the directories containing `adb.exe` and `scrcpy.exe` available on Windows `PATH`. Check in PowerShell:

   ```powershell
   where.exe adb
   where.exe scrcpy
   adb version
   scrcpy --version
   ```

   Restart CoreLink after modifying PATH. For packaged installs, ADB and scrcpy remain external prerequisites and are not redistributed.

## Run locally

```powershell
npm install
npm test
npm run desktop:dev
```

The first launch attempts to register Windows autostart. The settings panel shows success or failure. To disable later, change Windows Settings → Apps → Startup (or Task Manager → Startup apps).

## Samsung wireless setup

1. On Samsung, open Settings → About phone → Software information and tap Build number seven times (if Developer options are not already enabled).
2. Open Settings → Developer options → Wireless debugging, enable it, and ensure the PC and Samsung are on the **same trusted Wi-Fi LAN**.
3. Tap **Pair device with pairing code**, enter the displayed **pairing IP:port** and six-digit code in CoreLink, then select **Pair device**.
4. Return to the Wireless debugging main screen and find the **IP address & port** for the actual ADB connection. Enter this potentially **different port** under **Connection IP and port**, then click **Connect over Wi-Fi**.
5. Refresh if necessary; select the device marked **Ready**; click **Mirror phone**.
6. If wireless pairing is unavailable, connect USB, enable USB debugging, accept your phone's authorization prompt, select the detected device and mirror.

Some Samsung firmware, Wi-Fi networks, and corporate access points disallow wireless debugging discovery; manual IP addresses are supported. Never enable wireless debugging on untrusted networks.

## Build installer

```powershell
npm install
npm test
npm run desktop:build
```

The NSIS installer is produced under `src-tauri/target/release/bundle/nsis/`. Windows signing is **not** configured. Unsigned installers may trigger Windows SmartScreen warnings. No binary is provided until Windows CI successfully builds it.

## Automated checks

- `npm test`: IP/port validation and device state display helpers.
- `cargo test --manifest-path src-tauri/Cargo.toml`: ADB parser and private network filtering.
- `.github/workflows/windows.yml`: Windows CI runs both test suites and builds the installer.
- Manual hardware tests required for Windows PATH discovery, Samsung ADB pairing, scrcpy launch, input latency, reconnect, high-DPI display, sleep/resume and autostart.

## Security notes

- ADB pairing codes are passed directly to the local ADB CLI; no logs or persistent pairing code storage in CoreLink.
- Android authorization and Wireless debugging must be explicitly approved on the phone.
- CoreLink's explicit connection inputs reject public IPs; ADB and scrcpy can still be used independently outside CoreLink and must be secured separately.
- Commands do not run through a shell. Selected serial must match an ADB-reported authorized device.
- This is an **early prototype**: review ADB version compatibility, installer signing, session shutdown and network trust before distributing broadly.

## Repository setup

GitHub repository creation is not available via the current connected GitHub actions. Create a **private**, empty repository named `corelink` under `coreamlan-stack` in the GitHub UI, then from this directory:

```powershell
git init
git add .
git commit -m "feat: bootstrap CoreLink Phase 1 Android mirroring"
git branch -M main
git remote add origin https://github.com/coreamlan-stack/corelink.git
git push -u origin main
```

**Do not** add a GitHub-generated README or .gitignore when creating the empty repository. This project already includes both.

## Phase 2 companion input

`apps/android/` contains the native Kotlin + Jetpack Compose companion. Its touchpad connects only to an owner-started CoreLink companion service on a private LAN. Pairing is a short-lived six-digit proof plus explicit Windows approval; input packets use AES-256-GCM, expire after 15 minutes, and are validated before Windows input is sent. Stopping the service revokes all active sessions immediately.

The initial transport deliberately uses manual private-IP entry instead of pretending a PC was discovered. It does not capture a screen and it does not accept public-network connections. See [Companion setup](docs/COMPANION_SETUP.md) and the separate [Windows video streaming plan](docs/WINDOWS_VIDEO_STREAMING_PLAN.md).

### Android build

Install Android Studio with Android SDK Platform 35, then open `apps/android/` and run the `app` debug build. The APK is emitted at `apps/android/app/build/outputs/apk/debug/app-debug.apk`. Android builds require an installed SDK/Gradle; they cannot be produced from a Windows machine that only has Java.

### Quick Windows prerequisite audit

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\check-windows.ps1
```
