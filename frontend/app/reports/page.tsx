"use client";

import { useCallback, useEffect, useMemo, useState } from 'react';
import { reportsApi } from '@/lib/api/reportsApi';
import type { SlaBreakdownRow } from '@/lib/types';

export default function ReportsPage() {
  const [from, setFrom] = useState(() => new Date(Date.now() - 29 * 24 * 60 * 60 * 1000).toISOString().slice(0, 10));
  const [to, setTo] = useState(() => new Date().toISOString().slice(0, 10));
  const [rows, setRows] = useState<SlaBreakdownRow[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async (): Promise<void> => {
    setLoading(true);
    try {
      const report = await reportsApi.slaCompliance({ from, to });
      setRows(report.rows ?? []);
      setError(null);
    } catch {
      setRows([]);
      setError('No se pudo cargar el reporte desde backend.');
    } finally {
      setLoading(false);
    }
  }, [from, to]);

  useEffect(() => {
    void load();
  }, [load]);

  const byDimension = useMemo(() => {
    return {
      routeType: rows.filter((r) => r.dimension === 'ROUTE_TYPE'),
      client: rows.filter((r) => r.dimension === 'CLIENT'),
      destination: rows.filter((r) => r.dimension === 'DESTINATION'),
    };
  }, [rows]);

  const hasData = rows.length > 0;

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

        {loading ? (
          <div className="state-panel">
            <p className="state-panel-title">Cargando reportes SLA</p>
            <p className="state-panel-copy">Calculando cumplimiento por ruta, cliente y nodo destino...</p>
          </div>
        ) : null}

        {!loading && !error && !hasData ? (
          <div className="state-panel">
            <p className="state-panel-title">Sin datos para el rango seleccionado</p>
            <p className="state-panel-copy">Ejecuta la simulacion o amplia el rango de fechas para ver resultados SLA.</p>
          </div>
        ) : null}

        <section className="report-grid">
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
              {!loading && byDimension.routeType.length === 0 ? (
                <p style={{ fontSize: 12, color: '#8f98b6', margin: 0 }}>Aun no hay datos por tipo de ruta.</p>
              ) : null}
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
              {!loading && byDimension.client.length === 0 ? (
                <p style={{ fontSize: 12, color: '#8f98b6', margin: 0 }}>Aun no hay datos por cliente.</p>
              ) : null}
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
              {!loading && byDimension.destination.length === 0 ? (
                <p style={{ fontSize: 12, color: '#8f98b6', margin: 0 }}>Aun no hay datos por nodo destino.</p>
              ) : null}
            </div>
          </article>
        </section>
      </div>
    </div>
  );
}
