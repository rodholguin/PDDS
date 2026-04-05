"use client";

import { useCallback, useEffect, useMemo, useState } from 'react';
import { reportsApi } from '@/lib/api/reportsApi';
import type { SlaBreakdownRow } from '@/lib/types';

export default function ReportsPage() {
  const [from, setFrom] = useState(() => new Date(Date.now() - 29 * 24 * 60 * 60 * 1000).toISOString().slice(0, 10));
  const [to, setTo] = useState(() => new Date().toISOString().slice(0, 10));
  const [rows, setRows] = useState<SlaBreakdownRow[]>([]);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async (): Promise<void> => {
    try {
      const report = await reportsApi.slaCompliance({ from, to });
      setRows(report.rows ?? []);
      setError(null);
    } catch {
      setRows([]);
      setError('No se pudo cargar el reporte desde backend.');
    }
  }, [from, to]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void load();
  }, [load]);

  const byDimension = useMemo(() => {
    return {
      routeType: rows.filter((r) => r.dimension === 'ROUTE_TYPE'),
      client: rows.filter((r) => r.dimension === 'CLIENT'),
      destination: rows.filter((r) => r.dimension === 'DESTINATION'),
    };
  }, [rows]);

  return (
    <div className="app-page">
      <header className="page-head">
        <div>
          <h1 className="page-head-title">Reportes</h1>
          <p className="page-head-subtitle">Desempeno operativo y metricas clave</p>
        </div>

        <div className="report-topbar">
          <input type="date" value={from} onChange={(e) => setFrom(e.target.value)} className="dashboard-search" style={{ width: 150 }} />
          <input type="date" value={to} onChange={(e) => setTo(e.target.value)} className="dashboard-search" style={{ width: 150 }} />
          <button className="chip is-active" onClick={() => void load()}>Actualizar</button>
        </div>
      </header>

      <div className="report-layout">
        {error ? (
          <div className="state-panel is-error">
            <p className="state-panel-title">Error</p>
            <p className="state-panel-copy">{error}</p>
          </div>
        ) : null}

        <section className="report-kpis">
          <article className="surface-panel kpi-card">
            <p>Envios Completados</p>
            <strong>1,247</strong>
            <small style={{ color: '#7ce9bc' }}>+12.4% vs anterior</small>
          </article>
          <article className="surface-panel kpi-card">
            <p>Tiempo Promedio</p>
            <strong>14.2h</strong>
            <small style={{ color: '#7ce9bc' }}>-8.1% vs anterior</small>
          </article>
          <article className="surface-panel kpi-card">
            <p>Tasa Replanificacion</p>
            <strong>18.3%</strong>
            <small style={{ color: '#ff9ea6' }}>+2.1% vs anterior</small>
          </article>
          <article className="surface-panel kpi-card">
            <p>Costo por Envio</p>
            <strong>$128.50</strong>
            <small style={{ color: '#7ce9bc' }}>-5.3% vs anterior</small>
          </article>
        </section>

        <section className="report-grid">
          <article className="surface-panel" style={{ padding: 16 }}>
            <p className="report-card-title">Envios por Dia</p>
            <div style={{ marginTop: 14, display: 'flex', gap: 8, alignItems: 'end', height: 180 }}>
              {[52, 68, 44, 77, 60, 72, 58, 79, 66, 70, 62, 74].map((height, index) => (
                <div key={index} style={{ flex: 1, borderRadius: 6, height: `${height}%`, background: index % 3 === 0 ? '#5f82ff' : index % 2 === 0 ? '#f0c13a' : '#ff5a64' }} />
              ))}
            </div>
          </article>

          <article className="surface-panel" style={{ padding: 16 }}>
            <p className="report-card-title">SLA por Tipo de Ruta</p>
            <div style={{ marginTop: 14, display: 'grid', gap: 10 }}>
              {byDimension.routeType.map((row) => (
                <div key={`${row.dimension}-${row.group}`} style={{ display: 'grid', gridTemplateColumns: '60px 1fr 56px 64px', alignItems: 'center', gap: 10 }}>
                  <span style={{ fontSize: 13 }}>{row.group}</span>
                  <div className="progress-track" style={{ width: '100%' }}>
                    <div className="progress-fill" style={{ width: `${row.onTimePct}%`, background: '#43d29d' }} />
                  </div>
                  <span style={{ fontSize: 12, color: '#9ca3bf' }}>{row.onTimePct.toFixed(1)}%</span>
                  <span className="status-badge status-normal">{row.onTime}/{row.total}</span>
                </div>
              ))}
            </div>
          </article>

          <article className="surface-panel" style={{ padding: 16 }}>
            <p className="report-card-title">SLA por Cliente</p>
            <div style={{ marginTop: 14, display: 'grid', gap: 12 }}>
              {byDimension.client.slice(0, 6).map((row) => (
                <div key={`${row.dimension}-${row.group}`}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 13 }}>
                    <span style={{ color: '#9ca3bf' }}>{row.group}</span>
                    <strong>{row.onTimePct.toFixed(1)}%</strong>
                  </div>
                  <div className="progress-track" style={{ width: '100%', marginTop: 6 }}>
                    <div className="progress-fill" style={{ width: `${row.onTimePct}%`, background: '#5f82ff' }} />
                  </div>
                </div>
              ))}
            </div>
          </article>

          <article className="surface-panel" style={{ padding: 16 }}>
            <p className="report-card-title">SLA por Nodo Destino</p>
            <div style={{ marginTop: 14, display: 'grid', gap: 10 }}>
              {byDimension.destination.slice(0, 8).map((row) => (
                <div key={`${row.dimension}-${row.group}`} style={{ display: 'grid', gridTemplateColumns: '10px 1fr', gap: 10 }}>
                  <span style={{ width: 8, height: 8, borderRadius: 999, background: '#43d29d', marginTop: 5 }} />
                  <div>
                    <p style={{ margin: 0, fontSize: 13 }}>{row.group}</p>
                    <p style={{ margin: '2px 0 0', color: '#8f98b6', fontSize: 12 }}>{row.onTime}/{row.total} a tiempo ({row.onTimePct.toFixed(1)}%)</p>
                  </div>
                </div>
              ))}
            </div>
          </article>
        </section>
      </div>
    </div>
  );
}
