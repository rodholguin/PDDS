'use client';

import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react';
import type { Airport, ShipmentDetail, ShipmentFeasibility } from '@/lib/types';
import { airportsApi } from '@/lib/api/airportsApi';
import { shipmentsApi, type ShipmentCreate } from '@/lib/api/shipmentsApi';

const OK = '#43d29d';
const WARN = '#f0c13a';
const FAIL = '#ff5a64';
const ACCENT = '#5f82ff';
const INK = '#eef1ff';
const DIM = '#9ca3bf';
const FAINT = '#6f7693';
const RIM_SUBTLE = '#2a2e44';

const INPUT: CSSProperties = {
  width: '100%',
  height: 42,
  padding: '0 12px',
  borderRadius: 10,
  background: '#141726',
  border: '1px solid #32364f',
  color: INK,
  fontSize: 14,
  outline: 'none',
};
const LABEL: CSSProperties = { display: 'block', fontSize: 12, color: DIM, marginBottom: 6, fontWeight: 600 };
const CARD_TITLE: CSSProperties = { margin: 0, fontSize: 15, fontWeight: 700, color: INK };
const CARD_SUB: CSSProperties = { margin: '3px 0 0', fontSize: 12, color: FAINT, lineHeight: 1.5 };
const CODE: CSSProperties = { color: '#aeb9e0', background: 'rgba(95,130,255,0.12)', padding: '1px 5px', borderRadius: 5, fontSize: 11.5 };

type TxtResult = { filename: string; origin?: string; created: number; failed: number; errors: string[]; shipments?: ShipmentDetail[] };

const PERU_TZ = 'America/Lima'; // GMT-5 fijo (Perú no usa horario de verano)

export default function RegistroPage() {
  const [airports, setAirports] = useState<Airport[]>([]);
  const [form, setForm] = useState({ originIcao: '', destinationIcao: '', luggageCount: 1, airlineName: '' });
  const [feasibility, setFeasibility] = useState<ShipmentFeasibility | null>(null);
  const [lastCreated, setLastCreated] = useState<ShipmentDetail | null>(null);
  const [registered, setRegistered] = useState<ShipmentDetail[]>([]);
  const [busy, setBusy] = useState<'check' | 'create' | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [nowMs, setNowMs] = useState<number | null>(null);
  const [txtBusy, setTxtBusy] = useState(false);
  const [txtResults, setTxtResults] = useState<TxtResult[]>([]);
  const [drag, setDrag] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    airportsApi.getAll().then(setAirports).catch(() => setAirports([]));
  }, []);

  useEffect(() => {
    setNowMs(Date.now());
    const t = setInterval(() => setNowMs(Date.now()), 1000);
    return () => clearInterval(t);
  }, []);

  const clockDate = nowMs == null ? null : new Date(nowMs);
  const peruTime = clockDate == null ? '--:--:--' : clockDate.toLocaleTimeString('es-PE', { timeZone: PERU_TZ, hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' });
  const peruDate = clockDate == null ? '—' : clockDate.toLocaleDateString('es-PE', { timeZone: PERU_TZ, weekday: 'long', day: '2-digit', month: 'long', year: 'numeric' });

  // Las fechas vienen del backend en UTC (sin sufijo) → se muestran en hora de Perú.
  const fmt = (iso?: string | null) => {
    if (!iso) return '—';
    const utc = /[zZ]|[+-]\d\d:\d\d$/.test(iso) ? iso : `${iso.slice(0, 19)}Z`;
    return new Date(utc).toLocaleString('es-PE', { timeZone: PERU_TZ, hour12: false, day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' });
  };

  const set = (k: keyof typeof form, v: string | number) => {
    setForm((f) => ({ ...f, [k]: v }) as typeof f);
    setFeasibility(null);
  };

  const valid =
    !!form.originIcao &&
    !!form.destinationIcao &&
    form.originIcao !== form.destinationIcao &&
    form.luggageCount > 0 &&
    form.airlineName.trim().length > 0;

  const body = (): ShipmentCreate => ({
    airlineName: form.airlineName.trim(),
    originIcao: form.originIcao,
    destinationIcao: form.destinationIcao,
    luggageCount: form.luggageCount,
  });

  const onCheck = useCallback(async () => {
    setBusy('check');
    setError(null);
    try {
      setFeasibility(await shipmentsApi.checkFeasibility(body()));
    } catch (e) {
      setError(e instanceof Error ? e.message : 'No se pudo validar la factibilidad');
    } finally {
      setBusy(null);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [form]);

  const onRegister = useCallback(async () => {
    setBusy('create');
    setError(null);
    try {
      const created = await shipmentsApi.create(body());
      setLastCreated(created);
      setRegistered((prev) => [created, ...prev].slice(0, 25));
      setFeasibility(null);
      // Mantener el aeropuerto origen (el registrador sigue en su sede); limpiar el resto para el próximo.
      setForm((f) => ({ ...f, destinationIcao: '', luggageCount: 1, airlineName: '' }));
    } catch (e) {
      setError(e instanceof Error ? e.message : 'No se pudo registrar el envío');
    } finally {
      setBusy(null);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [form]);

  const handleFiles = useCallback(async (files: FileList | File[]) => {
    const list = Array.from(files).filter((f) => f.name.toLowerCase().endsWith('.txt') || f.type === 'text/plain');
    if (list.length === 0) {
      setError('Solo se aceptan archivos .txt');
      return;
    }
    setTxtBusy(true);
    setError(null);
    for (const file of list) {
      try {
        const res = await shipmentsApi.uploadLive(file);
        setTxtResults((prev) => [{ filename: file.name, ...res }, ...prev].slice(0, 12));
        if (res.shipments?.length) {
          setRegistered((prev) => [...res.shipments!, ...prev].slice(0, 25));
          setLastCreated(res.shipments[0]);
        }
      } catch (e) {
        setTxtResults((prev) => [{ filename: file.name, created: 0, failed: 0, errors: [e instanceof Error ? e.message : 'Error al cargar'] }, ...prev].slice(0, 12));
      }
    }
    setTxtBusy(false);
  }, []);

  return (
    <div className="app-page" style={{ paddingBottom: 24 }}>
      <header className="page-head">
        <div>
          <h1 className="page-head-title">Registro de envíos</h1>
          <p className="page-head-subtitle">Operación en vivo · entrada de datos. La hora mostrada es de Perú (GMT-5).</p>
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: 3, padding: '9px 16px', borderRadius: 12, background: 'linear-gradient(180deg, rgba(95,130,255,0.16) 0%, rgba(95,130,255,0.05) 100%)', border: '1px solid rgba(95,130,255,0.34)' }}>
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 7, fontSize: 10.5, color: '#aeb9e0', fontWeight: 700, letterSpacing: '0.04em' }}>
            <span className="animate-live" style={{ width: 7, height: 7, borderRadius: 999, background: OK }} /> EN VIVO · PERÚ (GMT-5)
          </span>
          <strong style={{ fontSize: 24, fontWeight: 700, color: '#dce6ff', fontVariantNumeric: 'tabular-nums', letterSpacing: '1px', lineHeight: 1.1 }}>{peruTime}</strong>
          <span style={{ fontSize: 11, color: FAINT, textTransform: 'capitalize' }}>{peruDate}</span>
        </div>
      </header>

      <div style={{ padding: '16px', display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) minmax(0, 0.82fr)', gap: 14, alignItems: 'start' }}>
        {/* IZQUIERDA: registro manual + carga por archivo */}
        <div style={{ display: 'grid', gap: 14, minWidth: 0 }}>
          {/* Registro manual */}
          <article className="surface-panel" style={{ padding: 18 }}>
            <div style={{ marginBottom: 16 }}>
              <p style={CARD_TITLE}>Registro manual</p>
              <p style={CARD_SUB}>Un envío a la vez. Se registra a la hora actual de Perú.</p>
            </div>

            <div style={{ display: 'grid', gap: 13 }}>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 11 }}>
                <div>
                  <label style={LABEL}>Aeropuerto de origen</label>
                  <select style={INPUT} value={form.originIcao} onChange={(e) => set('originIcao', e.target.value)}>
                    <option value="">Selecciona…</option>
                    {airports.map((a) => (
                      <option key={a.icaoCode} value={a.icaoCode}>{a.icaoCode} — {a.city}, {a.country}</option>
                    ))}
                  </select>
                </div>
                <div>
                  <label style={LABEL}>Aeropuerto de destino</label>
                  <select style={INPUT} value={form.destinationIcao} onChange={(e) => set('destinationIcao', e.target.value)}>
                    <option value="">Selecciona…</option>
                    {airports.filter((a) => a.icaoCode !== form.originIcao).map((a) => (
                      <option key={a.icaoCode} value={a.icaoCode}>{a.icaoCode} — {a.city}, {a.country}</option>
                    ))}
                  </select>
                </div>
              </div>

              <div style={{ display: 'grid', gridTemplateColumns: '150px 1fr', gap: 11 }}>
                <div>
                  <label style={LABEL}>Cantidad de maletas</label>
                  <input style={INPUT} type="number" min={1} value={form.luggageCount}
                    onChange={(e) => set('luggageCount', Math.max(1, Number(e.target.value) || 1))} />
                </div>
                <div>
                  <label style={LABEL}>Aerolínea / cliente</label>
                  <input style={INPUT} type="text" placeholder="Ej: LATAM" value={form.airlineName}
                    onChange={(e) => set('airlineName', e.target.value)} />
                </div>
              </div>

              {form.originIcao && form.destinationIcao && form.originIcao === form.destinationIcao ? (
                <p style={{ margin: 0, fontSize: 12, color: WARN }}>El origen y el destino no pueden ser iguales.</p>
              ) : null}

              {feasibility ? (
                <div style={{ padding: '11px 13px', borderRadius: 10, background: `${feasibility.feasible ? OK : FAIL}12`, border: `1px solid ${feasibility.feasible ? OK : FAIL}40` }}>
                  <p style={{ margin: 0, fontSize: 13, fontWeight: 700, color: feasibility.feasible ? OK : FAIL }}>
                    {feasibility.feasible ? '✓ Ruta factible' : '✗ Sin ruta factible'}
                  </p>
                  <p style={{ margin: '3px 0 0', fontSize: 12, color: DIM }}>{feasibility.message} · {feasibility.candidateRoutes} ruta(s) candidata(s)</p>
                </div>
              ) : null}

              {error ? <p style={{ margin: 0, fontSize: 12, color: FAIL }}>{error}</p> : null}

              <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', marginTop: 2 }}>
                <button className="btn btn-neutral" disabled={!valid || busy !== null} onClick={() => void onCheck()}>
                  {busy === 'check' ? 'Validando…' : 'Validar factibilidad'}
                </button>
                <button className="btn btn-primary" style={{ flex: 1, minWidth: 160 }} disabled={!valid || busy !== null} onClick={() => void onRegister()}>
                  {busy === 'create' ? 'Registrando…' : 'Registrar envío'}
                </button>
              </div>
            </div>
          </article>

          {/* Carga por archivo */}
          <article className="surface-panel" style={{ padding: 18 }}>
            <div style={{ marginBottom: 14 }}>
              <p style={CARD_TITLE}>Carga por archivo (.txt)</p>
              <p style={CARD_SUB}>
                Cada aeropuerto sube su propio archivo. El <b style={{ color: '#c4cdec' }}>origen se detecta del nombre</b> (ej:{' '}
                <code style={CODE}>SPIM.txt</code> o <code style={CODE}>_envios_SPIM_.txt</code>); el <b style={{ color: '#c4cdec' }}>destino</b> va en cada línea.
              </p>
            </div>

            <div
              role="button"
              tabIndex={0}
              onClick={() => inputRef.current?.click()}
              onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') inputRef.current?.click(); }}
              onDragOver={(e) => { e.preventDefault(); setDrag(true); }}
              onDragLeave={() => setDrag(false)}
              onDrop={(e) => { e.preventDefault(); setDrag(false); handleFiles(e.dataTransfer.files); }}
              style={{
                display: 'grid', placeItems: 'center', gap: 9, padding: '28px 18px', borderRadius: 14, cursor: 'pointer', textAlign: 'center',
                border: `1.5px dashed ${drag ? ACCENT : 'rgba(122,153,255,0.4)'}`,
                background: drag ? 'rgba(95,130,255,0.10)' : 'rgba(95,130,255,0.035)',
                transition: '140ms ease',
              }}
            >
              <span style={{ width: 46, height: 46, borderRadius: 12, display: 'grid', placeItems: 'center', background: 'rgba(95,130,255,0.16)', border: '1px solid rgba(95,130,255,0.4)' }}>
                <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke={ACCENT} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M12 15V4" /><path d="M7.5 8.5 12 4l4.5 4.5" /><path d="M5 15v3.5a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V15" />
                </svg>
              </span>
              <p style={{ margin: 0, fontSize: 14, color: INK, fontWeight: 600 }}>Arrastra tus archivos .txt aquí</p>
              <span style={{ fontSize: 12, color: FAINT }}>— o —</span>
              <span className="btn btn-primary" style={{ display: 'inline-flex', alignItems: 'center', height: 38, pointerEvents: 'none' }}>Seleccionar archivos</span>
              <p style={{ margin: '4px 0 0', fontSize: 11, color: FAINT, maxWidth: 360 }}>
                Formato <code style={CODE}>id-aaaammdd-hh-mm-dest-###-IdClien</code> · una línea por envío · puedes soltar varios a la vez.
              </p>
            </div>
            <input ref={inputRef} type="file" accept=".txt,text/plain" multiple style={{ display: 'none' }}
              onChange={(e) => { const fs = e.currentTarget.files ? Array.from(e.currentTarget.files) : []; e.currentTarget.value = ''; if (fs.length > 0) void handleFiles(fs); }} />

            {txtBusy ? <p style={{ margin: '12px 0 0', fontSize: 12, color: ACCENT }}>Cargando y planificando envíos…</p> : null}

            {txtResults.length > 0 ? (
              <div style={{ marginTop: 12, display: 'grid', gap: 8 }}>
                {txtResults.map((r, i) => {
                  const color = r.created > 0 ? (r.failed > 0 ? WARN : OK) : FAIL;
                  return (
                    <div key={i} style={{ padding: '9px 12px', borderRadius: 10, background: `${color}12`, border: `1px solid ${color}38` }}>
                      <p style={{ margin: 0, fontSize: 12.5, fontWeight: 600, color }}>
                        {r.filename}{r.origin ? ` · origen ${r.origin}` : ''} → {r.created} creado(s){r.failed > 0 ? ` · ${r.failed} con error` : ''}
                      </p>
                      {r.errors && r.errors.length > 0 ? (
                        <ul style={{ margin: '5px 0 0', paddingLeft: 16, fontSize: 11, color: DIM }}>
                          {r.errors.slice(0, 4).map((er, j) => <li key={j}>{er}</li>)}
                        </ul>
                      ) : null}
                    </div>
                  );
                })}
              </div>
            ) : null}
          </article>
        </div>

        {/* DERECHA: comprobante + sesión */}
        <div style={{ display: 'grid', gap: 14, minWidth: 0 }}>
          {lastCreated ? (
            <article className="surface-panel" style={{ padding: 18, borderColor: 'rgba(67,210,157,0.42)' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 9, marginBottom: 12 }}>
                <span style={{ width: 22, height: 22, borderRadius: 999, display: 'grid', placeItems: 'center', background: OK, color: '#04231a', fontSize: 13, fontWeight: 800 }}>✓</span>
                <p style={{ margin: 0, fontSize: 13, fontWeight: 700, color: OK }}>Envío registrado</p>
              </div>
              <p style={{ margin: 0, fontSize: 21, fontWeight: 800, color: INK, letterSpacing: '0.5px' }}>{lastCreated.shipmentCode}</p>
              <div style={{ marginTop: 13, display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 11 }}>
                <Field label="Ruta" value={`${lastCreated.originIcaoCode} → ${lastCreated.destinationIcaoCode}`} />
                <Field label="Maletas" value={String(lastCreated.luggageCount)} />
                <Field label="Registro" value={fmt(lastCreated.registrationDate)} />
                <Field label="Plazo máximo" value={fmt(lastCreated.deadline)} color={WARN} />
              </div>
              <button className="btn btn-neutral" style={{ marginTop: 14, width: '100%' }} onClick={() => void shipmentsApi.downloadReceipt(lastCreated.id)}>
                Descargar comprobante
              </button>
            </article>
          ) : (
            <article className="surface-panel" style={{ padding: '28px 18px', textAlign: 'center' }}>
              <p style={{ margin: 0, fontSize: 13, color: FAINT }}>Aquí verás el comprobante del último envío que registres.</p>
            </article>
          )}

          <article className="surface-panel" style={{ padding: 0, overflow: 'hidden' }}>
            <div style={{ padding: '13px 16px', borderBottom: `1px solid ${RIM_SUBTLE}`, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <p style={{ margin: 0, fontSize: 13, fontWeight: 700, color: INK }}>Registrados en esta sesión</p>
              <span className="status-badge status-neutral">{registered.length}</span>
            </div>
            {registered.length === 0 ? (
              <p style={{ margin: 0, padding: '26px 16px', fontSize: 12, color: FAINT, textAlign: 'center' }}>Aún no registras envíos en esta sesión.</p>
            ) : (
              <div style={{ maxHeight: 360, overflowY: 'auto' }}>
                {registered.map((s) => (
                  <div key={s.id} style={{ display: 'flex', justifyContent: 'space-between', gap: 8, padding: '11px 16px', borderBottom: '1px solid rgba(50,54,79,0.4)', fontSize: 12 }}>
                    <div style={{ minWidth: 0 }}>
                      <span style={{ color: '#dbe3ff', fontWeight: 600 }}>{s.shipmentCode}</span>
                      <span style={{ color: DIM }}> · {s.originIcaoCode}→{s.destinationIcaoCode}</span>
                    </div>
                    <span style={{ color: DIM, whiteSpace: 'nowrap' }}>plazo {fmt(s.deadline)}</span>
                  </div>
                ))}
              </div>
            )}
          </article>
        </div>
      </div>
    </div>
  );
}

function Field({ label, value, color }: { label: string; value: string; color?: string }) {
  return (
    <div>
      <span style={{ display: 'block', color: FAINT, fontSize: 11, marginBottom: 3 }}>{label}</span>
      <span style={{ color: color ?? '#dbe3ff', fontSize: 13, fontWeight: 600 }}>{value}</span>
    </div>
  );
}
