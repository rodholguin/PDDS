'use client';

import { useCallback, useEffect, useMemo, useRef, useState, type CSSProperties } from 'react';
import Link from 'next/link';
import Map, { Marker, NavigationControl, Popup, type MapRef } from 'react-map-gl/maplibre';
import type { StyleSpecification } from 'maplibre-gl';
import { dashboardApi } from '@/lib/api/dashboardApi';
import { alertsApi } from '@/lib/api/alertsApi';
import { importApi } from '@/lib/api/importApi';
import { simulationApi } from '@/lib/api/simulationApi';
import { useSimulation } from '@/lib/SimulationContext';
import type { Airport, MapLiveFlight, NodeDetail, OperationalAlert, SimScenario } from '@/lib/types';

const MAP_STYLE = 'https://demotiles.maplibre.org/style.json';
const SATELLITE_STYLE: StyleSpecification = {
  version: 8,
  sources: {
    esri: {
      type: 'raster',
      tiles: ['https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}'],
      tileSize: 256,
      attribution: 'Tiles © Esri',
    },
  },
  layers: [{ id: 'esri-world-imagery', type: 'raster', source: 'esri' }],
};

const INITIAL_VIEW = { latitude: 16, longitude: -38, zoom: 1.5 } as const;
const SPEED_OPTIONS = [1, 5, 10, 20] as const;
const FLIGHT_ANIMATION_MS = 900;
const SIM_TIME_FORMATTER = new Intl.DateTimeFormat('es-PE', {
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false,
});

type MapMode = 'MAPA' | 'SATELITAL';

type RuntimeConfig = {
  scenario: SimScenario;
  simulationDays: 3 | 5 | 7;
  scenarioStartDate: string;
  cancellationRatePct: number;
  intraNodeCapacity: number;
  interNodeCapacity: number;
  normalThresholdPct: number;
  warningThresholdPct: number;
};

function toDateInput(value: string | null): string {
  if (!value) return '';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '';
  return date.toISOString().slice(0, 10);
}

function escenarioLabel(value: SimScenario): string {
  if (value === 'PERIOD_SIMULATION') return 'Simulación período';
  if (value === 'COLLAPSE_TEST') return 'Prueba de colapso';
  return 'Operación día a día';
}

function colorSemaforo(estado: 'ok' | 'warn' | 'bad'): string {
  if (estado === 'ok') return '#22c55e';
  if (estado === 'warn') return '#f59e0b';
  return '#ef4444';
}

function semaforoSla(value: number): 'ok' | 'warn' | 'bad' {
  if (value >= 90) return 'ok';
  if (value >= 75) return 'warn';
  return 'bad';
}

function semaforoRiesgo(value: number): 'ok' | 'warn' | 'bad' {
  if (value <= 2) return 'ok';
  if (value <= 8) return 'warn';
  return 'bad';
}

function fmtPct(value: number): string {
  return `${value.toFixed(1)}%`;
}

function formatSimTime(value: Date): string {
  return SIM_TIME_FORMATTER.format(value);
}

function formatTimeScaleLabel(simulationSecondsPerTick: number | null | undefined): string {
  const simSeconds = Math.max(1, simulationSecondsPerTick ?? 1);
  if (simSeconds % 60 === 0) {
    const minutes = simSeconds / 60;
    return `${minutes} min simulados = 1 s real`;
  }
  return `${simSeconds} s simulados = 1 s real`;
}

function normalizeLongitude(longitude: number): number {
  let value = longitude;
  while (value > 180) value -= 360;
  while (value < -180) value += 360;
  return value;
}

function mercatorY(latitude: number): number {
  const rad = (latitude * Math.PI) / 180;
  return Math.log(Math.tan(Math.PI / 4 + rad / 2));
}

function inverseMercatorY(value: number): number {
  return (Math.atan(Math.sinh(value)) * 180) / Math.PI;
}

function nearestWrappedLongitude(target: number, reference: number): number {
  let best = target;
  let bestDistance = Math.abs(target - reference);
  for (const shift of [-720, -360, 0, 360, 720]) {
    const candidate = target + shift;
    const distance = Math.abs(candidate - reference);
    if (distance < bestDistance) {
      best = candidate;
      bestDistance = distance;
    }
  }
  return best;
}

function clamp01(value: number): number {
  return Math.max(0, Math.min(1, value));
}

function interpolateFlightPosition(from: { latitude: number; longitude: number }, to: { latitude: number; longitude: number }, ratio: number) {
  const safeRatio = clamp01(ratio);
  const fromLon = normalizeLongitude(from.longitude);
  const toLon = nearestWrappedLongitude(normalizeLongitude(to.longitude), fromLon);

  return {
    latitude: from.latitude + (to.latitude - from.latitude) * safeRatio,
    longitude: normalizeLongitude(fromLon + (toLon - fromLon) * safeRatio),
  };
}

function computeBearing(fromLat: number, fromLon: number, toLat: number, toLon: number): number {
  const toRad = (value: number) => (value * Math.PI) / 180;
  const toDeg = (value: number) => (value * 180) / Math.PI;
  const startLat = toRad(fromLat);
  const startLon = toRad(fromLon);
  const endLat = toRad(toLat);
  const endLon = toRad(toLon);
  const deltaLon = endLon - startLon;

  const y = Math.sin(deltaLon) * Math.cos(endLat);
  const x = Math.cos(startLat) * Math.sin(endLat)
    - Math.sin(startLat) * Math.cos(endLat) * Math.cos(deltaLon);

  return (toDeg(Math.atan2(y, x)) + 360) % 360;
}

function PlaneIcon({ rotation, selected }: { rotation: number; selected: boolean }) {
  const fill = selected ? '#facc15' : '#ffffff';
  const shadow = selected
    ? 'drop-shadow(0 0 8px rgba(250, 204, 21, 0.9)) drop-shadow(0 0 14px rgba(15, 23, 42, 0.9))'
    : 'drop-shadow(0 0 7px rgba(15, 23, 42, 0.98)) drop-shadow(0 0 12px rgba(15, 23, 42, 0.92))';

  return (
    <svg
      width="28"
      height="28"
      viewBox="0 0 24 24"
      aria-hidden="true"
      style={{
        display: 'block',
        overflow: 'visible',
        transform: `rotate(${rotation}deg)`,
        transformOrigin: '50% 50%',
        filter: shadow,
        pointerEvents: 'none',
      }}
    >
      <path
        d="M12 2 9.4 10.7 3 13.2 3 15.1 9.4 14.2 12 22 14.6 14.2 21 15.1 21 13.2 14.6 10.7Z"
        fill={fill}
        stroke="#08111f"
        strokeWidth="1.1"
        strokeLinejoin="round"
      />
    </svg>
  );
}

function KpiCard({ title, value, status }: { title: string; value: string; status: 'ok' | 'warn' | 'bad' }) {
  return (
    <article className="surface-panel" style={{ padding: 14 }}>
      <p style={{ margin: 0, fontSize: 12, color: '#a9b3cf', display: 'flex', alignItems: 'center', gap: 8 }}>
        <span style={{ width: 8, height: 8, borderRadius: 99, background: colorSemaforo(status), boxShadow: `0 0 0 3px ${colorSemaforo(status)}33` }} />
        {title}
      </p>
      <strong style={{ marginTop: 4, display: 'block', fontSize: 24, color: '#eff3ff' }}>{value}</strong>
    </article>
  );
}

export default function HomePage() {
  const mapRef = useRef<MapRef | null>(null);

  // Shared sim state + live operational data from context — persists across navigation
  const {
    sim,
    setSim,
    loaded: stateLoaded,
    overview,
    system,
    collapseRisk,
    airports,
    mapLive,
    mapLiveFlights,
    simulatedNowMs,
  } = useSimulation();

  const [mapMode, setMapMode] = useState<MapMode>('MAPA');
  const [selectedShipmentId, setSelectedShipmentId] = useState<number | null>(null);
  const [selectedFlightId, setSelectedFlightId] = useState<number | null>(null);
  const [selectedNode, setSelectedNode] = useState<{ point: { longitude: number; latitude: number }; detail: NodeDetail } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [savingConfig, setSavingConfig] = useState(false);
  const [generatingDemand, setGeneratingDemand] = useState(false);
  const [draftDirty, setDraftDirty] = useState(false);
  const [alerts, setAlerts] = useState<OperationalAlert[]>([]);
  const [panelCollapsed, setPanelCollapsed] = useState(() => {
    if (typeof window === 'undefined') return false;
    return localStorage.getItem('pdds-panel-collapsed') === 'true';
  });
  const [renderedFlights, setRenderedFlights] = useState<MapLiveFlight[]>([]);
  const renderedFlightsRef = useRef<MapLiveFlight[]>([]);
  const flightAnimationFrameRef = useRef<number | null>(null);

  // Derived clock display from the globally-extrapolated simulated time
  const displayedSimTime = useMemo(() => {
    if (!simulatedNowMs || !sim?.simulatedNow) return null;
    return formatSimTime(new Date(simulatedNowMs));
  }, [simulatedNowMs, sim?.simulatedNow]);

  const [config, setConfig] = useState<RuntimeConfig>({
    scenario: 'PERIOD_SIMULATION',
    simulationDays: 5,
    scenarioStartDate: '',
    cancellationRatePct: 5,
    intraNodeCapacity: 700,
    interNodeCapacity: 800,
    normalThresholdPct: 70,
    warningThresholdPct: 90,
  });

  const simulacionActiva = Boolean(sim?.running || sim?.paused);
  const canResume = Boolean(sim?.running && sim?.paused);
  const canEditConfig = !simulacionActiva;
  const speedOptions = config.scenario === 'DAY_TO_DAY' ? [1] : SPEED_OPTIONS;

  const hydrateFromState = useCallback((state: typeof sim) => {
    if (!state) return;
    const effectiveStart = state.scenarioStartAt
      || (state.projectedFrom ? `${state.projectedFrom}T00:00` : '');
    setConfig({
      scenario: state.scenario,
      simulationDays: state.simulationDays === 3 || state.simulationDays === 7 ? state.simulationDays : 5,
      scenarioStartDate: toDateInput(effectiveStart),
      cancellationRatePct: state.cancellationRatePct,
      intraNodeCapacity: state.intraNodeCapacity,
      interNodeCapacity: state.interNodeCapacity,
      normalThresholdPct: state.normalThresholdPct,
      warningThresholdPct: state.warningThresholdPct,
    });
  }, []);

  // Hydrate config from shared sim state (on mount and when sim updates externally)
  useEffect(() => {
    if (sim && !draftDirty) {
      hydrateFromState(sim);
    }
  }, [sim, draftDirty, hydrateFromState]);

  // All live data (overview/system/airports/mapLive/mapLiveFlights) and the
  // simulated-clock extrapolation are provided globally by SimulationContext so that
  // navigation between pages never triggers a flash of empty state.
  useEffect(() => {
    if (!simulacionActiva) {
      setRenderedFlights([]);
      renderedFlightsRef.current = [];
      setSelectedShipmentId(null);
      setSelectedFlightId(null);
    }
  }, [simulacionActiva]);

  useEffect(() => {
    renderedFlightsRef.current = renderedFlights;
  }, [renderedFlights]);

  useEffect(() => {
    if (flightAnimationFrameRef.current) {
      cancelAnimationFrame(flightAnimationFrameRef.current);
      flightAnimationFrameRef.current = null;
    }

    if (!simulacionActiva || mapLiveFlights.length === 0) {
      setRenderedFlights([]);
      renderedFlightsRef.current = [];
      return;
    }

    const previousById = new globalThis.Map(renderedFlightsRef.current.map((flight) => [flight.flightId, flight]));
    const startAt = performance.now();
    const fromFlights = mapLiveFlights.map((flight) => {
      const previous = previousById.get(flight.flightId);
      if (previous) {
        return previous;
      }

      return {
        ...flight,
        currentLatitude: flight.currentLatitude,
        currentLongitude: flight.currentLongitude,
      };
    });

    setRenderedFlights(fromFlights);
    renderedFlightsRef.current = fromFlights;

    const animate = (now: number) => {
      const ratio = clamp01((now - startAt) / FLIGHT_ANIMATION_MS);
      const nextFlights = mapLiveFlights.map((flight, index) => {
        const from = fromFlights[index] ?? flight;
        const position = interpolateFlightPosition(
          { latitude: from.currentLatitude, longitude: from.currentLongitude },
          { latitude: flight.currentLatitude, longitude: flight.currentLongitude },
          ratio,
        );

        return {
          ...flight,
          currentLatitude: position.latitude,
          currentLongitude: position.longitude,
        };
      });

      setRenderedFlights(nextFlights);
      renderedFlightsRef.current = nextFlights;

      if (ratio < 1) {
        flightAnimationFrameRef.current = requestAnimationFrame(animate);
      } else {
        flightAnimationFrameRef.current = null;
      }
    };

    flightAnimationFrameRef.current = requestAnimationFrame(animate);

    return () => {
      if (flightAnimationFrameRef.current) {
        cancelAnimationFrame(flightAnimationFrameRef.current);
        flightAnimationFrameRef.current = null;
      }
    };
  }, [mapLiveFlights, sim?.simulatedNow, sim?.simulationSecondsPerTick, simulacionActiva]);

  const selectedShipment = useMemo(() => mapLive.find((f) => f.shipmentId === selectedShipmentId) ?? null, [mapLive, selectedShipmentId]);
  const selectedFlight = useMemo(() => renderedFlights.find((f) => f.flightId === selectedFlightId) ?? null, [renderedFlights, selectedFlightId]);

  function patchConfig(patch: Partial<RuntimeConfig>) {
    setDraftDirty(true);
    setConfig((prev) => ({ ...prev, ...patch }));
  }

  async function saveScenarioConfig(): Promise<void> {
    setSavingConfig(true);
    try {
      const body: Parameters<typeof simulationApi.configure>[0] = {
        scenario: config.scenario,
        simulationDays: config.simulationDays,
        scenarioStartDate: config.scenarioStartDate || undefined,
        normalThresholdPct: config.normalThresholdPct,
        warningThresholdPct: config.warningThresholdPct,
      };
      if (config.scenario === 'COLLAPSE_TEST') {
        body.cancellationRatePct = config.cancellationRatePct;
        body.intraNodeCapacity = config.intraNodeCapacity;
        body.interNodeCapacity = config.interNodeCapacity;
      }
      const updated = await simulationApi.configure(body);
      setSim(updated);
      hydrateFromState(updated);
      setDraftDirty(false);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'No se pudo guardar la configuración.');
    } finally {
      setSavingConfig(false);
    }
  }

  async function onStart(): Promise<void> {
    try {
      if (draftDirty) await saveScenarioConfig();
      const res = await simulationApi.start();
      setSim(res.state);
      hydrateFromState(res.state);
      setDraftDirty(false);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'No se pudo iniciar la simulación.');
    }
  }

  async function onResume(): Promise<void> {
    try {
      const res = await simulationApi.resume();
      setSim(res.state);
      hydrateFromState(res.state);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'No se pudo reanudar la simulación.');
    }
  }

  async function onPause(): Promise<void> {
    try {
      const res = await simulationApi.pause();
      setSim(res.state);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'No se pudo pausar la simulación.');
    }
  }

  async function onStop(): Promise<void> {
    try {
      const res = await simulationApi.stop();
      setSim(res.state);
      setSelectedShipmentId(null);
      setSelectedNode(null);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'No se pudo detener la simulación.');
    }
  }

  async function onSetSpeed(next: number): Promise<void> {
    try {
      const res = await simulationApi.setSpeed(next);
      setSim(res.state);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'No se pudo cambiar la velocidad.');
    }
  }

  async function onGenerateFutureDemand(): Promise<void> {
    try {
      setGeneratingDemand(true);
      const today = new Date().toISOString().slice(0, 10);
      const inThirtyDays = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000).toISOString().slice(0, 10);
      await importApi.projectFutureDemand({ projectionStart: today, projectionEnd: inThirtyDays });
      const refreshed = await simulationApi.getState();
      setSim(refreshed);
      hydrateFromState(refreshed);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'No se pudo generar demanda futura.');
    } finally {
      setGeneratingDemand(false);
    }
  }

  async function openNode(airport: Airport) {
    try {
      const detail = await dashboardApi.getNodeDetail(airport.icaoCode);
      setSelectedNode({ detail, point: { longitude: airport.longitude, latitude: airport.latitude } });
    } catch {
      setSelectedNode(null);
    }
  }

  async function resolveAlert(alertId: number): Promise<void> {
    try {
      await alertsApi.resolve(alertId, { user: 'Operador', note: 'Resuelta desde el centro de simulación' });
      setAlerts((prev) => prev.filter((alert) => alert.id !== alertId));
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'No se pudo resolver la alerta.');
    }
  }

  useEffect(() => {
    if (!simulacionActiva) {
      setAlerts([]);
      return;
    }
    let cancelled = false;
    const load = async () => {
      try {
        const next = await alertsApi.list();
        if (!cancelled) setAlerts(next);
      } catch {
        if (!cancelled) setAlerts([]);
      }
    };
    void load();
    const timer = setInterval(load, 10000);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [simulacionActiva]);

  return (
    <div className="app-page" style={{ display: 'grid', gridTemplateRows: 'auto 1fr', gap: 0 }}>
      <header className="page-head">
        <div>
          <h1 className="page-head-title">Centro de simulación</h1>
          <p className="page-head-subtitle">Configura escenarios, ejecuta y monitorea la operación en tiempo real</p>
        </div>
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          <button className="btn btn-primary" disabled={simulacionActiva} onClick={onStart}>Iniciar</button>
          <button className="btn btn-primary" disabled={!canResume} onClick={onResume}>Reanudar</button>
          <button className="btn btn-neutral" disabled={!sim?.running || Boolean(sim?.paused)} onClick={onPause}>Pausar</button>
          <button className="btn btn-danger" disabled={!sim?.running && !sim?.paused} onClick={onStop}>Detener</button>
          {speedOptions.map((speed) => (
            <button
              key={speed}
              type="button"
              disabled={config.scenario === 'DAY_TO_DAY' || !simulacionActiva}
              className={`chip ${sim?.speed === speed ? 'is-active' : ''}`}
              onClick={() => void onSetSpeed(speed)}
            >
              {speed}x
            </button>
          ))}
        </div>
      </header>

      <div style={{ padding: '14px 16px 16px 20px' }}>
        <div style={{ display: 'grid', gridTemplateColumns: panelCollapsed ? '48px 1fr 320px' : '360px 1fr 320px', gap: 12, minHeight: 'calc(100vh - 110px)', transition: 'grid-template-columns 0.25s ease' }}>
          <aside className="surface-panel" style={{ padding: panelCollapsed ? '14px 6px' : 14, overflowY: 'auto', position: 'relative' }}>
            <button
              onClick={() => {
                const next = !panelCollapsed;
                setPanelCollapsed(next);
                localStorage.setItem('pdds-panel-collapsed', String(next));
              }}
              title={panelCollapsed ? 'Expandir panel' : 'Colapsar panel'}
              style={{ position: 'absolute', top: 10, right: panelCollapsed ? 'auto' : 10, left: panelCollapsed ? '50%' : 'auto', transform: panelCollapsed ? 'translateX(-50%)' : 'none', background: 'transparent', border: '1px solid #32364f', borderRadius: 6, color: '#9ca7c8', cursor: 'pointer', padding: '4px 8px', fontSize: 14, zIndex: 2 }}
            >
              {panelCollapsed ? '\u25B6' : '\u25C0'}
            </button>
            {!panelCollapsed && (
              <>
                <p style={{ margin: 0, fontWeight: 700, color: '#eaf0ff' }}>Escenario y parámetros</p>
                <p style={{ margin: '6px 0 0', fontSize: 12, color: '#93a0bf' }}>Parámetros de simulación, sin algoritmos.</p>

            <div style={{ marginTop: 12, display: 'grid', gap: 10 }}>
              <label>
                <span style={{ fontSize: 12, color: '#9ca7c8' }}>Escenario</span>
                <select disabled={!canEditConfig} value={config.scenario} onChange={(e) => patchConfig({ scenario: e.target.value as SimScenario })} style={field}>
                  <option value="DAY_TO_DAY">Operación día a día</option>
                  <option value="PERIOD_SIMULATION">Simulación período</option>
                  <option value="COLLAPSE_TEST">Prueba de colapso</option>
                </select>
              </label>

              {(config.scenario === 'PERIOD_SIMULATION' || config.scenario === 'COLLAPSE_TEST') && (
                <label>
                  <span style={{ fontSize: 12, color: '#9ca7c8' }}>Duración (días)</span>
                  <select disabled={!canEditConfig} value={config.simulationDays} onChange={(e) => patchConfig({ simulationDays: Number(e.target.value) as 3 | 5 | 7 })} style={field}>
                    <option value={3}>3 días</option>
                    <option value={5}>5 días</option>
                    <option value={7}>7 días</option>
                  </select>
                </label>
              )}

                <label>
                <span style={{ fontSize: 12, color: '#9ca7c8' }}>Fecha del escenario</span>
                <input disabled={!canEditConfig} type="date" value={config.scenarioStartDate} onChange={(e) => patchConfig({ scenarioStartDate: e.target.value })} style={field} />
              </label>

              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
                <label>
                  <span style={{ fontSize: 12, color: '#9ca7c8' }}>Umbral normal %</span>
                  <input disabled={!canEditConfig} type="number" min={50} max={99} value={config.normalThresholdPct} onChange={(e) => patchConfig({ normalThresholdPct: Number(e.target.value) })} style={field} />
                </label>
                <label>
                  <span style={{ fontSize: 12, color: '#9ca7c8' }}>Umbral alerta %</span>
                  <input disabled={!canEditConfig} type="number" min={51} max={100} value={config.warningThresholdPct} onChange={(e) => patchConfig({ warningThresholdPct: Number(e.target.value) })} style={field} />
                </label>
              </div>

              {config.scenario === 'COLLAPSE_TEST' && (
                <>
                  <label>
                    <span style={{ fontSize: 12, color: '#9ca7c8' }}>Tasa de cancelación %</span>
                    <input disabled={!canEditConfig} type="number" min={0} max={100} value={config.cancellationRatePct} onChange={(e) => patchConfig({ cancellationRatePct: Number(e.target.value) })} style={field} />
                  </label>
                  <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
                    <label>
                      <span style={{ fontSize: 12, color: '#9ca7c8' }}>Capacidad intra</span>
                      <input disabled={!canEditConfig} type="number" min={200} max={5000} value={config.intraNodeCapacity} onChange={(e) => patchConfig({ intraNodeCapacity: Number(e.target.value) })} style={field} />
                    </label>
                    <label>
                      <span style={{ fontSize: 12, color: '#9ca7c8' }}>Capacidad inter</span>
                      <input disabled={!canEditConfig} type="number" min={200} max={5000} value={config.interNodeCapacity} onChange={(e) => patchConfig({ interNodeCapacity: Number(e.target.value) })} style={field} />
                    </label>
                  </div>
                </>
              )}

              <button className="btn btn-primary" disabled={!canEditConfig || savingConfig} onClick={() => void saveScenarioConfig()}>
                {savingConfig ? 'Guardando...' : 'Guardar configuración'}
              </button>

                <div className="state-panel" style={{ marginTop: 6 }}>
                  <p className="state-panel-title">Estado runtime</p>
                <p className="state-panel-copy">
                  {!stateLoaded ? 'Cargando...' : sim?.paused ? 'Simulación pausada' : sim?.running ? 'Simulación corriendo' : 'Simulación detenida'}
                  {stateLoaded && <>{' · '}{sim ? escenarioLabel(sim.scenario) : 'Sin estado'}</>}
                </p>
                  <p className="state-panel-copy">Demanda futura: {sim?.projectedDemandReady ? 'Lista' : 'No disponible'}</p>
                  {!sim?.projectedDemandReady ? (
                    <div style={{ marginTop: 10, display: 'grid', gap: 8 }}>
                      <p className="state-panel-copy" style={{ color: '#fbbf24' }}>
                        Falta generar demanda futura para poder iniciar la simulación.
                      </p>
                      <button className="btn btn-neutral" disabled={generatingDemand || simulacionActiva} onClick={onGenerateFutureDemand}>
                        {generatingDemand ? 'Generando...' : 'Generar demanda futura'}
                      </button>
                    </div>
                  ) : null}
                  <p className="state-panel-copy">Inicio efectivo: {sim?.effectiveScenarioStartAt ? new Date(sim.effectiveScenarioStartAt).toLocaleDateString('es-PE') : 'Sin definir'}</p>
                 <p className="state-panel-copy">Modo de tiempo: {sim ? formatTimeScaleLabel(sim.simulationSecondsPerTick) : 'Sin definir'}</p>
                 {config.scenario === 'DAY_TO_DAY' ? <p className="state-panel-copy">La operación día a día mantiene velocidad fija para preservar continuidad visual.</p> : null}
                  {sim?.dateAdjusted && sim?.dateAdjustmentReason ? <p className="state-panel-copy" style={{ color: '#fbbf24' }}>{sim.dateAdjustmentReason}</p> : null}
                </div>
            </div>
              </>
            )}
          </aside>

          <section className="surface-panel" style={{ overflow: 'hidden', position: 'relative' }}>
            <div style={{ position: 'absolute', top: 12, left: 56, zIndex: 5, display: 'flex', gap: 8 }}>
              <button className={`chip${mapMode === 'MAPA' ? ' is-active' : ''}`} onClick={() => setMapMode('MAPA')}>Mapa</button>
              <button className={`chip${mapMode === 'SATELITAL' ? ' is-active' : ''}`} onClick={() => setMapMode('SATELITAL')}>Satelital</button>
            </div>

            {/* Dark overlay to improve plane visibility — FlightRadar style */}
            <div style={{ position: 'absolute', inset: 0, zIndex: 1, background: 'rgba(8, 10, 20, 0.35)', pointerEvents: 'none' }} />

            <Map
              ref={mapRef}
              mapStyle={mapMode === 'MAPA' ? MAP_STYLE : SATELLITE_STYLE}
              initialViewState={INITIAL_VIEW}
              style={{ width: '100%', height: '100%' }}
              renderWorldCopies={false}
              onClick={() => {
                  setSelectedShipmentId(null);
                  setSelectedFlightId(null);
                  setSelectedNode(null);
              }}
            >
              <NavigationControl position="top-left" />

              {airports.map((a) => (
                <Marker key={a.id} longitude={a.longitude} latitude={a.latitude} anchor="center" onClick={(e) => { e.originalEvent.stopPropagation(); void openNode(a); }}>
                  <button
                    title={`${a.icaoCode} ${a.occupancyPct.toFixed(1)}%`}
                    style={{
                      width: 12,
                      height: 12,
                      borderRadius: 99,
                      border: '2px solid rgba(8,17,31,0.95)',
                      background: a.status === 'CRITICO' ? '#ef4444' : a.status === 'ALERTA' ? '#f59e0b' : '#22c55e',
                      boxShadow: '0 0 0 2px rgba(255,255,255,0.55), 0 0 10px rgba(8,17,31,0.55)',
                      cursor: 'pointer',
                    }}
                  />
                </Marker>
              ))}

              {simulacionActiva && mapLive.map((row) => {
                const point = { longitude: row.currentLongitude, latitude: row.currentLatitude };
                return (
                  <Marker
                    key={row.shipmentId}
                    longitude={point.longitude}
                    latitude={point.latitude}
                    anchor="center"
                    onClick={(e) => {
                      e.originalEvent.stopPropagation();
                      setSelectedShipmentId(row.shipmentId);
                    }}
                  >
                    <span
                      title={row.shipmentCode}
                      role="button"
                      tabIndex={0}
                      style={{
                        display: 'inline-flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        width: 34,
                        height: 34,
                        fontSize: 16,
                        color: selectedShipmentId === row.shipmentId ? '#fde68a' : '#dbeafe',
                        cursor: 'pointer',
                        lineHeight: 1,
                        borderRadius: 999,
                        border: `1px solid ${selectedShipmentId === row.shipmentId ? '#fbbf24' : '#60a5fa'}`,
                        background: selectedShipmentId === row.shipmentId ? 'rgba(124, 58, 237, 0.92)' : 'rgba(15, 23, 42, 0.92)',
                        boxShadow: selectedShipmentId === row.shipmentId
                          ? '0 0 0 3px rgba(251, 191, 36, 0.25), 0 10px 24px rgba(15, 23, 42, 0.55)'
                          : '0 8px 20px rgba(15, 23, 42, 0.5)',
                      }}
                    >
                      ◆
                    </span>
                  </Marker>
                );
              })}

              {simulacionActiva && renderedFlights.map((row) => {
                const rotation = computeBearing(
                  row.currentLatitude,
                  row.currentLongitude,
                  row.destinationLatitude,
                  row.destinationLongitude,
                );

                return (
                  <Marker
                    key={`flight-${row.flightId}`}
                    longitude={row.currentLongitude}
                    latitude={row.currentLatitude}
                    anchor="center"
                    onClick={(e) => {
                      e.originalEvent.stopPropagation();
                      setSelectedFlightId(row.flightId);
                    }}
                  >
                    <button
                      title={row.flightCode}
                      type="button"
                      className="map-flight-button"
                    >
                      <PlaneIcon rotation={rotation} selected={selectedFlightId === row.flightId} />
                    </button>
                  </Marker>
                );
              })}

              {simulacionActiva && selectedShipment ? (
                <Popup longitude={selectedShipment.currentLongitude} latitude={selectedShipment.currentLatitude} closeButton={false} closeOnClick={false} anchor="top">
                  <div style={{ minWidth: 180 }}>
                    <p style={{ margin: 0, fontWeight: 700 }}>{selectedShipment.shipmentCode}</p>
                    <p style={{ margin: '4px 0 0', fontSize: 12 }}>{selectedShipment.originIcao} {'->'} {selectedShipment.destinationIcao}</p>
                    <p style={{ margin: '4px 0 0', fontSize: 12 }}>Progreso: {selectedShipment.progressPct.toFixed(1)}%</p>
                    <p style={{ margin: '4px 0 0', fontSize: 12 }}><Link href={`/shipments?selected=${selectedShipment.shipmentId}`} style={{ color: '#2563eb' }}>Abrir envío</Link></p>
                  </div>
                </Popup>
              ) : null}

              {simulacionActiva && selectedFlight ? (
                <Popup longitude={selectedFlight.currentLongitude} latitude={selectedFlight.currentLatitude} closeButton={false} closeOnClick={false} anchor="top">
                  <div style={{ minWidth: 180 }}>
                    <p style={{ margin: 0, fontWeight: 700 }}>{selectedFlight.flightCode}</p>
                    <p style={{ margin: '4px 0 0', fontSize: 12 }}>{selectedFlight.originIcao} {'->'} {selectedFlight.destinationIcao}</p>
                    <p style={{ margin: '4px 0 0', fontSize: 12 }}>Carga: {selectedFlight.loadPct.toFixed(1)}%</p>
                    <p style={{ margin: '4px 0 0', fontSize: 12 }}><Link href={`/flights?selected=${selectedFlight.flightId}&status=IN_FLIGHT`} style={{ color: '#2563eb' }}>Abrir vuelo</Link></p>
                  </div>
                </Popup>
              ) : null}

              {selectedNode ? (
                <Popup longitude={selectedNode.point.longitude} latitude={selectedNode.point.latitude} closeOnClick={false} anchor="top" onClose={() => setSelectedNode(null)}>
                  <div style={{ minWidth: 220 }}>
                    <p style={{ margin: 0, fontWeight: 700 }}>{selectedNode.detail.icaoCode} · {selectedNode.detail.city}</p>
                    <p style={{ margin: '4px 0 0', fontSize: 12 }}>Ocupación: {selectedNode.detail.occupancyPct.toFixed(1)}%</p>
                    <p style={{ margin: '4px 0 0', fontSize: 12 }}>Capacidad: {selectedNode.detail.currentStorageLoad}/{selectedNode.detail.maxStorageCapacity}</p>
                    <p style={{ margin: '4px 0 0', fontSize: 12 }}>Vuelos programados: {selectedNode.detail.scheduledFlights}</p>
                  </div>
                </Popup>
              ) : null}
            </Map>
          </section>

          <aside style={{ display: 'grid', gap: 10, alignContent: 'start' }}>
            <KpiCard title="Vuelos visibles" value={String(simulacionActiva ? renderedFlights.length : 0)} status={semaforoRiesgo(simulacionActiva ? renderedFlights.length : 0)} />
            <KpiCard title="Próximos vuelos" value={String(simulacionActiva ? (overview?.nextScheduledFlights ?? 0) : 0)} status={semaforoRiesgo(simulacionActiva ? (overview?.nextScheduledFlights ?? 0) : 0)} />
            <KpiCard title="Envíos visibles" value={String(simulacionActiva ? mapLive.length : 0)} status={semaforoRiesgo(simulacionActiva ? mapLive.length : 0)} />
            <KpiCard title="SLA actual" value={fmtPct(simulacionActiva ? (overview?.slaCompliancePct ?? 0) : 0)} status={semaforoSla(simulacionActiva ? (overview?.slaCompliancePct ?? 0) : 0)} />
            <KpiCard title="Nodos críticos" value={String(system?.criticoAirports ?? 0)} status={semaforoRiesgo(system?.criticoAirports ?? 0)} />
            <KpiCard title="Alertas operativas" value={String(simulacionActiva ? (overview?.unresolvedAlerts ?? 0) : 0)} status={semaforoRiesgo(simulacionActiva ? (overview?.unresolvedAlerts ?? 0) : 0)} />
            <KpiCard title="Riesgo de colapso" value={fmtPct(collapseRisk?.risk ?? 0)} status={semaforoRiesgo(collapseRisk?.risk ?? 0)} />
            <article className="surface-panel" style={{ padding: 14 }}>
              <p style={{ margin: 0, fontSize: 12, color: '#a9b3cf', display: 'flex', alignItems: 'center', gap: 8 }}>
                <span style={{ width: 8, height: 8, borderRadius: 99, background: simulacionActiva ? '#22c55e' : '#64748b' }} />
                Reloj simulado
              </p>
              <strong style={{ marginTop: 4, display: 'block', fontSize: 18, color: '#eff3ff' }}>
                {!stateLoaded ? 'Cargando...' : displayedSimTime ?? '-'}
              </strong>
              {stateLoaded && sim ? <p style={{ margin: '6px 0 0', color: '#9ca3bf', fontSize: 12 }}>{formatTimeScaleLabel(sim.simulationSecondsPerTick)}</p> : null}
            </article>
            <article className="surface-panel" style={{ padding: 14 }}>
              <p style={{ margin: 0, fontSize: 12, color: '#a9b3cf' }}>Alertas activas</p>
              {alerts.length === 0 ? (
                <p style={{ margin: '8px 0 0', color: '#9ca3bf', fontSize: 13 }}>Sin alertas operativas pendientes.</p>
              ) : (
                <div style={{ marginTop: 8, display: 'grid', gap: 8 }}>
                  {alerts.slice(0, 4).map((alert) => (
                    <div key={alert.id} style={{ padding: '10px 12px', borderRadius: 10, background: '#171a29', border: '1px solid #262940' }}>
                      <p style={{ margin: 0, color: '#eaf0ff', fontSize: 13, fontWeight: 700 }}>{alert.type}</p>
                      <p style={{ margin: '4px 0 0', color: '#9ca3bf', fontSize: 12 }}>{alert.shipmentCode ?? 'Sin envío'} · {alert.note}</p>
                      <div style={{ marginTop: 8, display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                        {alert.shipmentId ? <Link href={`/shipments?selected=${alert.shipmentId}`} style={{ color: '#93c5fd', fontSize: 12 }}>Abrir envío</Link> : null}
                        <button className="chip" onClick={() => void resolveAlert(alert.id)}>Resolver</button>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </article>
          </aside>
        </div>
      </div>

      {error ? (
        <div className="state-panel is-error" style={{ margin: '0 16px 16px 20px' }}>
          <p className="state-panel-title">Error operativo</p>
          <p className="state-panel-copy">{error}</p>
        </div>
      ) : null}
    </div>
  );
}

const field: CSSProperties = {
  width: '100%',
  height: 40,
  borderRadius: 8,
  border: '1px solid #32364f',
  background: '#171a29',
  color: '#dce4ff',
  padding: '0 10px',
  fontSize: 13,
};
