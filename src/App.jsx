import React, { useCallback, useEffect, useState } from 'react';
import { invoke } from '@tauri-apps/api/core';
import { enable, isEnabled } from '@tauri-apps/plugin-autostart';
import { MonitorSmartphone, Wifi, Usb, RefreshCcw, Play, Square, Settings2, Smartphone, Terminal, Power, LockKeyhole, MousePointer2 } from 'lucide-react';
import { validEndpoint, normalizeDeviceState } from './validation.js';
import corelinkLogo from './corelink-icon.png';

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
  const [companion, setCompanion] = useState({ running:false, pending:[], trusted_devices:[], port:49876, active_sessions:0, qr_svg:null });
  const [page, setPage] = useState('home');
  const [showPair, setShowPair] = useState(false);
  const [capture, setCapture] = useState({ running:false, starting:false, source:'', frames:0, error:'' });

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
  const refreshCompanion = useCallback(async () => { try { setCompanion(await invoke('companion_snapshot')); } catch {} }, []);
  useEffect(() => { refreshCompanion(); const timer = setInterval(refreshCompanion, 2000); return () => clearInterval(timer); }, [refreshCompanion]);
  useEffect(() => { const update = () => invoke('capture_snapshot').then(setCapture).catch(() => {}); update(); const timer = setInterval(update, 1000); return () => clearInterval(timer); }, []);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const current = await isEnabled();
        const initialized = localStorage.getItem('corelink-autostart-initialized');
        if (!import.meta.env.DEV && !initialized && !current) await enable();
        if (!import.meta.env.DEV && !initialized) localStorage.setItem('corelink-autostart-initialized', '1');
        if (!cancelled) setAutostart(!import.meta.env.DEV && !initialized ? true : current);
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
    <div className="min-h-screen bg-[#09090b] text-zinc-100 lg:flex">
      <aside className="border-b border-zinc-800 bg-[#101014] px-6 py-6 lg:min-h-screen lg:w-64 lg:shrink-0 lg:border-b-0 lg:border-r lg:px-5">
        <div className="flex items-center gap-3 px-2"><img src={corelinkLogo} alt="" className="h-12 w-12 rounded-xl border border-zinc-800"/><div><h1 className="text-lg font-semibold">CoreLink</h1><p className="small muted">Your devices, together</p></div></div>
        <nav aria-label="Main navigation" className="mt-8 flex gap-2 lg:flex-col">
          {[['home','Overview',MonitorSmartphone],['mirror','Mirror phone',Smartphone],['settings','Settings',Settings2]].map(([id,label,Icon]) => <button key={id} onClick={()=>setPage(id)} aria-current={page===id?'page':undefined} className={`flex items-center gap-3 rounded-xl px-4 py-3 text-left text-sm font-medium transition ${page===id?'bg-orange-500/15 text-orange-400':'text-zinc-400 hover:bg-zinc-800 hover:text-white'}`}><Icon size={18}/>{label}</button>)}
        </nav>
        <div className="mt-8 hidden rounded-xl border border-zinc-800 bg-zinc-900/50 p-4 lg:block"><p className="text-xs font-medium text-zinc-300">Private by design</p><p className="small muted mt-1">Your devices connect on your local network.</p></div>
      </aside>
      <main className="min-w-0 flex-1 px-6 py-8 md:px-10 lg:px-12 xl:px-16">
        <header className="flex flex-wrap items-center justify-between gap-4"><div><p className="text-xs font-medium uppercase tracking-[.18em] text-orange-400">YOUR WORKSPACE</p><h2 className="mt-2 text-3xl font-semibold tracking-tight">{page==='home'?'Welcome to CoreLink':page==='mirror'?'Mirror your phone':'Settings'}</h2><p className="muted mt-2 text-sm">{page==='home'?'Everything you need to stay connected.':page==='mirror'?'See and control your Android phone on this PC.':'Manage your connection and display preferences.'}</p></div><button className="btn btn-subtle" onClick={()=>{refresh();refreshCompanion();}} disabled={loading||!!action}><RefreshCcw size={15} className={loading?'animate-spin':''}/> Refresh</button></header>
        {(error||info)&&<div role={error?'alert':'status'} className={`mt-6 rounded-xl border px-4 py-3 text-sm ${error?'border-red-900 bg-red-950/40 text-red-200':'border-zinc-700 bg-zinc-900 text-zinc-200'}`}>{error||info}</div>}
        {page==='home' && <div className="mt-8 grid gap-5 xl:grid-cols-3">
          <section className="surface p-7 xl:col-span-2"><div className="flex flex-wrap items-start justify-between gap-4"><div><div className="flex h-12 w-12 items-center justify-center rounded-2xl bg-orange-500/15 text-orange-400"><Smartphone size={24}/></div><h3 className="mt-5 text-xl font-semibold">Your phone remote</h3><p className="muted mt-2 text-sm">Control this PC from CoreLink on your phone.</p></div><span className={`rounded-full px-3 py-1.5 text-xs font-medium ${companion.active_sessions>0?'bg-emerald-500/10 text-emerald-400':'bg-zinc-800 text-zinc-300'}`}>{companion.active_sessions>0?'Connected':companion.running?'Ready to connect':'Offline'}</span></div>
            <div className="mt-7 border-t border-zinc-800 pt-5"><p className="text-xs font-medium uppercase tracking-wider text-zinc-500">Your devices</p>{companion.trusted_devices?.length ? <div className="mt-3 space-y-2">{companion.trusted_devices.map(device=><div className="flex items-center gap-3 rounded-xl bg-zinc-900 p-4" key={device.device_id}><Smartphone size={20} className="text-orange-400"/><span className="min-w-0 flex-1 truncate text-sm font-medium">{device.name}</span><button className="text-xs text-zinc-400 hover:text-red-300" onClick={()=>perform('remove phone','revoke_companion_device',{deviceId:device.device_id}).then(refreshCompanion)}>Remove</button></div>)}</div>:<p className="muted mt-3 text-sm">No phones added yet.</p>}</div>
            <div className="mt-5 flex flex-wrap gap-3"><button className="btn btn-primary" disabled={!!action||!companion.running} onClick={()=>{setShowPair(true);perform('pair phone','regenerate_companion_qr').then(refreshCompanion);}}>+ Add phone</button>{!companion.running && <button className="btn btn-subtle" disabled={!!action} onClick={()=>perform('start remote','start_companion_service').then(refreshCompanion)}>Start remote</button>}{companion.running && <button className="btn btn-subtle" disabled={!!action} onClick={()=>perform('stop remote','stop_companion_service').then(refreshCompanion)}>Disconnect all</button>}</div>
            {showPair && <div className="mt-5 flex flex-wrap items-center gap-5 rounded-xl border border-zinc-800 bg-zinc-950 p-4">{companion.qr_svg?<img className="h-40 w-40 rounded bg-white p-2" alt="Pairing QR code" src={`data:image/svg+xml;base64,${btoa(companion.qr_svg)}`}/>:<p className="text-sm muted">Preparing code…</p>}<div><p className="font-medium">Connect a phone</p><p className="small muted mt-1">Scan this code in the CoreLink phone app.</p><button className="mt-3 text-sm text-orange-400" onClick={()=>setShowPair(false)}>Done</button></div></div>}
            {companion.pending.map(request=><div className="mt-4 rounded-xl border border-amber-800 p-4" key={request.request_id}><p className="text-sm">Allow {request.name} to connect?</p><div className="mt-3 flex gap-2"><button className="btn btn-primary" onClick={()=>perform('approve','approve_companion_pairing',{requestId:request.request_id}).then(refreshCompanion)}>Allow</button><button className="btn btn-subtle" onClick={()=>perform('reject','reject_companion_pairing',{requestId:request.request_id}).then(refreshCompanion)}>Decline</button></div></div>)}
          </section>
          <div className="grid gap-5"><section className="surface p-7"><MonitorSmartphone size={25} className="text-orange-400"/><h3 className="mt-5 text-lg font-semibold">PC display</h3><p className="muted mt-2 text-sm">{capture.running?'Your screen is being shared.':capture.starting?'Starting screen sharing…':capture.error||'Open View PC on your phone to start sharing.'}</p><p className={`mt-5 text-sm ${capture.running?'text-emerald-400':'text-zinc-400'}`} role="status">{capture.running?'● Sharing':'○ Not sharing'}</p>{capture.running&&<button className="btn btn-subtle mt-4" disabled={!!action} onClick={()=>perform('stop sharing','stop_capture')}>Stop sharing</button>}</section>
          <section className="surface p-7"><Smartphone size={25} className="text-orange-400"/><h3 className="mt-5 text-lg font-semibold">Phone on PC</h3><p className="muted mt-2 text-sm">View and control your phone on your computer.</p><button className="btn btn-subtle mt-5" onClick={()=>setPage('mirror')}>Open phone mirroring →</button></section></div>
        </div>}
        {page==='mirror' && <div className="mt-8 grid gap-5 xl:grid-cols-3"><section className="surface p-7 xl:col-span-2"><h3 className="text-xl font-semibold">Available phones</h3><p className="muted mt-2 text-sm">Connect your phone by USB or set up a wireless connection.</p>{snapshot.devices.length ? <div className="mt-5 space-y-2">{snapshot.devices.map(d=><label key={d.serial} className={`flex cursor-pointer items-center gap-3 rounded-xl border p-4 ${selected===d.serial?'border-orange-500/70 bg-orange-500/5':'border-zinc-800'}`}><input type="radio" name="device" checked={selected===d.serial} onChange={()=>setSelected(d.serial)} className="accent-orange-500"/><Smartphone size={19}/><span className="flex-1 text-sm font-medium">{d.model||d.serial}</span><span className="text-xs text-zinc-400">{normalizeDeviceState(d.state)}</span></label>)}</div>:<p className="mt-6 rounded-xl border border-dashed border-zinc-700 p-6 text-sm text-zinc-400">No phone detected. Connect one to get started.</p>}<div className="mt-5 flex gap-2"><button className="btn btn-primary" disabled={!available||!selectedReady||!!action||snapshot.mirroring} onClick={()=>perform('start','start_mirroring',{serial:selected,fps,bitrate})}><Play size={15}/> {snapshot.mirroring?'Mirroring active':'Mirror phone'}</button>{snapshot.mirroring&&<button className="btn btn-subtle" disabled={!!action} onClick={()=>perform('stop','stop_mirroring')}><Square size={15}/> Stop</button>}</div>{!available&&<p className="mt-3 text-sm text-amber-400">Phone mirroring tools are unavailable on this PC.</p>}</section><section className="surface p-7"><Wifi size={24} className="text-orange-400"/><h3 className="mt-4 text-lg font-semibold">Connect over Wi-Fi</h3><p className="muted mt-2 text-sm">Use the pairing details from Wireless debugging on your phone.</p><form className="mt-5 space-y-3" onSubmit={e=>{e.preventDefault();perform('pair','pair_device',{address:pairAddress,code:pairCode});setPairCode('');}}><label className="block text-xs text-zinc-300" htmlFor="pair-address">Pairing address</label><input id="pair-address" className="field" placeholder="IP address and port" value={pairAddress} onChange={e=>setPairAddress(e.target.value.trim())}/><label className="block text-xs text-zinc-300" htmlFor="pair-code">Pairing code</label><input id="pair-code" className="field" placeholder="Six-digit code" inputMode="numeric" maxLength={6} value={pairCode} onChange={e=>setPairCode(e.target.value.replace(/\D/g,'').slice(0,6))}/><button className="btn btn-subtle w-full" disabled={!validEndpoint(pairAddress)||!/^[0-9]{6}$/.test(pairCode)||!!action}>Pair phone</button></form><label className="mt-5 block text-xs text-zinc-300" htmlFor="endpoint">Connection address</label><input id="endpoint" className="field mt-2" placeholder="IP address and port" value={endpoint} onChange={e=>setEndpoint(e.target.value.trim())}/><button className="btn btn-subtle mt-3 w-full" disabled={!validEndpoint(endpoint)||!!action} onClick={()=>perform('connect','connect_device',{address:endpoint})}>Connect</button></section></div>}
        {page==='settings' && <div className="mt-8 grid gap-5 lg:grid-cols-2"><section className="surface p-7"><h3 className="text-lg font-semibold">Phone mirroring</h3><p className="muted mt-2 text-sm">Changes apply the next time you mirror your phone.</p><label className="mt-5 block text-xs text-zinc-300" htmlFor="fps">Frame rate</label><select id="fps" className="field mt-2" value={fps} onChange={e=>setFps(Number(e.target.value))}>{[30,60,90,120].map(v=><option key={v} value={v}>{v} FPS</option>)}</select><label className="mt-4 block text-xs text-zinc-300" htmlFor="bitrate">Video quality</label><select id="bitrate" className="field mt-2" value={bitrate} onChange={e=>setBitrate(Number(e.target.value))}>{[2,4,8,12,16].map(v=><option key={v} value={v}>{v} Mbps</option>)}</select></section><section className="surface p-7"><h3 className="text-lg font-semibold">Connection</h3><div className="mt-5 flex items-center justify-between text-sm"><span>Start with Windows</span><span className="text-zinc-400">{autostart===null?'Checking…':autostart?'On':'Off'}</span></div><div className="mt-5 flex items-center justify-between text-sm"><span>Remote access</span><span className="text-zinc-400">Local network</span></div></section></div>}
      </main>
    </div>
  );
}
