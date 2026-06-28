'use client';

import { useCallback, useEffect, useMemo, useState, type CSSProperties, type ReactNode } from 'react';
import { dashboardApi } from '@/lib/api/dashboardApi';
import { simulationApi } from '@/lib/api/simulationApi';
import type { DashboardOverview, SimulationResults, SimulationState } from '@/lib/types';

type ReportMode = 'live' | 'sim';

const GREEN = '#43d29d';
const AMBER = '#f0c13a';
const RED = '#ef4444';
const BLUE = '#60a5fa';
const MUTED = '#8f98b6';

export default function ReportsPage() {
  const [mode, setMode] = useState<ReportMode>('live');
  const [live, setLive] = useState<DashboardOverview | null>(null);
  const [simState, setSimState] = useState<SimulationState | null>(null);
  const [simResults, setSimResults] = useState<SimulationResults | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async (target: ReportMode = mode) => {
    setLoading(true);
    setError(null);
    try {
      if (target === 'live') {
        const [overview, state] = await Promise.all([
          dashboardApi.getOverview('live'),
          simulationApi.getState('live'),
        ]);
        setLive(overview);
        setSimState(state);
      } else {
        const [state, results] = await Promise.all([
          simulationApi.getState('sim'),
          simulationApi.getResults(),
        ]);
        setSimState(state);
        setSimResults(results);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : 'No se pudo cargar el reporte.');
    } finally {
      setLoading(false);
    }
  }, [mode]);

  useEffect(() => {
    void load(mode);
  }, [load, mode]);

  const simFinished = Boolean(simState && !simState.running && !simState.bootstrapping && simState.startedAt);
  const simScenario = simState?.scenario === 'COLLAPSE_TEST' ? 'Prueba de colapso'
    : simState?.scenario === 'PERIOD_SIMULATION' ? 'Simulación de período'
    : 'Simulación';

  const liveCards = useMemo(() => {
    if (!live) return [];
    return [
      { label: 'Envíos hoy', value: live.totalShipmentsToday.toLocaleString('es-PE'), sub: 'operación día a día', color: BLUE },
      { label: 'En ruta', value: live.shipmentsInRoute.toLocaleString('es-PE'), sub: 'maletas en traslado', color: BLUE },
      { label: 'Entregados hoy', value: live.deliveredToday.toLocaleString('es-PE'), sub: `${live.slaCompliancePct.toFixed(1)}% SLA`, color: slaColor(live.slaCompliancePct) },
      { label: 'En riesgo', value: live.atRiskShipments.toLocaleString('es-PE'), sub: `${live.overdueShipments} vencidos`, color: live.atRiskShipments > 0 ? AMBER : GREEN },
    ];
  }, [live]);

  const simCards = useMemo(() => {
    if (!simResults) return [];
    const delivered = simResults.kpis?.delivered ?? 0;
    const delayed = simResults.kpis?.delayed ?? 0;
    const critical = simResults.kpis?.critical ?? 0;
    const late = Math.max(delayed, critical);
    const total = simResults.totalShipments ?? 0;
    const planned = simResults.plannedShipments ?? 0;
    const failed = simResults.failedPlanningShipments ?? 0;
    return [
      { label: 'Total evaluado', value: total.toLocaleString('es-PE'), sub: simScenario, color: BLUE },
      { label: 'Planificados', value: planned.toLocaleString('es-PE'), sub: `${failed} fallos`, color: failed > 0 ? AMBER : GREEN },
      { label: 'Entregados', value: delivered.toLocaleString('es-PE'), sub: `${(simResults.kpis?.deliveredOnTimePct ?? 0).toFixed(1)}% a tiempo`, color: slaColor(simResults.kpis?.deliveredOnTimePct ?? 0) },
      { label: 'Fuera de plazo', value: late.toLocaleString('es-PE'), sub: simResults.collapseShipmentCode ? `colapso ${simResults.collapseShipmentCode}` : 'sin colapso detectado', color: late > 0 ? RED : GREEN },
    ];
  }, [simResults, simScenario]);

  return (
    <div className="app-page" style={{ paddingTop: 10 }}>
      <header className="page-head">
        <div>
          <h1 className="page-head-title">Reportes</h1>
          <p className="page-head-subtitle">
            Fuente explícita: operación en vivo o último cierre de simulación. Sin datos mezclados ni pegados.
          </p>
        </div>
        <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
          <button className={`chip${mode === 'live' ? ' is-active' : ''}`} onClick={() => setMode('live')}>Operación día a día</button>
          <button className={`chip${mode === 'sim' ? ' is-active' : ''}`} onClick={() => setMode('sim')}>Última simulación</button>
          <button className="btn btn-primary" onClick={() => void load()} disabled={loading}>{loading ? 'Actualizando...' : 'Actualizar'}</button>
        </div>
      </header>

      {error ? (
        <div className="state-panel is-error">
          <p className="state-panel-title">Error</p>
          <p className="state-panel-copy">{error}</p>
        </div>
      ) : null}

      {mode === 'live' ? (
        <ReportSection
          title="Operación día a día"
          subtitle="Reloj cotidiano. Muestra solo la operación viva usada por logística y registro."
          timestamp={simState?.simulatedNow ?? null}
          cards={liveCards}
          empty={!loading && !live}
        >
          {live ? (
            <div style={detailGrid}>
              <Metric label="Próximos vuelos" value={live.nextScheduledFlights} />
              <Metric label="Vuelos activos" value={live.totalActiveFlights} />
              <Metric label="Replanificaciones hoy" value={live.replanningsToday} />
              <Metric label="Alertas pendientes" value={live.unresolvedAlerts} />
              <Metric label="Intra / Inter" value={`${live.activeIntraPct.toFixed(1)}% / ${live.activeInterPct.toFixed(1)}%`} />
              <Metric label="Ocupación flota" value={`${live.avgFlightOccupancyPct.toFixed(1)}%`} />
            </div>
          ) : null}
        </ReportSection>
      ) : (
        <ReportSection
          title={simScenario}
          subtitle={simFinished ? 'Último cierre persistido de simulación.' : 'La simulación aún no tiene un cierre completo persistido.'}
          timestamp={simResults?.scenarioEndAt ?? simState?.simulatedNow ?? null}
          cards={simCards}
          empty={!loading && !simResults}
        >
          {simResults ? (
            <div style={detailGrid}>
              <Metric label="Inicio" value={formatDate(simResults.scenarioStartAt)} />
              <Metric label="Fin" value={formatDate(simResults.scenarioEndAt)} />
              <Metric label="Backlog planificación" value={simResults.periodPlanningBacklog ?? 0} />
              <Metric label="Ta último plan" value={`${simResults.lastPlanningDurationMs ?? 0} ms`} />
              <Metric label="Ocupación aeropuertos" value={`${(simResults.avgNodeOccupancyPct ?? 0).toFixed(1)}%`} />
              <Metric label="Colapso" value={simResults.collapseDetectedAt ? `${formatDate(simResults.collapseDetectedAt)} · ${simResults.collapseShipmentCode ?? '-'}` : 'No detectado'} />
            </div>
          ) : null}
        </ReportSection>
      )}
    </div>
  );
}

function ReportSection({
  title,
  subtitle,
  timestamp,
  cards,
  empty,
  children,
}: {
  title: string;
  subtitle: string;
  timestamp: string | null;
  cards: Array<{ label: string; value: string; sub: string; color: string }>;
  empty: boolean;
  children: ReactNode;
}) {
  return (
    <section style={{ padding: '0 14px 18px', display: 'grid', gap: 12 }}>
      <article className="surface-panel" style={{ padding: 16 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, alignItems: 'start', flexWrap: 'wrap' }}>
          <div>
            <h2 style={{ margin: 0, color: '#edf1ff', fontSize: 18 }}>{title}</h2>
            <p style={{ margin: '4px 0 0', color: MUTED, fontSize: 13 }}>{subtitle}</p>
          </div>
          <span className="status-badge status-neutral">{timestamp ? formatDate(timestamp) : 'Sin hora de cierre'}</span>
        </div>
      </article>

      {empty ? (
        <div className="state-panel">
          <p className="state-panel-title">Sin datos disponibles</p>
          <p className="state-panel-copy">Ejecuta una operación o una simulación y actualiza este reporte.</p>
        </div>
      ) : null}

      <section style={{ display: 'grid', gridTemplateColumns: 'repeat(4, minmax(0, 1fr))', gap: 12 }}>
        {cards.map((card) => (
          <article key={card.label} className="surface-panel" style={{ padding: 14, borderLeft: `3px solid ${card.color}` }}>
            <p style={{ margin: 0, color: '#98a3c7', fontSize: 12 }}>{card.label}</p>
            <strong style={{ display: 'block', marginTop: 8, color: '#edf1ff', fontSize: 24 }}>{card.value}</strong>
            <p style={{ margin: '4px 0 0', color: card.color, fontSize: 12 }}>{card.sub}</p>
          </article>
        ))}
      </section>

      <article className="surface-panel" style={{ padding: 16 }}>
        {children}
      </article>
    </section>
  );
}

function Metric({ label, value }: { label: string; value: string | number }) {
  return (
    <div style={{ padding: '10px 12px', borderRadius: 10, background: '#171a29', border: '1px solid #2b3048' }}>
      <p style={{ margin: 0, color: '#8f98b6', fontSize: 11 }}>{label}</p>
      <strong style={{ display: 'block', marginTop: 4, color: '#edf1ff', fontSize: 15 }}>{value}</strong>
    </div>
  );
}

function slaColor(value: number) {
  if (value >= 90) return GREEN;
  if (value >= 75) return AMBER;
  return RED;
}

function formatDate(value: string | null | undefined) {
  if (!value) return '-';
  return new Date(value).toLocaleString('es-PE', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

const detailGrid: CSSProperties = {
  display: 'grid',
  gridTemplateColumns: 'repeat(auto-fit, minmax(190px, 1fr))',
  gap: 10,
};
