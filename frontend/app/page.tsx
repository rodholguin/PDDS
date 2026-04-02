'use client';

import { useCallback, useEffect, useState } from 'react';
import type { Airport, CollapseRisk, DashboardKpis, SimulationConfig, SystemStatus } from '@/lib/types';
import { airportsApi }   from '@/lib/api/airportsApi';
import { dashboardApi }  from '@/lib/api/dashboardApi';
import { simulationApi } from '@/lib/api/simulationApi';

// ── Helpers ───────────────────────────────────────────────────────────────

function statusColor(s: string): string {
  if (s === 'NORMAL')  return '#22c55e';
  if (s === 'ALERTA')  return '#f59e0b';
  if (s === 'CRITICO') return '#ef4444';
  return '#8484a0';
}

function riskColor(r: number): string {
  if (r >= 0.75) return '#ef4444';
  if (r >= 0.45) return '#f59e0b';
  return '#22c55e';
}

function loadColor(pct: number): string {
  if (pct >= 90) return '#ef4444';
  if (pct >= 70) return '#f59e0b';
  return '#6685ff';
}

const fmt = (n: number, d = 1) => n.toFixed(d);

// ── Sub-components ────────────────────────────────────────────────────────

function KpiCard({ title, value, sub, accent }: {
  title: string; value: React.ReactNode; sub?: string; accent?: string;
}) {
  return (
    <div className="rounded-xl p-5 card-hover" style={{
      background: '#1c1c24', border: '1px solid #2d2d40', flex: '1 1 0',
    }}>
      <p className="text-xs font-medium uppercase tracking-wide mb-3" style={{ color: '#8484a0' }}>
        {title}
      </p>
      <p className="text-3xl font-bold leading-none mb-1" style={{ color: accent ?? '#f0f0f8' }}>
        {value}
      </p>
      {sub && <p className="text-xs mt-1.5" style={{ color: '#8484a0' }}>{sub}</p>}
    </div>
  );
}

function StatusBadge({ status }: { status: string }) {
  const c = statusColor(status);
  return (
    <span className="text-[10px] font-semibold px-2 py-0.5 rounded-full"
          style={{ background: c + '22', color: c }}>
      {status}
    </span>
  );
}

function AirportRow({ a }: { a: Airport }) {
  const c = statusColor(a.status);
  return (
    <div className="flex items-center gap-3 py-2" style={{ borderBottom: '1px solid #2d2d4020' }}>
      <span className="text-xs font-mono font-bold w-10 flex-shrink-0" style={{ color: '#8ba3ff' }}>
        {a.icaoCode}
      </span>
      <span className="text-xs flex-shrink-0 w-24 truncate" style={{ color: '#f0f0f8' }}>
        {a.city}
      </span>
      {/* Bar */}
      <div className="flex-1 h-1.5 rounded-full" style={{ background: '#2d2d40' }}>
        <div className="h-full rounded-full transition-all"
             style={{ width: `${Math.min(a.occupancyPct, 100)}%`, background: c }} />
      </div>
      <span className="text-xs w-10 text-right flex-shrink-0" style={{ color: '#8484a0' }}>
        {fmt(a.occupancyPct)}%
      </span>
      <StatusBadge status={a.status} />
    </div>
  );
}

function ContinentBlock({ label, airports }: { label: string; airports: Airport[] }) {
  if (!airports.length) return null;
  const flag: Record<string, string> = { AMERICA: '🌎', EUROPE: '🌍', ASIA: '🌏' };
  return (
    <div className="mb-5">
      <p className="text-[10px] font-semibold uppercase tracking-widest mb-2 flex items-center gap-1.5"
         style={{ color: '#8484a0' }}>
        {flag[label]} {label}
      </p>
      {airports.map(a => <AirportRow key={a.id} a={a} />)}
    </div>
  );
}

function Skeleton() {
  return (
    <div className="space-y-2">
      {Array.from({ length: 8 }).map((_, i) => (
        <div key={i} className="h-8 rounded-lg animate-pulse" style={{ background: '#232330' }} />
      ))}
    </div>
  );
}

// ── Page ──────────────────────────────────────────────────────────────────

export default function DashboardPage() {
  const [kpis,    setKpis]    = useState<DashboardKpis | null>(null);
  const [sysstat, setSysstat] = useState<SystemStatus | null>(null);
  const [airports, setAirports] = useState<Airport[]>([]);
  const [risk,    setRisk]    = useState<CollapseRisk | null>(null);
  const [sim,     setSim]     = useState<SimulationConfig | null>(null);
  const [loading, setLoading] = useState(true);
  const [lastUpd, setLastUpd] = useState<Date | null>(null);
  const [error,   setError]   = useState<string | null>(null);

  const fetchAll = useCallback(async () => {
    const [k, s, a, r, sm] = await Promise.allSettled([
      dashboardApi.getKpis(),
      dashboardApi.getSystemStatus(),
      airportsApi.getAll(),
      simulationApi.getCollapseRisk(),
      simulationApi.getState(),
    ]);
    if (k.status  === 'fulfilled') setKpis(k.value);
    if (s.status  === 'fulfilled') setSysstat(s.value);
    if (a.status  === 'fulfilled') setAirports(a.value);
    if (r.status  === 'fulfilled') setRisk(r.value);
    if (sm.status === 'fulfilled') setSim(sm.value);

    const anyError = [k, s, a, r, sm].every(x => x.status === 'rejected');
    setError(anyError ? 'Sin conexión con el backend — comprueba que corra en :8080' : null);
    setLastUpd(new Date());
    setLoading(false);
  }, []);

  useEffect(() => {
    fetchAll();
    const id = setInterval(fetchAll, 30_000);
    return () => clearInterval(id);
  }, [fetchAll]);

  const byC = (c: string) => airports.filter(a => a.continent === c);

  return (
    <div className="max-w-[1400px] mx-auto">

      {/* Header */}
      <div className="flex items-start justify-between mb-8">
        <div>
          <h1 className="text-2xl font-bold" style={{ color: '#f0f0f8' }}>
            Panel de Control
          </h1>
          <p className="text-sm mt-0.5" style={{ color: '#8484a0' }}>
            Sistema de gestión de traslado de maletas · PUCP
          </p>
        </div>
        <div className="flex items-center gap-4">
          {sim?.isRunning && (
            <span className="flex items-center gap-1.5 text-xs px-3 py-1.5 rounded-full"
                  style={{ background: '#22c55e18', color: '#22c55e', border: '1px solid #22c55e40' }}>
              <span className="w-1.5 h-1.5 rounded-full animate-live" style={{ background: '#22c55e' }} />
              Simulación activa
            </span>
          )}
          <span className="flex items-center gap-1.5 text-xs" style={{ color: '#8484a0' }}>
            <span className="w-1.5 h-1.5 rounded-full animate-live" style={{ background: '#6685ff' }} />
            {lastUpd ? `Actualizado ${lastUpd.toLocaleTimeString('es-PE')}` : 'Conectando…'}
          </span>
          <button onClick={fetchAll}
                  className="text-xs px-3 py-1.5 rounded-lg cursor-pointer"
                  style={{ background: '#2d2d40', color: '#8484a0' }}>
            ↻ Refrescar
          </button>
        </div>
      </div>

      {/* Error banner */}
      {error && (
        <div className="mb-6 px-4 py-3 rounded-xl text-sm"
             style={{ background: '#ef444418', color: '#ef4444', border: '1px solid #ef444440' }}>
          ⚠ {error}
        </div>
      )}

      {/* KPI row */}
      <div className="flex gap-4 mb-8">
        <KpiCard title="Envíos Activos"
                 value={loading ? '…' : (kpis?.activeShipments ?? 0)}
                 sub={`${kpis?.criticalShipments ?? 0} críticos · ${kpis?.deliveredShipments ?? 0} entregados`}
                 accent="#6685ff" />
        <KpiCard title="Carga del Sistema"
                 value={loading ? '…' : `${fmt(kpis?.systemLoadPct ?? 0)}%`}
                 sub={`${kpis?.alertaAirports ?? 0} en alerta · ${kpis?.criticoAirports ?? 0} críticos`}
                 accent={loadColor(kpis?.systemLoadPct ?? 0)} />
        <KpiCard title="Riesgo de Colapso"
                 value={loading ? '…' : fmt(kpis?.collapseRisk ?? 0, 2)}
                 sub={risk && risk.estimatedHoursToCollapse > 0
                   ? `~${fmt(risk.estimatedHoursToCollapse)}h estimadas`
                   : 'Sistema estable'}
                 accent={riskColor(kpis?.collapseRisk ?? 0)} />
        <KpiCard title="Aeropuertos Críticos"
                 value={loading ? '…' : `${kpis?.criticoAirports ?? 0} / ${kpis?.totalAirports ?? 0}`}
                 sub={`${kpis?.alertaAirports ?? 0} en alerta`}
                 accent={(kpis?.criticoAirports ?? 0) > 0 ? '#ef4444' : '#22c55e'} />
      </div>

      {/* Content grid */}
      <div className="flex gap-6">

        {/* Airport list */}
        <div className="flex-1 min-w-0 rounded-xl p-5"
             style={{ background: '#1c1c24', border: '1px solid #2d2d40' }}>
          <div className="flex items-center justify-between mb-5">
            <h2 className="text-sm font-semibold" style={{ color: '#f0f0f8' }}>
              Aeropuertos por Continente
            </h2>
            <span className="text-xs" style={{ color: '#8484a0' }}>
              {airports.length} nodos
            </span>
          </div>
          {loading ? <Skeleton /> : (
            <>
              <ContinentBlock label="AMERICA" airports={byC('AMERICA')} />
              <ContinentBlock label="EUROPE"  airports={byC('EUROPE')}  />
              <ContinentBlock label="ASIA"    airports={byC('ASIA')}    />
            </>
          )}
        </div>

        {/* Right panel */}
        <div className="w-[280px] flex-shrink-0 flex flex-col gap-4">

          {/* System status */}
          <div className="rounded-xl p-5" style={{ background: '#1c1c24', border: '1px solid #2d2d40' }}>
            <h2 className="text-sm font-semibold mb-4" style={{ color: '#f0f0f8' }}>
              Estado del Sistema
            </h2>
            {(['NORMAL', 'ALERTA', 'CRITICO'] as const).map(s => {
              const count = s === 'NORMAL' ? (sysstat?.normalAirports ?? 0)
                          : s === 'ALERTA' ? (sysstat?.alertaAirports ?? 0)
                          : (sysstat?.criticoAirports ?? 0);
              const pct = ((count / (sysstat?.totalAirports || 1)) * 100);
              const c = statusColor(s);
              return (
                <div key={s} className="mb-3">
                  <div className="flex justify-between text-xs mb-1">
                    <span style={{ color: c }}>{s}</span>
                    <span style={{ color: '#8484a0' }}>{count} / {sysstat?.totalAirports ?? '?'}</span>
                  </div>
                  <div className="h-1.5 rounded-full" style={{ background: '#2d2d40' }}>
                    <div className="h-full rounded-full transition-all"
                         style={{ width: `${pct}%`, background: c }} />
                  </div>
                </div>
              );
            })}
            <div className="grid grid-cols-2 gap-2 mt-4 pt-4" style={{ borderTop: '1px solid #2d2d40' }}>
              {[
                { label: 'Programados', val: sysstat?.scheduledFlights ?? 0, c: '#6685ff' },
                { label: 'En vuelo',    val: sysstat?.inFlightFlights   ?? 0, c: '#38bdf8' },
              ].map(({ label, val, c }) => (
                <div key={label} className="rounded-lg p-2 text-center" style={{ background: '#232330' }}>
                  <p className="text-lg font-bold" style={{ color: c }}>{val}</p>
                  <p className="text-[10px]" style={{ color: '#8484a0' }}>{label}</p>
                </div>
              ))}
            </div>
          </div>

          {/* Collapse risk */}
          <div className="rounded-xl p-5" style={{ background: '#1c1c24', border: '1px solid #2d2d40' }}>
            <h2 className="text-sm font-semibold mb-4" style={{ color: '#f0f0f8' }}>
              Riesgo de Colapso
            </h2>
            <div className="text-center mb-4">
              <p className="text-5xl font-bold" style={{ color: riskColor(risk?.risk ?? 0) }}>
                {fmt(risk?.risk ?? 0, 2)}
              </p>
              <p className="text-xs mt-1" style={{ color: '#8484a0' }}>
                {(risk?.risk ?? 0) >= 0.75 ? '🔴 CRÍTICO'
                : (risk?.risk ?? 0) >= 0.45 ? '🟡 MODERADO'
                : '🟢 ESTABLE'}
              </p>
            </div>
            {/* gradient bar */}
            <div className="h-2 rounded-full mb-4 overflow-hidden" style={{ background: '#232330' }}>
              <div className="h-full rounded-full transition-all"
                   style={{
                     width: `${(risk?.risk ?? 0) * 100}%`,
                     background: 'linear-gradient(90deg,#22c55e,#f59e0b,#ef4444)',
                   }} />
            </div>
            {(risk?.bottlenecks?.length ?? 0) > 0 && (
              <div className="mb-3">
                <p className="text-[10px] uppercase tracking-wide mb-1.5" style={{ color: '#8484a0' }}>
                  Cuellos de botella
                </p>
                <div className="flex flex-wrap gap-1">
                  {risk!.bottlenecks.map(icao => (
                    <span key={icao} className="text-[10px] font-mono px-2 py-0.5 rounded"
                          style={{ background: '#ef444420', color: '#ef4444' }}>
                      {icao}
                    </span>
                  ))}
                </div>
              </div>
            )}
            <div className="rounded-lg p-2.5 text-center" style={{ background: '#232330' }}>
              <p className="text-xs" style={{ color: '#8484a0' }}>Tiempo estimado al colapso</p>
              <p className="text-sm font-semibold mt-0.5"
                 style={{ color: (risk?.estimatedHoursToCollapse ?? -1) < 0 ? '#22c55e' : '#f59e0b' }}>
                {(risk?.estimatedHoursToCollapse ?? -1) < 0
                  ? 'Sistema estable'
                  : `~${fmt(risk!.estimatedHoursToCollapse)} horas`}
              </p>
            </div>
          </div>

          {/* Simulation chip */}
          {sim && (
            <div className="rounded-xl p-4" style={{
              background: sim.isRunning ? '#22c55e10' : '#232330',
              border: `1px solid ${sim.isRunning ? '#22c55e40' : '#2d2d40'}`,
            }}>
              <div className="flex items-center justify-between mb-1">
                <p className="text-xs font-medium" style={{ color: '#f0f0f8' }}>Simulación</p>
                <span className="text-[10px] px-2 py-0.5 rounded-full"
                      style={{
                        background: sim.isRunning ? '#22c55e20' : '#2d2d40',
                        color:      sim.isRunning ? '#22c55e'   : '#8484a0',
                      }}>
                  {sim.isRunning ? '● EN CURSO' : '◼ DETENIDA'}
                </span>
              </div>
              <p className="text-[10px]" style={{ color: '#8484a0' }}>
                {sim.scenario} · {sim.primaryAlgorithm}
              </p>
            </div>
          )}

        </div>
      </div>
    </div>
  );
}
