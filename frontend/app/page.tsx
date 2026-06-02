'use client';

import { memo, useCallback, useEffect, useMemo, useRef, useState, type CSSProperties } from 'react';
import Link from 'next/link';
import Map, { Marker, NavigationControl, Popup, type MapLayerMouseEvent, type MapRef } from 'react-map-gl/maplibre';
import type { StyleSpecification } from 'maplibre-gl';
import { dashboardApi } from '@/lib/api/dashboardApi';
import { alertsApi } from '@/lib/api/alertsApi';
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
const SPEED_OPTIONS = [1, 5, 20] as const;
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
  normalThresholdPct: number;
  warningThresholdPct: number;
  slaWarnPct: number;
  slaCritPct: number;
  riskShipmentsWarnPct: number;
  riskShipmentsCritPct: number;
  criticalNodesWarnPct: number;
  criticalNodesCritPct: number;
};

function toDateTimeInput(value: string | null): string {
  if (!value) return '';
  // value es un LocalDateTime "YYYY-MM-DDTHH:mm(:ss)" sin zona; el input datetime-local usa "YYYY-MM-DDTHH:mm".
  // Se extrae por string (NO con new Date()) para evitar corrimientos por huso del navegador.
  const dt = /^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2})/.exec(value);
  if (dt) return `${dt[1]}T${dt[2]}`;
  const d = /^(\d{4}-\d{2}-\d{2})/.exec(value);
  return d ? `${d[1]}T00:00` : '';
}

function escenarioLabel(value: SimScenario): string {
  if (value === 'PERIOD_SIMULATION') return 'Simulación período';
  if (value === 'COLLAPSE_TEST') return 'Prueba de colapso';
  return 'Operación día a día';
}

function colorSemaforo(estado: 'ok' | 'warn' | 'bad' | 'neutral'): string {
  if (estado === 'neutral') return '#64748b';
  if (estado === 'ok') return '#22c55e';
  if (estado === 'warn') return '#f59e0b';
  return '#ef4444';
}

// SLA: valores altos = mejor (verde por encima de warnPct, ámbar por encima de critPct).
function semaforoSla(value: number, warnPct: number, critPct: number): 'ok' | 'warn' | 'bad' {
  if (value >= warnPct) return 'ok';
  if (value >= critPct) return 'warn';
  return 'bad';
}

// Métricas de riesgo: valores bajos = mejor (verde bajo warnMax, ámbar bajo critMax, rojo por encima).
function semaforoMax(value: number, warnMax: number, critMax: number): 'ok' | 'warn' | 'bad' {
  if (value <= warnMax) return 'ok';
  if (value <= critMax) return 'warn';
  return 'bad';
}

function fmtPct(value: number): string {
  return `${value.toFixed(1)}%`;
}

function formatDuration(totalSeconds: number): string {
  const s = Math.max(0, Math.floor(totalSeconds));
  const d = Math.floor(s / 86400);
  const h = Math.floor((s % 86400) / 3600);
  const m = Math.floor((s % 3600) / 60);
  const parts: string[] = [];
  if (d) parts.push(`${d}d`);
  if (h || d) parts.push(`${h}h`);
  parts.push(`${m}m`);
  return parts.join(' ');
}

function formatSimTime(value: Date): string {
  return SIM_TIME_FORMATTER.format(value);
}

function formatTimeScaleLabel(simulationSecondsPerTick: number | null | undefined, tickIntervalMs: number | null | undefined): string {
  const simSeconds = Math.max(1, simulationSecondsPerTick ?? 1);
  const realSeconds = Math.max(1, tickIntervalMs ?? 1_000) / 1_000;
  const realLabel = Number.isInteger(realSeconds) ? `${realSeconds.toFixed(0)} s real` : `${realSeconds.toFixed(1)} s real`;
  if (simSeconds % 60 === 0) {
    const minutes = simSeconds / 60;
    return `${minutes} min simulados = ${realLabel}`;
  }
  return `${simSeconds} s simulados = ${realLabel}`;
}

function normalizeLongitude(longitude: number): number {
  let value = longitude;
  while (value > 180) value -= 360;
  while (value < -180) value += 360;
  return value;
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

function BootstrapOverlay({ planned, total, message }: { planned: number; total: number; message: string | null | undefined }) {
  const safeTotal = Math.max(0, total);
  const safePlanned = Math.max(0, Math.min(planned, safeTotal || planned));
  const progress = safeTotal > 0 ? (safePlanned * 100) / safeTotal : 0;

  return (
    <div
      style={{
        position: 'absolute',
        inset: 0,
        zIndex: 7,
        display: 'grid',
        placeItems: 'center',
        background: 'rgba(5, 8, 18, 0.68)',
        backdropFilter: 'blur(4px)',
        padding: 24,
      }}
    >
      <article className="surface-panel" style={{ width: 'min(460px, 100%)', padding: 22, border: '1px solid rgba(96, 165, 250, 0.28)' }}>
        <p style={{ margin: 0, color: '#c7d2fe', fontSize: 12, letterSpacing: 1.2, textTransform: 'uppercase' }}>Preplanificación</p>
        <h3 style={{ margin: '8px 0 6px', color: '#eff3ff', fontSize: 24 }}>Programando envíos antes de iniciar la simulación</h3>
        <p style={{ margin: 0, color: '#a9b3cf', fontSize: 14 }}>{message ?? 'Construyendo rutas iniciales del período para arrancar con operación consistente.'}</p>
        <div style={{ marginTop: 18, height: 12, borderRadius: 999, background: 'rgba(148, 163, 184, 0.18)', overflow: 'hidden' }}>
          <div style={{ width: `${progress.toFixed(1)}%`, height: '100%', background: 'linear-gradient(90deg, #60a5fa 0%, #818cf8 55%, #a78bfa 100%)' }} />
        </div>
        <div style={{ marginTop: 12, display: 'flex', justifyContent: 'space-between', color: '#dbeafe', fontSize: 14 }}>
          <span>{safePlanned.toLocaleString('es-PE')} / {safeTotal.toLocaleString('es-PE')} envíos</span>
          <strong>{progress.toFixed(1)}%</strong>
        </div>
      </article>
    </div>
  );
}

function KpiCard({ title, value, status }: { title: string; value: string; status: 'ok' | 'warn' | 'bad' | 'neutral' }) {
  return (
    <article className="surface-panel" style={{ flex: 1, minHeight: 58, padding: '12px 14px', display: 'flex', flexDirection: 'column', justifyContent: 'center', gap: 8, borderLeft: `3px solid ${colorSemaforo(status)}` }}>
      <span style={{ fontSize: 12, color: '#a9b3cf', display: 'flex', alignItems: 'center', gap: 8 }}>
        <span style={{ width: 9, height: 9, borderRadius: 99, flexShrink: 0, background: colorSemaforo(status), boxShadow: `0 0 0 3px ${colorSemaforo(status)}33` }} />
        {title}
      </span>
      <strong style={{ fontSize: 26, color: '#eff3ff' }}>{value}</strong>
    </article>
  );
}

function PlaneIcon({ rotation, selected }: { rotation: number; selected: boolean }) {
  const fill = selected ? '#f97316' : '#ffffff';
  const stroke = selected ? '#fff7ed' : '#08111f';

  return (
    <svg
      width="24"
      height="24"
      viewBox="0 0 24 24"
      aria-hidden="true"
      style={{
        display: 'block',
        overflow: 'visible',
        transform: `rotate(${rotation}deg)`,
        transformOrigin: '50% 50%',
        pointerEvents: 'none',
      }}
    >
      <path
        d="M21 16.5V14l-8-5V3.5a1.5 1.5 0 0 0-3 0V9l-8 5v2.5l8-2.5V20l-2 1.5V23l4-1 4 1v-1.5L14 20v-6l8 2.5Z"
        fill={fill}
        stroke={stroke}
        strokeWidth="1.3"
        strokeLinejoin="round"
        strokeLinecap="round"
      />
    </svg>
  );
}

type SelectedFlightView = MapLiveFlight & {
  currentLatitude: number;
  currentLongitude: number;
};

type SimulationMapPanelProps = {
  airports: Airport[];
  flights: MapLiveFlight[];
  renderedFlights: MapLiveFlight[];
  active: boolean;
  mapMode: MapMode;
  onMapModeChange: (mode: MapMode) => void;
  selectedShipment: {
    shipmentId: number;
    shipmentCode: string;
    originIcao: string;
    destinationIcao: string;
    currentLatitude: number;
    currentLongitude: number;
    progressPct: number;
  } | null;
  selectedFlight: SelectedFlightView | null;
  selectedFlightId: number | null;
  selectedNode: { point: { longitude: number; latitude: number }; detail: NodeDetail } | null;
  onSelectedFlightIdChange: (flightId: number | null) => void;
  onSelectedFlightVisualChange: (flight: MapLiveFlight | null) => void;
  onSelectedNodeChange: (node: { point: { longitude: number; latitude: number }; detail: NodeDetail } | null) => void;
  onSelectedShipmentChange: (shipmentId: number | null) => void;
  onOpenNode: (airport: Airport) => void;
  bootstrapping: boolean;
  bootstrapPlannedShipments: number;
  bootstrapTotalShipments: number;
  bootstrapMessage: string | null | undefined;
};

const SimulationMapPanel = memo(function SimulationMapPanel({
  airports,
  flights,
  renderedFlights,
  active,
  mapMode,
  onMapModeChange,
  selectedShipment,
  selectedFlight,
  selectedFlightId,
  selectedNode,
  onSelectedFlightIdChange,
  onSelectedFlightVisualChange,
  onSelectedNodeChange,
  onSelectedShipmentChange,
  onOpenNode,
  bootstrapping,
  bootstrapPlannedShipments,
  bootstrapTotalShipments,
  bootstrapMessage,
}: SimulationMapPanelProps) {
  const mapRef = useRef<MapRef | null>(null);

  const handleMapClick = useCallback((event: MapLayerMouseEvent) => {
    onSelectedShipmentChange(null);
    onSelectedFlightIdChange(null);
    onSelectedFlightVisualChange(null);
    onSelectedNodeChange(null);
  }, [onSelectedFlightIdChange, onSelectedFlightVisualChange, onSelectedNodeChange, onSelectedShipmentChange]);

  return (
    <section className="surface-panel" style={{ overflow: 'hidden', position: 'relative' }}>
      <div style={{ position: 'absolute', top: 12, left: 56, zIndex: 5, display: 'flex', gap: 8 }}>
        <button className={`chip${mapMode === 'MAPA' ? ' is-active' : ''}`} onClick={() => onMapModeChange('MAPA')}>Mapa</button>
        <button className={`chip${mapMode === 'SATELITAL' ? ' is-active' : ''}`} onClick={() => onMapModeChange('SATELITAL')}>Satelital</button>
      </div>

      <div style={{ position: 'absolute', inset: 0, zIndex: 1, background: 'rgba(8, 10, 20, 0.24)', pointerEvents: 'none' }} />

      <Map
        ref={mapRef}
        mapStyle={mapMode === 'MAPA' ? MAP_STYLE : SATELLITE_STYLE}
        initialViewState={INITIAL_VIEW}
        style={{ width: '100%', height: '100%' }}
        renderWorldCopies={false}
        onClick={handleMapClick}
      >
        <NavigationControl position="top-left" />

        {airports.map((a) => (
          <Marker key={a.id} longitude={a.longitude} latitude={a.latitude} anchor="center" onClick={(e) => { e.originalEvent.stopPropagation(); onOpenNode(a); }}>
            <button
              title={`${a.icaoCode} ${a.occupancyPct.toFixed(1)}%`}
              style={{
                width: 10,
                height: 10,
                borderRadius: 99,
                border: '1px solid rgba(8,17,31,0.9)',
                background: a.status === 'CRITICO' ? '#ef4444' : a.status === 'ALERTA' ? '#f59e0b' : '#22c55e',
                cursor: 'pointer',
              }}
            />
          </Marker>
        ))}

        {active && renderedFlights.map((row) => {
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
                onSelectedShipmentChange(null);
                onSelectedNodeChange(null);
                onSelectedFlightIdChange(row.flightId);
              }}
            >
              <button
                title={row.flightCode}
                type="button"
                className="map-flight-button"
                style={{
                  background: 'transparent',
                  border: 'none',
                  padding: 0,
                  margin: 0,
                  cursor: 'pointer',
                  display: 'block',
                }}
              >
                <PlaneIcon rotation={rotation} selected={selectedFlightId === row.flightId} />
              </button>
            </Marker>
          );
        })}

        {active && selectedShipment ? (
          <Popup longitude={selectedShipment.currentLongitude} latitude={selectedShipment.currentLatitude} closeButton={false} closeOnClick={false} anchor="top">
            <div style={{ minWidth: 180 }}>
              <p style={{ margin: 0, fontWeight: 700 }}>{selectedShipment.shipmentCode}</p>
              <p style={{ margin: '4px 0 0', fontSize: 12 }}>{selectedShipment.originIcao} {'->'} {selectedShipment.destinationIcao}</p>
              <p style={{ margin: '4px 0 0', fontSize: 12 }}>Progreso: {selectedShipment.progressPct.toFixed(1)}%</p>
              <p style={{ margin: '4px 0 0', fontSize: 12 }}><Link href={`/shipments?selected=${selectedShipment.shipmentId}`} style={{ color: '#2563eb' }}>Abrir envío</Link></p>
            </div>
          </Popup>
        ) : null}

        {active && selectedFlight ? (
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
          <Popup longitude={selectedNode.point.longitude} latitude={selectedNode.point.latitude} closeOnClick={false} anchor="top" onClose={() => onSelectedNodeChange(null)}>
            <div style={{ minWidth: 220 }}>
              <p style={{ margin: 0, fontWeight: 700 }}>{selectedNode.detail.icaoCode} · {selectedNode.detail.city}</p>
              <p style={{ margin: '4px 0 0', fontSize: 12 }}>Ocupación: {selectedNode.detail.occupancyPct.toFixed(1)}%</p>
              <p style={{ margin: '4px 0 0', fontSize: 12 }}>Capacidad: {selectedNode.detail.currentStorageLoad}/{selectedNode.detail.maxStorageCapacity}</p>
              <p style={{ margin: '4px 0 0', fontSize: 12 }}>Vuelos programados: {selectedNode.detail.scheduledFlights}</p>
            </div>
          </Popup>
        ) : null}
      </Map>
      {bootstrapping ? (
        <BootstrapOverlay
          planned={bootstrapPlannedShipments}
          total={bootstrapTotalShipments}
          message={bootstrapMessage}
        />
      ) : null}
    </section>
  );
});

export default function HomePage() {
  // Shared sim state + live operational data from context — persists across navigation
  const {
    sim,
    setSim,
    loaded: stateLoaded,
    overview,
    system,
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
  const [draftDirty, setDraftDirty] = useState(false);
  const [alerts, setAlerts] = useState<OperationalAlert[]>([]);
  const [panelCollapsed, setPanelCollapsed] = useState(() => {
    if (typeof window === 'undefined') return false;
    return localStorage.getItem('pdds-panel-collapsed') === 'true';
  });
  const [kpisCollapsed, setKpisCollapsed] = useState(() => {
    if (typeof window === 'undefined') return false;
    return localStorage.getItem('pdds-kpis-collapsed') === 'true';
  });
  const [selectedFlightVisual, setSelectedFlightVisual] = useState<MapLiveFlight | null>(null);
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
    normalThresholdPct: 70,
    warningThresholdPct: 90,
    slaWarnPct: 90,
    slaCritPct: 75,
    riskShipmentsWarnPct: 10,
    riskShipmentsCritPct: 25,
    criticalNodesWarnPct: 10,
    criticalNodesCritPct: 25,
  });

  const simulacionActiva = Boolean(sim?.running || sim?.paused);
  const canResume = Boolean(sim?.running && sim?.paused);
  const canEditConfig = !simulacionActiva;
  const speedOptions = config.scenario === 'DAY_TO_DAY' ? [1] : SPEED_OPTIONS;
  const bootstrapping = Boolean(sim?.bootstrapping);

  const hydrateFromState = useCallback((state: typeof sim) => {
    if (!state) return;
    const effectiveStart = state.scenarioStartAt
      || (state.projectedFrom ? `${state.projectedFrom}T00:00` : '');
    setConfig({
      scenario: state.scenario,
      simulationDays: state.simulationDays === 3 || state.simulationDays === 7 ? state.simulationDays : 5,
      scenarioStartDate: toDateTimeInput(effectiveStart),
      normalThresholdPct: state.normalThresholdPct,
      warningThresholdPct: state.warningThresholdPct,
      slaWarnPct: state.slaWarnPct,
      slaCritPct: state.slaCritPct,
      riskShipmentsWarnPct: state.riskShipmentsWarnPct,
      riskShipmentsCritPct: state.riskShipmentsCritPct,
      criticalNodesWarnPct: state.criticalNodesWarnPct,
      criticalNodesCritPct: state.criticalNodesCritPct,
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
      setSelectedFlightVisual(null);
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
    const fromFlights = mapLiveFlights.map((flight) => previousById.get(flight.flightId) ?? flight);

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
  }, [mapLiveFlights, simulacionActiva]);

  useEffect(() => {
    if (selectedFlightId == null) {
      setSelectedFlightVisual(null);
    }
  }, [selectedFlightId]);

  const flightSnapshotById = useMemo(
    () => new globalThis.Map(mapLiveFlights.map((flight) => [flight.flightId, flight] as const)),
    [mapLiveFlights],
  );

  const selectedShipment = useMemo(() => mapLive.find((f) => f.shipmentId === selectedShipmentId) ?? null, [mapLive, selectedShipmentId]);
  const selectedFlight = useMemo(() => {
    if (selectedFlightId == null) {
      return null;
    }
    const snapshot = flightSnapshotById.get(selectedFlightId) ?? null;
    const visual = selectedFlightVisual && selectedFlightVisual.flightId === selectedFlightId
      ? selectedFlightVisual
      : null;
    if (!snapshot && !visual) {
      return null;
    }
    return {
      ...(snapshot ?? visual!),
      currentLatitude: visual?.currentLatitude ?? snapshot?.currentLatitude ?? 0,
      currentLongitude: visual?.currentLongitude ?? snapshot?.currentLongitude ?? 0,
    };
  }, [flightSnapshotById, selectedFlightId, selectedFlightVisual]);

  useEffect(() => {
    if (selectedFlightId == null) {
      return;
    }
    if (!flightSnapshotById.has(selectedFlightId)) {
      setSelectedFlightId(null);
      setSelectedFlightVisual(null);
    }
  }, [flightSnapshotById, selectedFlightId]);

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
        slaWarnPct: config.slaWarnPct,
        slaCritPct: config.slaCritPct,
        riskShipmentsWarnPct: config.riskShipmentsWarnPct,
        riskShipmentsCritPct: config.riskShipmentsCritPct,
        criticalNodesWarnPct: config.criticalNodesWarnPct,
        criticalNodesCritPct: config.criticalNodesCritPct,
      };
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
      setSelectedFlightId(null);
      setSelectedFlightVisual(null);
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

  const openNode = useCallback(async (airport: Airport) => {
    try {
      const detail = await dashboardApi.getNodeDetail(airport.icaoCode);
      setSelectedNode({ detail, point: { longitude: airport.longitude, latitude: airport.latitude } });
    } catch {
      setSelectedNode(null);
    }
  }, []);

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
          <h1 className="page-head-title">Tasf.B2B — Operación logística</h1>
          <p className="page-head-subtitle">Monitoreo en tiempo real del traslado de equipaje entre aeropuertos</p>
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
        <div style={{ display: 'grid', gridTemplateColumns: `${panelCollapsed ? '48px' : '340px'} 1fr ${kpisCollapsed ? '48px' : '300px'}`, gap: 12, minHeight: 'calc(100vh - 110px)', transition: 'grid-template-columns 0.25s ease' }}>
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
                <span style={{ fontSize: 12, color: '#9ca7c8' }}>Fecha y hora de inicio</span>
                <input disabled={!canEditConfig} type="datetime-local" step={60} value={config.scenarioStartDate} onChange={(e) => patchConfig({ scenarioStartDate: e.target.value })} style={field} />
                <span style={{ fontSize: 11, color: '#6b7392' }}>Vacío = primer envío importado (fecha y hora).</span>
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

              <p style={{ margin: '4px 0 0', fontSize: 12, color: '#9ca7c8' }}>Umbrales del semáforo de KPIs de riesgo</p>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
                <label>
                  <span style={{ fontSize: 12, color: '#9ca7c8' }}>SLA verde ≥ %</span>
                  <input disabled={!canEditConfig} type="number" min={0} max={100} value={config.slaWarnPct} onChange={(e) => patchConfig({ slaWarnPct: Number(e.target.value) })} style={field} />
                </label>
                <label>
                  <span style={{ fontSize: 12, color: '#9ca7c8' }}>SLA ámbar ≥ %</span>
                  <input disabled={!canEditConfig} type="number" min={0} max={100} value={config.slaCritPct} onChange={(e) => patchConfig({ slaCritPct: Number(e.target.value) })} style={field} />
                </label>
                <label>
                  <span style={{ fontSize: 12, color: '#9ca7c8' }}>Riesgo ámbar ≤ % activos</span>
                  <input disabled={!canEditConfig} type="number" min={0} max={100} value={config.riskShipmentsWarnPct} onChange={(e) => patchConfig({ riskShipmentsWarnPct: Number(e.target.value) })} style={field} />
                </label>
                <label>
                  <span style={{ fontSize: 12, color: '#9ca7c8' }}>Riesgo rojo &gt; % activos</span>
                  <input disabled={!canEditConfig} type="number" min={0} max={100} value={config.riskShipmentsCritPct} onChange={(e) => patchConfig({ riskShipmentsCritPct: Number(e.target.value) })} style={field} />
                </label>
                <label>
                  <span style={{ fontSize: 12, color: '#9ca7c8' }}>Nodos ámbar ≤ % nodos</span>
                  <input disabled={!canEditConfig} type="number" min={0} max={100} value={config.criticalNodesWarnPct} onChange={(e) => patchConfig({ criticalNodesWarnPct: Number(e.target.value) })} style={field} />
                </label>
                <label>
                  <span style={{ fontSize: 12, color: '#9ca7c8' }}>Nodos rojo &gt; % nodos</span>
                  <input disabled={!canEditConfig} type="number" min={0} max={100} value={config.criticalNodesCritPct} onChange={(e) => patchConfig({ criticalNodesCritPct: Number(e.target.value) })} style={field} />
                </label>
              </div>

              <button className="btn btn-primary" disabled={!canEditConfig || savingConfig} onClick={() => void saveScenarioConfig()}>
                {savingConfig ? 'Guardando...' : 'Guardar configuración'}
              </button>

              {sim?.dateAdjusted && sim?.dateAdjustmentReason ? (
                <p style={{ margin: 0, fontSize: 11, color: '#fbbf24' }}>{sim.dateAdjustmentReason}</p>
              ) : null}
            </div>
              </>
            )}
          </aside>

          <SimulationMapPanel
            airports={airports}
            flights={mapLiveFlights}
            renderedFlights={renderedFlights}
            active={simulacionActiva}
            mapMode={mapMode}
            onMapModeChange={setMapMode}
            selectedShipment={selectedShipment}
            selectedFlight={selectedFlight}
            selectedFlightId={selectedFlightId}
            selectedNode={selectedNode}
            onSelectedFlightIdChange={setSelectedFlightId}
            onSelectedFlightVisualChange={setSelectedFlightVisual}
            onSelectedNodeChange={setSelectedNode}
            onSelectedShipmentChange={setSelectedShipmentId}
            onOpenNode={openNode}
            bootstrapping={bootstrapping}
            bootstrapPlannedShipments={sim?.bootstrapPlannedShipments ?? 0}
            bootstrapTotalShipments={sim?.bootstrapTotalShipments ?? 0}
            bootstrapMessage={sim?.bootstrapMessage}
          />

          <aside style={{ display: 'flex', flexDirection: 'column', gap: 10, minHeight: 0 }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: kpisCollapsed ? 'center' : 'space-between', minHeight: 28 }}>
              {!kpisCollapsed && <span style={{ fontSize: 12, fontWeight: 700, color: '#9ca7c8' }}>Indicadores</span>}
              <button
                onClick={() => {
                  const next = !kpisCollapsed;
                  setKpisCollapsed(next);
                  localStorage.setItem('pdds-kpis-collapsed', String(next));
                }}
                title={kpisCollapsed ? 'Expandir indicadores' : 'Colapsar indicadores'}
                style={{ background: '#171a29', border: '1px solid #32364f', borderRadius: 6, color: '#9ca7c8', cursor: 'pointer', padding: '4px 8px', fontSize: 14 }}
              >
                {kpisCollapsed ? '◀' : '▶'}
              </button>
            </div>
            {!kpisCollapsed && (
            <>
            {sim?.collapseDetectedAt ? (
              <article className="surface-panel" style={{ padding: 14, border: '1px solid #ef4444', background: '#2a1414' }}>
                <p style={{ margin: 0, fontSize: 12, color: '#fca5a5', fontWeight: 700, textTransform: 'uppercase', letterSpacing: 0.4 }}>Colapso detectado</p>
                <strong style={{ marginTop: 6, display: 'block', fontSize: 18, color: '#fee2e2' }}>
                  {formatSimTime(new Date(sim.collapseDetectedAt))}
                </strong>
                <p style={{ margin: '6px 0 0', color: '#fecaca', fontSize: 12 }}>
                  Supervivencia: {sim.collapseSurvivalSeconds != null ? formatDuration(sim.collapseSurvivalSeconds) : '-'}
                  {sim.collapseShipmentCode ? ` · Envío ${sim.collapseShipmentCode}` : ''}
                </p>
                <p style={{ margin: '4px 0 0', color: '#f8b4b4', fontSize: 11 }}>Primer envío que no llegó a tiempo. Simulación detenida.</p>
              </article>
            ) : null}
            <div style={{ display: 'flex', flexDirection: 'column', gap: 10, flex: 1, minHeight: 0 }}>
            <KpiCard title="Vuelos visibles" value={String(simulacionActiva ? mapLiveFlights.length : 0)} status="neutral" />
            <KpiCard title="Próximos vuelos" value={String(simulacionActiva ? (overview?.nextScheduledFlights ?? 0) : 0)} status="neutral" />
            <KpiCard title="Envíos operativos" value={String(simulacionActiva ? (overview?.shipmentsInRoute ?? 0) : 0)} status="neutral" />
            <KpiCard title="SLA actual" value={fmtPct(simulacionActiva ? (overview?.slaCompliancePct ?? 100) : 100)} status={semaforoSla(simulacionActiva ? (overview?.slaCompliancePct ?? 100) : 100, sim?.slaWarnPct ?? 90, sim?.slaCritPct ?? 75)} />
            <KpiCard title="Nodos críticos" value={String(system?.criticoAirports ?? 0)} status={semaforoMax(((system?.criticoAirports ?? 0) / Math.max(1, system?.totalAirports ?? 0)) * 100, sim?.criticalNodesWarnPct ?? 10, sim?.criticalNodesCritPct ?? 25)} />
            <KpiCard title="Envíos en riesgo" value={String(simulacionActiva ? (overview?.atRiskShipments ?? 0) : 0)} status={semaforoMax(simulacionActiva ? ((overview?.atRiskShipments ?? 0) / Math.max(1, (overview?.activeIntraShipments ?? 0) + (overview?.activeInterShipments ?? 0))) * 100 : 0, sim?.riskShipmentsWarnPct ?? 10, sim?.riskShipmentsCritPct ?? 25)} />
            </div>
            <article className="surface-panel" style={{ padding: 14 }}>
              <p style={{ margin: 0, fontSize: 12, color: '#a9b3cf', display: 'flex', alignItems: 'center', gap: 8 }}>
                <span style={{ width: 8, height: 8, borderRadius: 99, background: simulacionActiva ? '#22c55e' : '#64748b' }} />
                Reloj simulado
              </p>
              <strong style={{ marginTop: 4, display: 'block', fontSize: 18, color: '#eff3ff' }}>
                {!stateLoaded ? 'Cargando...' : displayedSimTime ?? '-'}
              </strong>
              {stateLoaded && sim ? <p style={{ margin: '6px 0 0', color: '#9ca3bf', fontSize: 12 }}>{formatTimeScaleLabel(sim.simulationSecondsPerTick, sim.tickIntervalMs)}</p> : null}
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
            </>
            )}
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
