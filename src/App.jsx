import React, { useCallback, useEffect, useState } from 'react';
import { invoke } from '@tauri-apps/api/core';
import { enable, isEnabled } from '@tauri-apps/plugin-autostart';
import { MonitorSmartphone, Wifi, Usb, RefreshCcw, Play, Square, Settings2, CheckCircle2, AlertTriangle, Smartphone, Terminal, Power, LockKeyhole } from 'lucide-react';
import { validEndpoint, normalizeDeviceState } from './validation.js';

const INITIAL = { adb: { found: false, path: null }, scrcpy: { found: false, path: null }, devices: [], mirroring: false };
const errorMessage = (error) => typeof error === 'string' ? error : (error?.message || String(error));

export default function App() {
  const [snapshot, setSnapshot] = useState(INITIAL);
  const [loading, setLoading] = useState(false);
  const [action, setAction] = useState('');
  const [error, setError] = useState('');
  const [info, setInfo] = useState('');
  const [endpoint, setEndpoint] = useState('');
  const [pairAddress, setPairAddress] = useState('');
  const [pairCode, setPairCode] = useState('');
  const [selected, setSelected] = useState('');
  const [fps, setFps] = useState(60);
  const [bitrate, setBitrate] = useState(8);
  const [autostart, setAutostart] = useState(null);

  const refresh = useCallback(async (quiet = false) => {
    if (!quiet) setLoading(true);
    try {
      const result = await invoke('get_snapshot');
      setSnapshot(result);
      setSelected(previous => result.devices.some(device => device.serial === previous) ? previous : (result.devices.find(device => device.state === 'device')?.serial || ''));
      if (!quiet) setError('');
    } catch (e) { if (!quiet) setError(errorMessage(e)); }
    finally { if (!quiet) setLoading(false); }
  }, []);

  useEffect(() => {
    refresh();
    const interval = setInterval(() => refresh(true), 3500);
    return () => clearInterval(interval);
  }, [refresh]);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const current = await isEnabled();
        if (!current) await enable(); // User requested Windows autostart, initialized on first launch.
        if (!cancelled) setAutostart(true);
      } catch (e) {
        if (!cancelled) { setAutostart(false); setInfo(`Windows autostart could not be enabled: ${errorMessage(e)}`); }
      }
    })();
    return () => { cancelled = true; };
  }, []);

  async function perform(label, command, args = {}) {
    setAction(label); setError(''); setInfo('');
    try {
      const result = await invoke(command, args);
      if (typeof result === 'string' && result.trim()) setInfo(result.trim());
      await refresh(true);
    } catch (e) { setError(errorMessage(e)); }
    finally { setAction(''); }
  }

  const ready = snapshot.devices.filter(d => d.state === 'device');
  const available = snapshot.adb.found && snapshot.scrcpy.found;
  const selectedReady = ready.some(d => d.serial === selected);
  return (
    <div className="mx-auto max-w-5xl px-5 py-7 md:px-10 md:py-9">
      <header className="flex flex-wrap items-center justify-between gap-4 border-b border-zinc-800 pb-6">
        <div className="flex items-center gap-3"><div className="rounded-xl bg-orange-500 p-3 text-zinc-950"><MonitorSmartphone size={23}/></div><div><h1 className="text-xl font-semibold tracking-tight">CoreLink</h1><p className="small muted">Device workspace · Phase 1</p></div></div>
        <div className="flex gap-2 items-center"><span className="flex items-center gap-1.5 rounded-full border border-zinc-800 px-3 py-1.5 text-xs text-zinc-300"><LockKeyhole size={13}/> Local-only</span><button className="btn btn-subtle" onClick={() => refresh()} disabled={loading || !!action}><RefreshCcw size={15} className={loading ? 'animate-spin' : ''}/> Refresh</button></div>
      </header>
      <div className="mt-8 flex flex-wrap items-end justify-between gap-3"><div><p className="small muted uppercase tracking-widest">CONNECTED DEVICES</p><h2 className="mt-1 text-2xl font-semibold tracking-tight">Your Workspace</h2><p className="mt-2 muted text-sm">Mirror and control your real Samsung screen through ADB and scrcpy.</p></div><span className="text-xs text-zinc-400">{ready.length} authorized device{ready.length === 1 ? '' : 's'}</span></div>
      {(error || info) && <div role={error ? 'alert' : 'status'} className={`mt-5 rounded-xl border px-4 py-3 text-sm ${error ? 'border-red-900 bg-red-950/40 text-red-200' : 'border-zinc-700 bg-zinc-900 text-zinc-200'}`}>{error || info}</div>}
      <section className="mt-6 grid gap-4 md:grid-cols-2" aria-label="Tools">
        {[{name:'ADB',result:snapshot.adb,desc:'Android Debug Bridge'},{name:'scrcpy',result:snapshot.scrcpy,desc:'Android display and control'}].map(tool=><div className="surface p-5" key={tool.name}><div className="flex items-center justify-between"><div className="flex items-center gap-2"><Terminal size={18} className="text-orange-400"/><strong>{tool.name}</strong></div><span className={`text-xs ${tool.result.found ? 'text-emerald-400' : 'text-amber-400'}`}>{tool.result.found ? 'Detected' : 'Missing'}</span></div><p className="mt-1 small muted">{tool.desc}</p><p className="mt-3 break-all text-xs text-zinc-400">{tool.result.path || 'Install and add to PATH. See README.'}</p></div>)}
      </section>
      <section className="surface mt-4 p-5 md:p-6"><div className="flex items-center justify-between gap-3"><div><h3 className="font-semibold">Available Android devices</h3><p className="small muted mt-1">Only ADB-reported devices are shown. Nothing is simulated.</p></div><Smartphone className="text-zinc-400" size={21}/></div>
        {snapshot.devices.length === 0 ? <div className="mt-5 rounded-xl border border-dashed border-zinc-700 p-7 text-center"><Smartphone size={28} className="mx-auto text-zinc-500"/><p className="mt-3 text-sm font-medium">No Android devices detected</p><p className="small muted mt-1">Pair over Wireless debugging or connect by USB and authorize this computer.</p></div> : <div className="mt-5 space-y-2">{snapshot.devices.map(d=><label key={d.serial} className={`flex cursor-pointer items-center gap-3 rounded-xl border p-4 ${selected===d.serial ? 'border-orange-500/70 bg-orange-500/5':'border-zinc-800 bg-zinc-950'}`}><input type="radio" name="device" value={d.serial} checked={selected===d.serial} onChange={()=>setSelected(d.serial)} className="accent-orange-500"/><Smartphone size={19}/><div className="min-w-0 flex-1"><p className="truncate text-sm font-medium">{d.model || d.serial}</p><p className="small muted break-all">{d.serial}</p></div><span className={`text-xs ${d.state==='device'?'text-emerald-400':'text-amber-400'}`}>{normalizeDeviceState(d.state)}</span></label>)}</div>}
        <div className="mt-5 flex flex-wrap gap-2"><button className="btn btn-primary" disabled={!available || !selectedReady || !!action || snapshot.mirroring} onClick={()=>perform('start','start_mirroring',{serial:selected,fps,bitrate})}><Play size={15}/> {snapshot.mirroring ? 'Mirroring active' : 'Mirror phone'}</button><button className="btn btn-subtle" disabled={!snapshot.mirroring || !!action} onClick={()=>perform('stop','stop_mirroring')}><Square size={15}/> Stop mirroring</button></div>
        <p className="small muted mt-3">Mirroring opens a native scrcpy window. Fullscreen and orientation controls are available there. CoreLink never shows a fake video preview.</p>
      </section>
      <div className="mt-4 grid gap-4 lg:grid-cols-2">
        <section className="surface p-5 md:p-6"><div className="flex items-center gap-2"><Wifi size={19} className="text-orange-400"/><h3 className="font-semibold">Wi-Fi pairing</h3></div><p className="small muted mt-2">On Samsung: Developer options → Wireless debugging → Pair device with pairing code. Enter the pairing address and six-digit code.</p><form className="mt-4 space-y-3" onSubmit={e=>{e.preventDefault();perform('pair','pair_device',{address:pairAddress,code:pairCode});setPairCode('');}}><label className="block text-xs text-zinc-300" htmlFor="pair-address">Pairing IP and port</label><input id="pair-address" className="field" placeholder="192.168.1.12:37145" value={pairAddress} onChange={e=>setPairAddress(e.target.value.trim())} autoComplete="off"/><label className="block text-xs text-zinc-300" htmlFor="pair-code">Pairing code</label><input id="pair-code" className="field" placeholder="Six-digit code" inputMode="numeric" maxLength={6} value={pairCode} onChange={e=>setPairCode(e.target.value.replace(/\D/g,'').slice(0,6))} autoComplete="off"/><button className="btn btn-subtle w-full" disabled={!validEndpoint(pairAddress)||!/^[0-9]{6}$/.test(pairCode)||!!action} type="submit"><LockKeyhole size={15}/> Pair device</button></form><div className="my-5 border-t border-zinc-800"/><label className="block text-xs text-zinc-300" htmlFor="endpoint">Connection IP and port (shown on Wireless debugging main screen)</label><input id="endpoint" className="field mt-2" placeholder="192.168.1.12:39451" value={endpoint} onChange={e=>setEndpoint(e.target.value.trim())} autoComplete="off"/><button className="btn btn-subtle mt-3 w-full" disabled={!validEndpoint(endpoint)||!!action} onClick={()=>perform('connect','connect_device',{address:endpoint})}><Wifi size={15}/> Connect over Wi-Fi</button><p className="small muted mt-3">The pairing and connection ports are often different. Both devices must be on the same trusted LAN.</p></section>
        <section className="surface p-5 md:p-6"><div className="flex items-center gap-2"><Settings2 size={19} className="text-orange-400"/><h3 className="font-semibold">Mirror settings</h3></div><p className="small muted mt-2">Applied when the next scrcpy session starts.</p><label className="mt-5 block text-xs text-zinc-300" htmlFor="fps">Maximum frame rate</label><select id="fps" className="field mt-2" value={fps} onChange={e=>setFps(Number(e.target.value))}>{[30,60,90,120].map(v=><option key={v} value={v}>{v} FPS</option>)}</select><label className="mt-4 block text-xs text-zinc-300" htmlFor="bitrate">Video bitrate</label><select id="bitrate" className="field mt-2" value={bitrate} onChange={e=>setBitrate(Number(e.target.value))}>{[2,4,8,12,16].map(v=><option key={v} value={v}>{v} Mbps</option>)}</select><div className="mt-6 space-y-3 rounded-xl border border-zinc-800 bg-zinc-950 p-4"><div className="flex items-center justify-between gap-3 text-sm"><span className="flex items-center gap-2"><Power size={16} className="text-zinc-400"/> Start with Windows</span><span className={autostart ? 'text-emerald-400':'text-amber-400'}>{autostart === null ? 'Checking...' : autostart ? 'Enabled' : 'Not enabled'}</span></div><div className="flex items-center justify-between gap-3 text-sm"><span className="flex items-center gap-2"><LockKeyhole size={16} className="text-zinc-400"/> Remote internet access</span><span className="text-zinc-400">Disabled</span></div><div className="flex items-center justify-between gap-3 text-sm"><span className="flex items-center gap-2"><Usb size={16} className="text-zinc-400"/> USB fallback</span><span className="text-zinc-400">Available via ADB</span></div></div><p className="small muted mt-4">Autostart launches CoreLink only; it never begins screen capture automatically.</p></section>
      </div>
      <footer className="py-7 text-xs text-zinc-500">CoreLink 0.1.0 · Windows ↔ Samsung · Phase 1: Android mirroring only</footer>
    </div>
  );
}
