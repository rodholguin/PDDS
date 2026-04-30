'use client';

import { useCallback, useEffect, useMemo, useState, type CSSProperties } from 'react';
import { reportsApi } from '@/lib/api/reportsApi';
import { useSimulation } from '@/lib/SimulationContext';
import type { SlaBreakdownRow } from '@/lib/types';

export default function ReportsPage() {
  const { sim } = useSimulation();
  const [from, setFrom] = useState(() => new Date(Date.now() - 29 * 24 * 60 * 60 * 1000).toISOString().slice(0, 10));
  const [to, setTo] = useState(() => new Date().toISOString().slice(0, 10));
  const [rows, setRows] = useState<SlaBreakdownRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const report = await reportsApi.slaCompliance({ from, to });
      setRows(report.rows ?? []);
      setError(null);
    } catch {
      setRows([]);
      setError('No se pudo cargar reportes operativos.');
    } finally {
      setLoading(false);
    }
  }, [from, to]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    const simulatedDate = toDateInput(sim?.simulatedNow ?? sim?.effectiveScenarioStartAt);
    if (!simulatedDate) return;

    setFrom((current) => (isHostDefaultRange(current, 29) ? shiftDate(simulatedDate, -29) : current));
    setTo((current) => (isHostDefaultRange(current, 0) ? simulatedDate : current));
  }, [sim?.simulatedNow, sim?.effectiveScenarioStartAt]);

  const grouped = useMemo(() => {
    const routeType = rows.filter((r) => r.dimension === 'ROUTE_TYPE');
    const client = rows.filter((r) => r.dimension === 'CLIENT');
    const destination = rows.filter((r) => r.dimension === 'DESTINATION');
    return { routeType, client, destination };
  }, [rows]);

  const overall = useMemo(() => {
    const total = rows.reduce((acc, row) => acc + row.total, 0);
    const onTime = rows.reduce((acc, row) => acc + row.onTime, 0);
    const late = Math.max(0, total - onTime);
    const sla = total === 0 ? 0 : (onTime * 100) / total;
    return { total, onTime, late, sla };
  }, [rows]);

  const criticalDestinations = useMemo(
    () => [...grouped.destination].sort((a, b) => a.onTimePct - b.onTimePct).slice(0, 5),
    [grouped.destination]
  );

  return (
    <div className="app-page" style={{ paddingTop: 10 }}>
      <header className="page-head">
        <div>
          <h1 className="page-head-title">Reportes operativos</h1>
          <p className="page-head-subtitle">Cumplimiento SLA, riesgo y focos de atencion de la red</p>
        </div>
        <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
          <input type="date" value={from} onChange={(e) => setFrom(e.target.value)} className="dashboard-search" style={{ width: 148 }} />
          <input type="date" value={to} onChange={(e) => setTo(e.target.value)} className="dashboard-search" style={{ width: 148 }} />
          <button className="btn btn-primary" onClick={() => void load()} disabled={loading}>{loading ? 'Actualizando...' : 'Actualizar'}</button>
        </div>
      </header>

      {error ? (
        <div className="state-panel is-error">
          <p className="state-panel-title">Error</p>
          <p className="state-panel-copy">{error}</p>
        </div>
      ) : null}

      <section className="report-kpi-grid" style={{ padding: '0 14px' }}>
        <article className="surface-panel" style={{ padding: 14 }}>
          <p style={titleStyle}><Semaforo dot={overall.sla >= 90 ? 'ok' : overall.sla >= 75 ? 'warn' : 'bad'} />SLA global</p>
          <strong style={valueStyle}>{overall.sla.toFixed(1)}%</strong>
        </article>
        <article className="surface-panel" style={{ padding: 14 }}>
          <p style={titleStyle}><Semaforo dot={overall.total > 0 ? 'ok' : 'warn'} />Despachos evaluados</p>
          <strong style={valueStyle}>{overall.total.toLocaleString('es-PE')}</strong>
        </article>
        <article className="surface-panel" style={{ padding: 14 }}>
          <p style={titleStyle}><Semaforo dot={overall.onTime >= overall.late ? 'ok' : 'warn'} />A tiempo</p>
          <strong style={valueStyle}>{overall.onTime.toLocaleString('es-PE')}</strong>
        </article>
        <article className="surface-panel" style={{ padding: 14 }}>
          <p style={titleStyle}><Semaforo dot={overall.late === 0 ? 'ok' : overall.late < 100 ? 'warn' : 'bad'} />Fuera de plazo</p>
          <strong style={valueStyle}>{overall.late.toLocaleString('es-PE')}</strong>
        </article>
      </section>

      <section className="report-main-grid" style={{ marginTop: 12, padding: '0 14px' }}>
        <article className="surface-panel" style={{ padding: 16 }}>
          <p className="report-card-title">SLA por tipo de ruta</p>
          <div style={{ marginTop: 10, display: 'grid', gap: 10 }}>
            {grouped.routeType.map((row) => (
              <div key={`${row.dimension}-${row.group}`} style={{ display: 'grid', gridTemplateColumns: '110px 1fr 64px 80px', gap: 10, alignItems: 'center' }}>
                <span style={{ color: '#dbe3ff', fontSize: 13 }}>{row.group}</span>
                <div className="progress-track" style={{ width: '100%' }}>
                  <div className="progress-fill" style={{ width: `${row.onTimePct}%`, background: row.onTimePct >= 90 ? '#43d29d' : row.onTimePct >= 75 ? '#f0c13a' : '#ef4444' }} />
                </div>
                <span style={{ color: '#c2cced', fontSize: 12 }}>{row.onTimePct.toFixed(1)}%</span>
                <span className="status-badge status-normal">{row.onTime}/{row.total}</span>
              </div>
            ))}
            {!loading && grouped.routeType.length === 0 ? <p style={{ margin: 0, color: '#8f98b6', fontSize: 12 }}>Sin datos en el rango seleccionado.</p> : null}
          </div>
        </article>

        <article className="surface-panel" style={{ padding: 16 }}>
          <p className="report-card-title">Top clientes con mejor SLA</p>
          <div style={{ marginTop: 10, display: 'grid', gap: 8 }}>
            {[...grouped.client].sort((a, b) => b.onTimePct - a.onTimePct).slice(0, 6).map((row) => (
              <div key={`${row.dimension}-${row.group}`} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '8px 10px', border: '1px solid #2f3550', borderRadius: 10 }}>
                <span style={{ color: '#dbe3ff', fontSize: 13 }}>{row.group}</span>
                <strong style={{ color: '#cde7d6', fontSize: 13 }}>{row.onTimePct.toFixed(1)}%</strong>
              </div>
            ))}
            {!loading && grouped.client.length === 0 ? <p style={{ margin: 0, color: '#8f98b6', fontSize: 12 }}>Sin datos de clientes.</p> : null}
          </div>
        </article>
      </section>

      <section className="report-secondary-grid" style={{ marginTop: 12, padding: '0 14px 14px' }}>
        <article className="surface-panel" style={{ padding: 16 }}>
          <p className="report-card-title">Nodos destino con mayor riesgo</p>
          <div style={{ marginTop: 10, display: 'grid', gap: 8 }}>
            {criticalDestinations.map((row) => (
              <div key={`${row.dimension}-${row.group}`} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '8px 10px', border: '1px solid #49303a', borderRadius: 10, background: '#2b1d23' }}>
                <span style={{ color: '#f5d2dc', fontSize: 13 }}>{row.group}</span>
                <span style={{ color: '#ff9bb2', fontWeight: 700, fontSize: 13 }}>{row.onTimePct.toFixed(1)}%</span>
              </div>
            ))}
            {!loading && criticalDestinations.length === 0 ? <p style={{ margin: 0, color: '#8f98b6', fontSize: 12 }}>Sin nodos con riesgo en el rango.</p> : null}
          </div>
        </article>

        <article className="surface-panel" style={{ padding: 16 }}>
          <p className="report-card-title">Detalle SLA por nodo destino</p>
          <div style={{ marginTop: 10, maxHeight: 340, overflowY: 'auto', display: 'grid', gap: 8 }}>
            {grouped.destination.map((row) => (
              <div key={`${row.dimension}-${row.group}-detail`} style={{ display: 'flex', justifyContent: 'space-between', gap: 12, alignItems: 'center', padding: '8px 10px', border: '1px solid #2f3550', borderRadius: 10 }}>
                <span style={{ color: '#dbe3ff', fontSize: 13 }}>{row.group}</span>
                <span style={{ color: '#c2cced', fontSize: 12 }}>{row.onTime}/{row.total} ({row.onTimePct.toFixed(1)}%)</span>
              </div>
            ))}
            {!loading && grouped.destination.length === 0 ? <p style={{ margin: 0, color: '#8f98b6', fontSize: 12 }}>Sin detalle de nodos para el rango.</p> : null}
          </div>
        </article>
      </section>
    </div>
  );
}

const titleStyle: CSSProperties = {
  margin: 0,
  color: '#98a3c7',
  fontSize: 12,
  display: 'flex',
  alignItems: 'center',
  gap: 8,
};

const valueStyle: CSSProperties = {
  marginTop: 6,
  display: 'block',
  color: '#edf1ff',
  fontSize: 24,
};

function Semaforo({ dot }: { dot: 'ok' | 'warn' | 'bad' }) {
  const color = dot === 'ok' ? '#22c55e' : dot === 'warn' ? '#f59e0b' : '#ef4444';
  return <span style={{ width: 8, height: 8, borderRadius: 99, background: color, boxShadow: `0 0 0 3px ${color}33` }} />;
}

function toDateInput(value: string | null | undefined) {
  if (!value) return null;
  return new Date(value).toISOString().slice(0, 10);
}

function shiftDate(date: string, days: number) {
  const value = new Date(`${date}T00:00:00Z`);
  value.setUTCDate(value.getUTCDate() + days);
  return value.toISOString().slice(0, 10);
}

function isHostDefaultRange(value: string, offsetDays: number) {
  return value === shiftDate(new Date().toISOString().slice(0, 10), -offsetDays);
}
