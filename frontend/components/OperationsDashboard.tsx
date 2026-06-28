'use client';

import { Component, memo, useCallback, useEffect, useMemo, useRef, useState, type CSSProperties, type ErrorInfo, type ReactNode } from 'react';
import Link from 'next/link';
import Map, { Marker, NavigationControl, Popup, type MapLayerMouseEvent, type MapRef } from 'react-map-gl/maplibre';
import type { StyleSpecification } from 'maplibre-gl';
import { dashboardApi } from '@/lib/api/dashboardApi';
import { alertsApi } from '@/lib/api/alertsApi';
import { simulationApi } from '@/lib/api/simulationApi';
import { shipmentsApi } from '@/lib/api/shipmentsApi';
import { useSimulation } from '@/lib/SimulationContext';
import type { Airport, AirportStatus, MapLiveFlight, MapLiveShipment, NodeDetail, OperationalAlert, ShipmentUpcoming, SimScenario, SimulationResults } from '@/lib/types';
import { FlightTrajectoryLayer } from '@/components/FlightTrajectoryLayer';
import { ShipmentRouteLayer } from '@/components/ShipmentRouteLayer';
import { AirportPanel } from '@/components/AirportPanel';

const MAP_STYLE: StyleSpecification = {
  version: 8,
  sources: {
    carto: {
      type: 'raster',
      tiles: ['https://a.basemaps.cartocdn.com/light_all/{z}/{x}/{y}.png'],
      tileSize: 256,
      attribution: '© CARTO',
    },
  },
  layers: [{ id: 'carto-light', type: 'raster', source: 'carto' }],
};
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
const MIN_SIM_SPEED = 1;
const MAX_SIM_SPEED = 20;
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

class MapErrorBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };

  static getDerivedStateFromError() {
    return { failed: true };
  }

  componentDidCatch(error: unknown, info: ErrorInfo) {
    console.error('Error renderizando el mapa operacional', error, info.componentStack);
  }

  render() {
    if (!this.state.failed) {
      return this.props.children;
    }
    return (
      <div style={{
        height: '100%',
        minHeight: 420,
        display: 'grid',
        placeItems: 'center',
        background: '#0f172a',
        color: '#e5e7eb',
        padding: 24,
        textAlign: 'center',
      }}>
        <div style={{ maxWidth: 420 }}>
          <h3 style={{ margin: '0 0 8px', fontSize: 18 }}>No se pudo renderizar el mapa</h3>
          <p style={{ margin: '0 0 16px', color: '#94a3b8', lineHeight: 1.5 }}>
            El navegador perdió el contexto WebGL. La simulación sigue corriendo; recarga el mapa para volver a visualizar vuelos y nodos.
          </p>
          <button className="btn btn-primary" type="button" onClick={() => this.setState({ failed: false })}>
            Recargar mapa
          </button>
        </div>
      </div>
    );
  }
}

function airportColor(status: AirportStatus): string {
  if (status === 'SIN_USO') return '#64748b';
  if (status === 'NORMAL') return '#22c55e';
  if (status === 'ALERTA') return '#f59e0b';
  return '#ef4444';
}

type RuntimeConfig = {
  scenario: SimScenario;
  simulationDays: 3 | 5 | 7;
  scenarioStartDate: string;
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

function mercatorY(latitude: number): number {
  const rad = (latitude * Math.PI) / 180;
  return Math.log(Math.tan(Math.PI / 4 + rad / 2));
}

function inverseMercatorY(y: number): number {
  return (Math.atan(Math.exp(y)) * 2 - Math.PI / 2) * 180 / Math.PI;
}

function interpolateFlightPositionMercator(from: { latitude: number; longitude: number }, to: { latitude: number; longitude: number }, ratio: number) {
  const safeRatio = clamp01(ratio);
  const fromLon = normalizeLongitude(from.longitude);
  const toLon = nearestWrappedLongitude(normalizeLongitude(to.longitude), fromLon);
  const fromY = mercatorY(from.latitude);
  const toY = mercatorY(to.latitude);

  return {
    latitude: inverseMercatorY(fromY + (toY - fromY) * safeRatio),
    longitude: normalizeLongitude(fromLon + (toLon - fromLon) * safeRatio),
  };
}

function computeFlightProgressAt(flight: MapLiveFlight, simMs: number | null): number {
  if (flight.status === 'CANCELLED') return 0;
  if (!simMs || !flight.scheduledDeparture || !flight.scheduledArrival) return 0;
  const departureMs = new Date(flight.scheduledDeparture).getTime();
  const arrivalMs = new Date(flight.scheduledArrival).getTime();
  if (!Number.isFinite(departureMs) || !Number.isFinite(arrivalMs) || arrivalMs <= departureMs) return 0;
  return clamp01((simMs - departureMs) / (arrivalMs - departureMs));
}

function computeVisualFlightProgressAt(flight: MapLiveFlight, simMs: number | null, visualStartMs?: number): number {
  if (flight.status === 'CANCELLED') return 0;
  if (!simMs || !flight.scheduledDeparture || !flight.scheduledArrival) return 0;
  const departureMs = new Date(flight.scheduledDeparture).getTime();
  const arrivalMs = new Date(flight.scheduledArrival).getTime();
  if (!Number.isFinite(departureMs) || !Number.isFinite(arrivalMs) || arrivalMs <= departureMs) return 0;
  const startMs = visualStartMs ?? departureMs;
  const durationMs = arrivalMs - departureMs;
  return clamp01((simMs - startMs) / durationMs);
}

function projectFlightAt(flight: MapLiveFlight, simMs: number | null, visualStartMs?: number): MapLiveFlight {
  const ratio = computeVisualFlightProgressAt(flight, simMs, visualStartMs);
  const position = interpolateFlightPositionMercator(
    { latitude: flight.originLatitude, longitude: flight.originLongitude },
    { latitude: flight.destinationLatitude, longitude: flight.destinationLongitude },
    ratio,
  );

  return {
    ...flight,
    currentLatitude: position.latitude,
    currentLongitude: position.longitude,
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
  const indeterminate = safeTotal === 0; // arranque: la semilla aún no reporta su conteo
  const safePlanned = Math.max(0, Math.min(planned, safeTotal || planned));
  const progress = safeTotal > 0 ? (safePlanned * 100) / safeTotal : 0;
  const stopping = Boolean(message && /deteniendo|limpiando|esperando|detener/i.test(message));

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
        <p style={{ margin: 0, color: '#c7d2fe', fontSize: 12, letterSpacing: 1.2, textTransform: 'uppercase' }}>{stopping ? 'Cierre operativo' : 'Preplanificación'}</p>
        <h3 style={{ margin: '8px 0 6px', color: '#eff3ff', fontSize: 24 }}>{stopping ? 'Deteniendo la simulación…' : indeterminate ? 'Preparando la simulación…' : 'Programando envíos antes de iniciar la simulación'}</h3>
        <p style={{ margin: 0, color: '#a9b3cf', fontSize: 14 }}>{message ?? (stopping ? 'Deteniendo el reloj y limpiando datos operativos.' : indeterminate ? 'Reiniciando los datos del período y construyendo la semilla inicial de rutas.' : 'Construyendo rutas iniciales del período para arrancar con operación consistente.')}</p>
        <div style={{ marginTop: 18, height: 12, borderRadius: 999, background: 'rgba(148, 163, 184, 0.18)', overflow: 'hidden' }}>
          <div style={{ width: indeterminate ? '45%' : `${progress.toFixed(1)}%`, height: '100%', background: 'linear-gradient(90deg, #60a5fa 0%, #818cf8 55%, #a78bfa 100%)', opacity: indeterminate ? 0.75 : 1, transition: 'width 0.3s ease' }} />
        </div>
        {!indeterminate && (
          <div style={{ marginTop: 12, display: 'flex', justifyContent: 'space-between', color: '#dbeafe', fontSize: 14 }}>
            <span>{safePlanned.toLocaleString('es-PE')} / {safeTotal.toLocaleString('es-PE')} envíos</span>
            <strong>{progress.toFixed(1)}%</strong>
          </div>
        )}
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

function PlaneIcon({ rotation, selected, loadPct }: { rotation: number; selected: boolean; loadPct: number }) {
  // Semáforo por carga del vuelo (C27): verde/ámbar/rojo según ocupación; blanco si está seleccionado.
  const fill = loadPct >= 90 ? '#ef4444' : loadPct >= 70 ? '#f59e0b' : '#22c55e';
  const stroke = selected ? '#ffffff' : '#08111f';

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
  shipments: MapLiveShipment[];
  renderedFlights: MapLiveFlight[];
  active: boolean;
  mapMode: MapMode;
  mapRef: React.MutableRefObject<MapRef | null>;
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
  selectedShipmentId: number | null;
  onSelectedShipmentChange: (shipmentId: number | null) => void;
  onOpenNode: (airport: Airport) => void;
  onMapClick?: () => void;
  bootstrapping: boolean;
  bootstrapPlannedShipments: number;
  bootstrapTotalShipments: number;
  bootstrapMessage: string | null | undefined;
  maximized: boolean;
  onToggleMaximized: () => void;
  liveOnly: boolean;
  simClock: string | null;
  waitingForPlanning: boolean;
  planningActive: boolean;
  planningBacklog: number;
  plannedThroughLabel: string | null;
};

const SimulationMapPanel = memo(function SimulationMapPanel({
  airports,
  flights,
  shipments,
  renderedFlights,
  active,
  mapMode,
  mapRef,
  onMapModeChange,
  selectedShipment,
  selectedShipmentId,
  selectedFlight,
  selectedFlightId,
  selectedNode,
  onSelectedFlightIdChange,
  onSelectedFlightVisualChange,
  onSelectedNodeChange,
  onSelectedShipmentChange,
  onOpenNode,
  onMapClick,
  bootstrapping,
  bootstrapPlannedShipments,
  bootstrapTotalShipments,
  bootstrapMessage,
  maximized,
  onToggleMaximized,
  liveOnly,
  simClock,
  waitingForPlanning,
  planningActive,
  planningBacklog,
  plannedThroughLabel,
}: SimulationMapPanelProps) {
  // Reloj del mapa (C12-C16): en vivo (Inicio) = hora real de Perú; simulación = reloj simulado.
  const [wallMs, setWallMs] = useState<number | null>(null);
  useEffect(() => {
    setWallMs(Date.now());
    const t = setInterval(() => setWallMs(Date.now()), 1000);
    return () => clearInterval(t);
  }, []);
  const peruClock = wallMs == null ? '--:--:--' : new Date(wallMs).toLocaleTimeString('es-PE', { timeZone: 'America/Lima', hour12: false });
  const peruDate = wallMs == null ? '—' : new Date(wallMs).toLocaleDateString('es-PE', { timeZone: 'America/Lima', weekday: 'short', day: '2-digit', month: 'short' });
  const mapContainerRef = useRef<HTMLDivElement | null>(null);

  const handleMapClick = useCallback((event: MapLayerMouseEvent) => {
    onSelectedShipmentChange(null);
    onSelectedFlightIdChange(null);
    onSelectedFlightVisualChange(null);
    onSelectedNodeChange(null);
    onMapClick?.();
  }, [onSelectedFlightIdChange, onSelectedFlightVisualChange, onSelectedNodeChange, onSelectedShipmentChange, onMapClick]);

  // Al maximizar/restaurar, el contenedor del mapa cambia de tamaño; maplibre necesita resize() para
  // redibujar (si no, el canvas queda en blanco). Se llama de inmediato y tras la transición del grid (0.25s).
  useEffect(() => {
    const ref = mapRef.current;
    if (!ref) return;
    const underlying = (ref as any).getMap ? (ref as any).getMap() : ref;
    if (underlying && typeof underlying.resize === 'function') underlying.resize();
    const t = setTimeout(() => { if (underlying && typeof underlying.resize === 'function') underlying.resize(); }, 280);
    return () => clearTimeout(t);
  }, [maximized]);

  // Resize observer: cuando el contenedor del mapa cambia de tamaño (por layout/transition),
  // invocar `resize()` en la instancia de Map para evitar canvas en blanco.
  useEffect(() => {
    const node = mapContainerRef.current;
    if (!node || typeof (window as any).ResizeObserver === 'undefined') return;
    const obs = new (window as any).ResizeObserver(() => {
      const ref = mapRef.current;
      if (!ref) return;
      const underlying = (ref as any).getMap ? (ref as any).getMap() : ref;
      try { if (underlying && typeof underlying.resize === 'function') underlying.resize(); } catch (e) { /* ignore */ }
    });
    obs.observe(node);
    return () => obs.disconnect();
  }, []);

  return (
    <section className="surface-panel" style={{ flex: 1, minHeight: 0, overflow: 'hidden', position: 'relative' }}>
      <div style={{ position: 'absolute', top: 12, left: 56, zIndex: 5, display: 'flex', gap: 8 }}>
        <button className={`chip${mapMode === 'MAPA' ? ' is-active' : ''}`} onClick={() => onMapModeChange('MAPA')}>Mapa</button>
        <button className={`chip${mapMode === 'SATELITAL' ? ' is-active' : ''}`} onClick={() => onMapModeChange('SATELITAL')}>Satelital</button>
      </div>
      <div style={{ position: 'absolute', top: 12, right: 12, zIndex: 5 }}>
        <button className={`chip${maximized ? ' is-active' : ''}`} onClick={onToggleMaximized} title={maximized ? 'Restaurar paneles' : 'Maximizar mapa'}>
          {maximized ? '⤡ Restaurar' : '⤢ Maximizar'}
        </button>
      </div>

      <div style={{ position: 'absolute', top: 10, left: '50%', transform: 'translateX(-50%)', zIndex: 5, padding: '5px 16px', borderRadius: 12, background: 'rgba(13,16,30,0.84)', border: `1px solid ${liveOnly ? 'rgba(67,210,157,0.42)' : 'rgba(95,130,255,0.42)'}`, backdropFilter: 'blur(6px)', textAlign: 'center', minWidth: 190 }}>
        <p style={{ margin: 0, fontSize: 10, fontWeight: 700, letterSpacing: '0.05em', color: liveOnly ? '#43d29d' : '#7a99ff' }}>
          {liveOnly ? '● EN VIVO · PERÚ (GMT-5)' : '◷ RELOJ SIMULADO'}
        </p>
        <strong style={{ display: 'block', fontSize: 19, fontWeight: 700, color: '#eaf0ff', fontVariantNumeric: 'tabular-nums', lineHeight: 1.25 }}>
          {liveOnly ? peruClock : (simClock ?? '—')}
        </strong>
        <p style={{ margin: 0, fontSize: 10, color: '#7c879f', textTransform: 'capitalize' }}>
          {liveOnly ? peruDate : `Real · ${peruClock}`}
        </p>
      </div>

      <div style={{ position: 'absolute', inset: 0, zIndex: 1, background: 'rgba(8, 10, 20, 0.24)', pointerEvents: 'none' }} />

      <div ref={mapContainerRef} style={{ position: 'absolute', inset: 0, zIndex: 1 }}>
        <Map
          ref={mapRef}
          mapStyle={mapMode === 'MAPA' ? MAP_STYLE : SATELLITE_STYLE}
          initialViewState={INITIAL_VIEW}
          style={{ width: '100%', height: '100%' }}
          renderWorldCopies={false}
          onClick={handleMapClick}
        >
          <NavigationControl position="top-left" />

        <FlightTrajectoryLayer
          selectedFlight={selectedFlight}
          selectedAirportIcao={selectedNode?.detail.icaoCode ?? null}
          flights={renderedFlights}
        />

        <ShipmentRouteLayer shipmentId={selectedShipmentId} />

        {airports.map((a) => {
          const markerColor = airportColor(a.status);
          return (
          <Marker key={a.id} longitude={a.longitude} latitude={a.latitude} anchor="bottom" onClick={(e) => { e.originalEvent.stopPropagation(); onOpenNode(a); }}>
            <button
              title={`${a.icaoCode} ${a.occupancyPct.toFixed(1)}%`}
              type="button"
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 3,
                padding: '2px 5px',
                borderRadius: 4,
                border: `1px solid ${markerColor}`,
                background: 'rgba(8,17,31,0.85)',
                color: markerColor,
                cursor: 'pointer',
                fontSize: 10,
                fontWeight: 700,
                fontFamily: 'monospace',
                lineHeight: 1,
                whiteSpace: 'nowrap',
              }}
            >
              <span style={{
                width: 6,
                height: 6,
                borderRadius: 99,
                background: markerColor,
                flexShrink: 0,
              }} />
              {a.icaoCode}
            </button>
          </Marker>
          );
        })}

        {active && renderedFlights.filter((row) => row.status !== 'CANCELLED').map((row) => {
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
                <PlaneIcon rotation={rotation} selected={selectedFlightId === row.flightId} loadPct={row.loadPct} />
              </button>
            </Marker>
          );
        })}

        {active && renderedFlights.filter((row) => row.status === 'CANCELLED').map((row) => (
          <Marker
            key={`cancelled-${row.flightId}`}
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
              title={`${row.flightCode} · Cancelado`}
              type="button"
              aria-label={`Vuelo cancelado ${row.flightCode}`}
              style={{
                width: 24,
                height: 24,
                borderRadius: 4,
                border: '1.5px solid #6b7280',
                background: 'rgba(107,114,128,0.35)',
                cursor: 'pointer',
                padding: 0,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                fontSize: 13,
                fontWeight: 700,
                color: '#6b7280',
                lineHeight: 1,
                opacity: 0.5,
              }}
            >
              ✕
            </button>
          </Marker>
        ))}

        {active && renderedFlights.filter((row) => row.status === 'CANCELLED').length > 0 && selectedFlight && selectedFlight.status === 'CANCELLED' ? (
          <Popup longitude={selectedFlight.currentLongitude} latitude={selectedFlight.currentLatitude} closeButton={false} closeOnClick={false} anchor="top">
            <div style={{ minWidth: 180 }}>
              <p style={{ margin: 0, fontWeight: 700, color: '#ef4444' }}>{selectedFlight.flightCode}</p>
              <p style={{ margin: '4px 0 0', fontSize: 12 }}>{selectedFlight.originIcao} {'->'} {selectedFlight.destinationIcao}</p>
              <p style={{ margin: '4px 0 0', fontSize: 12, color: '#ef4444', fontWeight: 600 }}>Cancelado</p>
              <p style={{ margin: '4px 0 0', fontSize: 12 }}><Link href={`/flights?selected=${selectedFlight.flightId}&status=CANCELLED`} style={{ color: '#2563eb' }}>Abrir vuelo</Link></p>
            </div>
          </Popup>
        ) : null}

        {active && selectedShipment ? (
          <Marker
            key={`ship-${selectedShipment.shipmentId}`}
            longitude={selectedShipment.currentLongitude}
            latitude={selectedShipment.currentLatitude}
            anchor="center"
          >
            <span
              title={`${selectedShipment.shipmentCode} · ${selectedShipment.originIcao}→${selectedShipment.destinationIcao} (${(selectedShipment.progressPct).toFixed(0)}%)`}
              style={{
                width: 16,
                height: 16,
                borderRadius: '3px 7px 3px 7px',
                border: '2px solid #e2e8f0',
                background: 'linear-gradient(135deg, #5eead4 0%, #14b8a6 100%)',
                boxShadow: '0 0 12px rgba(45,212,191,0.7)',
                display: 'block',
                transform: 'rotate(45deg)',
              }}
            />
          </Marker>
        ) : null}

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

        {active && selectedFlight && selectedFlight.status !== 'CANCELLED' ? (
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
              <p style={{ margin: '4px 0 0', fontSize: 12 }}>Vuelos en camino: {renderedFlights.filter((f) => f.destinationIcao === selectedNode.detail.icaoCode).length}</p>
            </div>
          </Popup>
        ) : null}
        </Map>
        </div>
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

export function OperationsDashboard({ liveOnly = false }: { liveOnly?: boolean }) {
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
    simulatedClockWaitingForPlanning,
    simulatedClockPlannedThroughMs,
  } = useSimulation();

  const [mapMode, setMapMode] = useState<MapMode>('MAPA');
  const [selectedShipmentId, setSelectedShipmentId] = useState<number | null>(null);
  const [selectedFlightId, setSelectedFlightId] = useState<number | null>(null);
  const [selectedNode, setSelectedNode] = useState<{ point: { longitude: number; latitude: number }; detail: NodeDetail } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [savingConfig, setSavingConfig] = useState(false);
  const [starting, setStarting] = useState(false);
  const [stopping, setStopping] = useState(false);
  const [draftDirty, setDraftDirty] = useState(false);
  const [alerts, setAlerts] = useState<OperationalAlert[]>([]);
  const mapRef = useRef<MapRef | null>(null);
  const [focusedShipmentId, setFocusedShipmentId] = useState<number | null>(null);
  const [focusedShipmentCode, setFocusedShipmentCode] = useState<string | null>(null);
  const [focusedFlightId, setFocusedFlightId] = useState<number | null>(null);
  const [focusedFlightCode, setFocusedFlightCode] = useState<string | null>(null);
  const [selectedAirportIcao, setSelectedAirportIcao] = useState<string | null>(null);
  const mapMaximized = true;
  const [selectedFlightVisual, setSelectedFlightVisual] = useState<MapLiveFlight | null>(null);
  const [airportStatusFilter, setAirportStatusFilter] = useState<'ALL' | AirportStatus>('ALL');
  const [flightLoadFilter, setFlightLoadFilter] = useState<'ALL' | 'EMPTY' | 'LOW' | 'MEDIUM' | 'HIGH'>('ALL');
  const [renderedFlights, setRenderedFlights] = useState<MapLiveFlight[]>([]);
  const renderedFlightsRef = useRef<MapLiveFlight[]>([]);
  const flightVisualStartRef = useRef<globalThis.Map<number, number>>(new globalThis.Map());
  const flightVisualCacheRef = useRef<globalThis.Map<number, MapLiveFlight>>(new globalThis.Map());
  const flightAnimationFrameRef = useRef<number | null>(null);
  const simulatedNowMsRef = useRef<number | null>(simulatedNowMs);
  const flightLocalSimMsRef = useRef<number | null>(simulatedNowMs);
  const simRunningRef = useRef(false);
  const simPausedRef = useRef(false);
  const simSpeedRef = useRef({ simulationSecondsPerTick: 1, tickIntervalMs: 1_000 });
  const speedCommitTimerRef = useRef<number | null>(null);
  const [localSpeed, setLocalSpeed] = useState<number | null>(null);

  // Derived clock display from the globally-extrapolated simulated time
  const displayedSimTime = useMemo(() => {
    if (!simulatedNowMs || !sim?.simulatedNow) return null;
    return formatSimTime(new Date(simulatedNowMs));
  }, [simulatedNowMs, sim?.simulatedNow]);

  const plannedThroughLabel = useMemo(() => {
    if (!simulatedClockPlannedThroughMs) return null;
    return formatSimTime(new Date(simulatedClockPlannedThroughMs));
  }, [simulatedClockPlannedThroughMs]);

  const planningBacklog = Math.max(0, sim?.periodPlanningBacklog ?? 0);
  const planAheadActive = Boolean(
    !liveOnly
    && sim?.running
    && (sim.scenario === 'PERIOD_SIMULATION' || sim.scenario === 'COLLAPSE_TEST')
    && (sim.periodPlanningActive || planningBacklog > 0),
  );
  const planningLeadMs = useMemo(() => {
    if (!simulatedNowMs || !simulatedClockPlannedThroughMs) return Number.POSITIVE_INFINITY;
    return simulatedClockPlannedThroughMs - simulatedNowMs;
  }, [simulatedNowMs, simulatedClockPlannedThroughMs]);
  const oneTickMs = Math.max(1, sim?.simulationSecondsPerTick ?? 1) * 1000;
  const recentlyWaitedForPlanning = Boolean(
    sim?.periodTickLastWaitAt
    && sim?.periodTickLastWaitPlannedThrough
    && sim?.periodTickLastWaitHorizon
    && sim.periodTickLastWaitPlannedThrough < sim.periodTickLastWaitHorizon
    && planningBacklog > 0,
  );
  const showWaitingForPlanning = simulatedClockWaitingForPlanning
    || (recentlyWaitedForPlanning && planningLeadMs < oneTickMs);

  useEffect(() => {
    simulatedNowMsRef.current = simulatedNowMs;
  }, [simulatedNowMs]);

  useEffect(() => {
    simRunningRef.current = Boolean(sim?.running);
    simPausedRef.current = Boolean(sim?.paused);
    const effectiveSpeed = localSpeed ?? sim?.speed ?? 1;
    simSpeedRef.current = {
      simulationSecondsPerTick: Math.max(1, effectiveSpeed * 60),
      tickIntervalMs: Math.max(1, sim?.tickIntervalMs ?? 1_000),
    };
  }, [sim?.running, sim?.paused, sim?.speed, sim?.tickIntervalMs, localSpeed]);

  useEffect(() => {
    if (localSpeed == null || sim?.speed == null || sim.speed === localSpeed) return;
    const t = setTimeout(() => setLocalSpeed(null), 3_000);
    return () => clearTimeout(t);
  }, [localSpeed, sim?.speed]);

  const operationalAirports = useMemo(() => {
    const loadByOrigin = new globalThis.Map<string, number>();
    for (const shipment of mapLive) {
      if (shipment.currentFlightCode) continue;
      loadByOrigin.set(shipment.originIcao, (loadByOrigin.get(shipment.originIcao) ?? 0) + 1);
    }
    const activeFlightsByAirport = new globalThis.Map<string, number>();
    for (const flight of mapLiveFlights) {
      activeFlightsByAirport.set(flight.originIcao, (activeFlightsByAirport.get(flight.originIcao) ?? 0) + 1);
      activeFlightsByAirport.set(flight.destinationIcao, (activeFlightsByAirport.get(flight.destinationIcao) ?? 0) + 1);
    }
    return airports.map((airport) => {
      const liveLoad = loadByOrigin.get(airport.icaoCode) ?? 0;
      const activeFlights = activeFlightsByAirport.get(airport.icaoCode) ?? 0;
      if (liveLoad <= 0 && activeFlights <= 0 && (airport.currentStorageLoad ?? 0) <= 0 && (airport.occupancyPct ?? 0) <= 0) {
        return {
          ...airport,
          currentStorageLoad: 0,
          availableCapacity: airport.maxStorageCapacity,
          occupancyPct: 0,
          status: 'SIN_USO' as AirportStatus,
        };
      }
      const backendOccupancy = Math.max(0, airport.occupancyPct ?? 0);
      const liveOccupancy = airport.maxStorageCapacity > 0 ? (liveLoad * 100) / airport.maxStorageCapacity : 0;
      const occupancyPct = Math.max(backendOccupancy, liveOccupancy);
      const status: AirportStatus = occupancyPct >= 90 ? 'CRITICO' : occupancyPct >= 70 ? 'ALERTA' : 'NORMAL';
      return {
        ...airport,
        currentStorageLoad: Math.max(liveLoad, airport.currentStorageLoad ?? 0),
        availableCapacity: Math.max(0, airport.maxStorageCapacity - Math.max(liveLoad, airport.currentStorageLoad ?? 0)),
        occupancyPct,
        status,
      };
    });
  }, [airports, mapLive, mapLiveFlights]);

  const filteredAirports = useMemo(() => {
    if (airportStatusFilter === 'ALL') return operationalAirports;
    return operationalAirports.filter((a) => a.status === airportStatusFilter);
  }, [operationalAirports, airportStatusFilter]);

  const filteredMapLiveFlights = useMemo(() => {
    if (flightLoadFilter === 'ALL') return mapLiveFlights;
    return mapLiveFlights.filter((f) => {
      if (flightLoadFilter === 'EMPTY') return f.loadPct === 0;
      if (flightLoadFilter === 'LOW') return f.loadPct > 0 && f.loadPct < 70;
      if (flightLoadFilter === 'MEDIUM') return f.loadPct >= 70 && f.loadPct <= 90;
      if (flightLoadFilter === 'HIGH') return f.loadPct > 90;
      return true;
    });
  }, [mapLiveFlights, flightLoadFilter]);

  const [config, setConfig] = useState<RuntimeConfig>({
    scenario: 'PERIOD_SIMULATION',
    simulationDays: 5,
    scenarioStartDate: '',
  });

  const bootstrapping = Boolean(sim?.bootstrapping);
  const backendStopping = Boolean(sim?.bootstrapMessage && /deteniendo|limpiando|esperando|detener/i.test(sim.bootstrapMessage));
  const controlBusy = bootstrapping || backendStopping || starting || stopping;
  const stoppingVisible = stopping || backendStopping;
  const simulacionActiva = Boolean(sim?.running || sim?.paused);
  const canResume = Boolean(sim?.running && sim?.paused);
  const canEditConfig = !simulacionActiva && !controlBusy;
  const selectedSpeed = Math.max(MIN_SIM_SPEED, Math.min(MAX_SIM_SPEED, localSpeed ?? sim?.speed ?? MIN_SIM_SPEED));
  const [showClosureReport, setShowClosureReport] = useState(false);
  const [closureResults, setClosureResults] = useState<SimulationResults | null>(null);
  const [loadingClosure, setLoadingClosure] = useState(false);
  const prevRunningRef = useRef(false);
  const startedByUserRef = useRef(false);

  // Detecta detención/colapso real de la simulación → muestra el reporte de cierre.
  // Solo se activa si el usuario INICIÓ la simulación en esta sesión (startedByUserRef).
  // Esto evita que el reporte aparezca al Guardar configuración cuando el backend
  // tiene startedAt de una sesión anterior.
  useEffect(() => {
    if (!startedByUserRef.current) return;
    const wasRunning = prevRunningRef.current;
    const nowRunning = sim?.running ?? false;
    prevRunningRef.current = nowRunning;
    if (sim?.bootstrapping) return;
    if (!wasRunning || nowRunning) return;
    if (!sim?.startedAt) return;
    // Sim ended → limpiar selecciones de la corrida anterior
    setSelectedShipmentId(null);
    setSelectedFlightId(null);
    setSelectedFlightVisual(null);
    setSelectedNode(null);
    setShowClosureReport(true);
  }, [sim?.running, sim?.startedAt, sim?.collapseDetectedAt, sim?.bootstrapping]);

  // Si la pestaÃ±a se recarga o se abre despuÃ©s del final, el cierre debe seguir siendo visible.
  // La evaluaciÃ³n suele alternar navegadores; el reporte no puede depender solo de memoria de React.
  useEffect(() => {
    if (!sim || sim.running || sim.bootstrapping || showClosureReport) return;
    const endedPeriod = sim.scenario === 'PERIOD_SIMULATION'
      && Boolean(sim.startedAt)
      && Boolean(sim.simulatedNow)
      && Boolean(sim.periodEndAt)
      && new Date(sim.simulatedNow!).getTime() >= new Date(sim.periodEndAt!).getTime();
    const endedCollapse = sim.scenario === 'COLLAPSE_TEST'
      && Boolean(sim.startedAt)
      && (
        Boolean(sim.collapseDetectedAt)
        || (Boolean(sim.simulatedNow)
          && Boolean(sim.periodEndAt)
          && new Date(sim.simulatedNow!).getTime() >= new Date(sim.periodEndAt!).getTime())
      );
    if (endedPeriod || endedCollapse) {
      setShowClosureReport(true);
    }
  }, [sim, showClosureReport]);

  // Dismiss when a new sim starts or config resets
  useEffect(() => {
    if (sim?.running || sim?.bootstrapping) {
      setShowClosureReport(false);
      setClosureResults(null);
    }
  }, [sim?.running, sim?.bootstrapping]);

  // Fetch results when report is visible
  useEffect(() => {
    if (!showClosureReport) return;
    setLoadingClosure(true);
    simulationApi.getResults()
      .then(setClosureResults)
      .catch(() => setClosureResults(null))
      .finally(() => setLoadingClosure(false));
  }, [showClosureReport]);

  const hydrateFromState = useCallback((state: typeof sim) => {
    if (!state) return;
    const effectiveStart = state.scenarioStartAt
      || (state.projectedFrom ? `${state.projectedFrom}T00:00` : '');
    setConfig({
      scenario: state.scenario,
      simulationDays: state.simulationDays === 3 || state.simulationDays === 7 ? state.simulationDays : 5,
      scenarioStartDate: toDateTimeInput(effectiveStart),
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
      flightLocalSimMsRef.current = null;
      flightVisualStartRef.current.clear();
      flightVisualCacheRef.current.clear();
      setSelectedShipmentId(null);
      setSelectedFlightId(null);
      setSelectedFlightVisual(null);
    }
  }, [simulacionActiva]);

  // Read ?shipment=X or ?flight=X from URL to focus on the map
  useEffect(() => {
    const sp = typeof window !== 'undefined' ? new URLSearchParams(window.location.search) : null;
    const shipmentId = sp?.get('shipment');
    if (shipmentId) {
      setFocusedShipmentId(Number(shipmentId));
    }
    const flightId = sp?.get('flight');
    if (flightId) {
      setFocusedFlightId(Number(flightId));
    }
  }, []);

  // When focusedShipmentId is set, find the shipment in mapLive and flyTo / highlight it
  useEffect(() => {
    if (focusedShipmentId == null) return;
    const shipment = mapLive.find((s) => s.shipmentId === focusedShipmentId);
    if (!shipment || !mapRef.current) return;
    setSelectedShipmentId(focusedShipmentId);
    setFocusedShipmentCode(shipment.shipmentCode);
    mapRef.current.flyTo({
      center: [shipment.currentLongitude, shipment.currentLatitude],
      zoom: 5,
    });
    // Clear the focus so it only triggers once per URL param
    setFocusedShipmentId(null);
  }, [focusedShipmentId, mapLive]);

  // When focusedFlightId is set, find the flight in mapLiveFlights and flyTo / highlight it
  useEffect(() => {
    if (focusedFlightId == null) return;
    const flight = mapLiveFlights.find((f) => f.flightId === focusedFlightId);
    if (!flight || !mapRef.current) return;
    setSelectedFlightId(focusedFlightId);
    setFocusedFlightCode(flight.flightCode);
    mapRef.current.flyTo({
      center: [flight.currentLongitude, flight.currentLatitude],
      zoom: 5,
    });
    // Clear the focus so it only triggers once per URL param
    setFocusedFlightId(null);
  }, [focusedFlightId, mapLiveFlights]);

  // Auto-clear the focused indicator after 6 seconds
  useEffect(() => {
    if (!focusedShipmentCode) return;
    const t = setTimeout(() => setFocusedShipmentCode(null), 6000);
    return () => clearTimeout(t);
  }, [focusedShipmentCode]);

  useEffect(() => {
    if (!focusedFlightCode) return;
    const t = setTimeout(() => setFocusedFlightCode(null), 6000);
    return () => clearTimeout(t);
  }, [focusedFlightCode]);

  useEffect(() => {
    renderedFlightsRef.current = renderedFlights;
  }, [renderedFlights]);

  useEffect(() => {
    const visualStarts = flightVisualStartRef.current;
    const visualCache = flightVisualCacheRef.current;
    const currentSimMs = flightLocalSimMsRef.current ?? simulatedNowMsRef.current ?? Date.now();
    for (const flight of filteredMapLiveFlights) {
      visualCache.set(flight.flightId, flight);
      if (!visualStarts.has(flight.flightId)) {
        visualStarts.set(flight.flightId, currentSimMs);
      }
    }
  }, [filteredMapLiveFlights]);

  useEffect(() => {
    if (flightAnimationFrameRef.current) {
      cancelAnimationFrame(flightAnimationFrameRef.current);
      flightAnimationFrameRef.current = null;
    }

    if (!simulacionActiva) {
      setRenderedFlights([]);
      renderedFlightsRef.current = [];
      return;
    }

    let lastFrameAt = performance.now();
    let localSimMs = flightLocalSimMsRef.current ?? simulatedNowMsRef.current ?? Date.now();

    const animate = (now: number) => {
      const authoritativeSimMs = simulatedNowMsRef.current;
      if (authoritativeSimMs != null) {
        const driftMs = authoritativeSimMs - localSimMs;
        if (driftMs > 0) {
          localSimMs += Math.min(driftMs, Math.max(1_000, driftMs * 0.18));
        }
      }
      const speed = simSpeedRef.current;
      const factor = simRunningRef.current && !simPausedRef.current
        ? speed.simulationSecondsPerTick * (1_000 / speed.tickIntervalMs)
        : 0;
      if (!showWaitingForPlanning) {
        localSimMs += Math.max(0, now - lastFrameAt) * factor;
      }
      flightLocalSimMsRef.current = localSimMs;
      lastFrameAt = now;
      const visualCache = flightVisualCacheRef.current;
      const nextFlights = Array.from(visualCache.values())
        .map((flight) => projectFlightAt(flight, localSimMs, flightVisualStartRef.current.get(flight.flightId)))
        .filter((flight) => flight.status === 'CANCELLED'
          || computeVisualFlightProgressAt(flight, localSimMs, flightVisualStartRef.current.get(flight.flightId)) < 1);
      const activeVisualIds = new Set(nextFlights.map((flight) => flight.flightId));
      for (const flightId of Array.from(visualCache.keys())) {
        if (!activeVisualIds.has(flightId)) {
          visualCache.delete(flightId);
          flightVisualStartRef.current.delete(flightId);
        }
      }

      setRenderedFlights(nextFlights);
      renderedFlightsRef.current = nextFlights;

      if (simulacionActiva) {
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
  }, [filteredMapLiveFlights, simulacionActiva, showWaitingForPlanning]);

  useEffect(() => {
    if (selectedFlightId == null) {
      setSelectedFlightVisual(null);
    }
  }, [selectedFlightId]);

  const flightSnapshotById = useMemo(
    () => new globalThis.Map(renderedFlights.map((flight) => [flight.flightId, flight] as const)),
    [renderedFlights],
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
      };
      const updated = await simulationApi.configure(body);
      setSim(updated);
      hydrateFromState(updated);
      setDraftDirty(false);
      setShowClosureReport(false);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'No se pudo guardar la configuración.');
    } finally {
      setSavingConfig(false);
    }
  }

  async function onStart(): Promise<void> {
    setStarting(true);
    try {
      if (draftDirty) await saveScenarioConfig();
      const res = await simulationApi.start();
      setSim(res.state);
      hydrateFromState(res.state);
      setDraftDirty(false);
      setShowClosureReport(false);
      startedByUserRef.current = true;
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'No se pudo iniciar la simulación.');
    } finally {
      setStarting(false);
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
    setStopping(true);
    try {
      const res = await simulationApi.stop();
      setSim(res.state);
      setSelectedShipmentId(null);
      setSelectedFlightId(null);
      setSelectedFlightVisual(null);
      setSelectedNode(null);
      setSelectedAirportIcao(null);
      setShowClosureReport(true);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'No se pudo detener la simulación.');
    } finally {
      setStopping(false);
    }
  }

  function onSetSpeed(next: number): void {
    const clamped = Math.max(MIN_SIM_SPEED, Math.min(MAX_SIM_SPEED, Math.round(next)));
    setLocalSpeed(clamped);
    if (sim) setSim({ ...sim, speed: clamped, simulationSecondsPerTick: clamped * 60 }); // optimista: reloj y mapa cambian al instante
    if (speedCommitTimerRef.current != null) {
      window.clearTimeout(speedCommitTimerRef.current);
    }
    speedCommitTimerRef.current = window.setTimeout(() => {
      speedCommitTimerRef.current = null;
      simulationApi.setSpeed(clamped)
        .then((res) => {
          setSim(res.state);
          setLocalSpeed(null);
          setError(null);
        })
        .catch((e) => {
          setError(e instanceof Error ? e.message : 'No se pudo cambiar la velocidad.');
        });
    }, 180);
  }

  const openNode = useCallback(async (airport: Airport) => {
    setSelectedFlightId(null);
    setSelectedFlightVisual(null);
    setSelectedAirportIcao(airport.icaoCode);
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

  const occupancyFlota = simulacionActiva ? (overview?.avgFlightOccupancyPct ?? 0) : 0;
  const occupancyNodos = simulacionActiva ? (overview?.avgNodeOccupancyPct ?? 0) : 0;

  function FloatingKpiBar() {
    const elapsedSimStr = stateLoaded && sim && sim.effectiveScenarioStartAt && sim.simulatedNow
      ? formatDuration((new Date(sim.simulatedNow).getTime() - new Date(sim.effectiveScenarioStartAt).getTime()) / 1000)
      : null;
    const elapsedRealStr = stateLoaded && sim && sim.startedAt
      ? formatDuration((Date.now() - new Date(sim.startedAt).getTime()) / 1000)
      : null;
    const semaforoFlota = semaforoMax(occupancyFlota, 70, 90);
    const semaforoNodos = semaforoMax(occupancyNodos, 70, 90);
    const saLabel = stateLoaded && sim ? formatDuration(sim.planningIntervalSeconds ?? 300) : null;
    const scLabel = stateLoaded && sim ? formatDuration(sim.consumptionWindowSeconds ?? 0) : null;
    return (
      <div style={{
        position: 'absolute', bottom: 12, left: 12, zIndex: 5,
        padding: '10px 16px',
        background: 'rgba(13,16,30,0.85)',
        backdropFilter: 'blur(14px)',
        WebkitBackdropFilter: 'blur(14px)',
        borderRadius: 12,
        border: '1px solid rgba(148,163,184,0.13)',
        boxShadow: '0 4px 24px rgba(0,0,0,0.35)',
        fontSize: 11, color: '#c7d1e6',
        fontVariantNumeric: 'tabular-nums',
        pointerEvents: 'auto',
        display: 'flex', flexDirection: 'column', gap: 7, minWidth: 420,
      }}>
        {/* Fila 1 — Ocupación global */}
        {!liveOnly && (
          <div style={{ display: 'flex', alignItems: 'center', gap: 18, flexWrap: 'wrap' }}>
            <span style={{ display: 'flex', alignItems: 'center', gap: 6, fontWeight: 500 }}>
              <span style={{ width: 9, height: 9, borderRadius: 99, flexShrink: 0, background: colorSemaforo(semaforoFlota) }} />
              Ocupación de flota: {fmtPct(occupancyFlota)}
              <span style={{ fontSize: 10, fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.3px', background: colorSemaforo(semaforoFlota), color: '#0d101e', padding: '0 5px', borderRadius: 3, lineHeight: '16px' }}>
                {semaforoFlota === 'ok' ? 'Normal' : semaforoFlota === 'warn' ? 'Alerta' : 'Crítico'}
              </span>
            </span>
            <span style={{ display: 'flex', alignItems: 'center', gap: 6, fontWeight: 500 }}>
              <span style={{ width: 9, height: 9, borderRadius: 99, flexShrink: 0, background: colorSemaforo(semaforoNodos) }} />
              Ocupación de aeropuertos: {fmtPct(occupancyNodos)}
              <span style={{ fontSize: 10, fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.3px', background: colorSemaforo(semaforoNodos), color: '#0d101e', padding: '0 5px', borderRadius: 3, lineHeight: '16px' }}>
                {semaforoNodos === 'ok' ? 'Normal' : semaforoNodos === 'warn' ? 'Alerta' : 'Crítico'}
              </span>
            </span>
          </div>
        )}
        {/* Fila 2 — Tiempos de simulación y real */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 16, flexWrap: 'wrap' }}>
          <span style={{ display: 'flex', alignItems: 'center', gap: 5 }}>
            <span style={{ width: 7, height: 7, borderRadius: 99, flexShrink: 0, background: simulacionActiva ? '#22c55e' : '#64748b' }} />
            <span style={{ color: '#94a3b8' }}>Transcurrido en simulación:</span>
            <span style={{ fontFamily: 'var(--font-mono)', color: '#f0f4ff' }}>{elapsedSimStr ?? '—'}</span>
          </span>
        </div>
        {/* Fila 3 — Hora real + rendimiento */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 16, flexWrap: 'wrap' }}>
          {elapsedRealStr && (
            <span style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
              <span style={{ color: '#94a3b8' }}>Tiempo real transcurrido:</span>
              <span style={{ fontFamily: 'var(--font-mono)', color: '#f0f4ff' }}>{elapsedRealStr}</span>
            </span>
          )}
          {stateLoaded && sim ? (
            <span style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
              <span style={{ color: '#94a3b8' }}>Salto de simulación (Sa):</span>
              <span style={{ fontFamily: 'var(--font-mono)', color: '#f0f4ff' }}>{saLabel}</span>
            </span>
          ) : null}
          {stateLoaded && sim ? (
            <span style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
              <span style={{ color: '#94a3b8' }}>K:</span>
              <span style={{ fontFamily: 'var(--font-mono)', color: '#f0f4ff' }}>{sim.consumptionK ?? '?'}</span>
            </span>
          ) : null}
          {stateLoaded && sim ? (
            <span style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
              <span style={{ color: '#94a3b8' }}>Salto de consumo (Sc):</span>
              <span style={{ fontFamily: 'var(--font-mono)', color: '#f0f4ff' }}>{scLabel}</span>
            </span>
          ) : null}
          {stateLoaded && sim ? (
            <span style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
              <span style={{ color: '#94a3b8' }}>Tiempo de planificación (Ta):</span>
              <span style={{ fontFamily: 'var(--font-mono)', color: '#f0f4ff' }}>{sim.lastPlanningDurationMs} ms</span>
            </span>
          ) : null}
          {!liveOnly && stateLoaded && sim ? (
            <span style={{ display: 'flex', alignItems: 'center', gap: 5, color: showWaitingForPlanning ? '#fde68a' : '#bfdbfe' }}>
              <span style={{ width: 7, height: 7, borderRadius: 99, background: showWaitingForPlanning ? '#f59e0b' : planAheadActive ? '#60a5fa' : '#64748b' }} />
              <span>{showWaitingForPlanning ? 'Esperando planificacion' : planAheadActive ? 'Planificando' : 'Plan al dia'}</span>
              {planningBacklog > 0 ? (
                <span style={{ color: '#94a3b8' }}>backlog {planningBacklog.toLocaleString('es-PE')}</span>
              ) : null}
              {plannedThroughLabel ? (
                <span style={{ color: '#94a3b8' }}>hasta {plannedThroughLabel}</span>
              ) : null}
            </span>
          ) : null}
          {sim?.collapseDetectedAt ? (
            <span style={{ color: '#fca5a5', fontWeight: 600, display: 'flex', alignItems: 'center', gap: 5, padding: '2px 8px', background: 'rgba(239,68,68,0.12)', borderRadius: 6, border: '1px solid rgba(239,68,68,0.25)' }}>
              <span style={{ width: 7, height: 7, borderRadius: 99, background: '#ef4444' }} />
              Colapso detectado: {formatSimTime(new Date(sim.collapseDetectedAt))}
              {sim.collapseShipmentCode ? ` · Envío: ${sim.collapseShipmentCode}` : ''}
            </span>
          ) : null}
          <span style={{ flex: 1 }} />
          {!liveOnly && (
            <span style={{ display: 'flex', alignItems: 'center', gap: 3 }}>
              <span style={{ color: '#94a3b8', fontSize: 10 }}>Filtro de carga:</span>
              <button className={`chip${flightLoadFilter === 'ALL' ? ' is-active' : ''}`} onClick={() => setFlightLoadFilter('ALL')} style={{ fontSize: 10, padding: '2px 6px' }}>Todo</button>
              <button className={`chip${flightLoadFilter === 'EMPTY' ? ' is-active' : ''}`} onClick={() => setFlightLoadFilter('EMPTY')} style={{ fontSize: 10, padding: '2px 6px' }}>Vacío</button>
              <button className={`chip${flightLoadFilter === 'HIGH' ? ' is-active' : ''}`} onClick={() => setFlightLoadFilter('HIGH')} style={{ fontSize: 10, padding: '2px 6px' }}>Lleno</button>
            </span>
          )}
        </div>
      </div>
    );
  }

  return (
    <div className="app-page" style={{ overflowY: 'auto', height: '100vh' }}>
      <div style={{ height: '100vh', display: 'flex', flexDirection: 'column', flexShrink: 0 }}>
        <header className="page-head" style={{ flexShrink: 0, flexDirection: 'column', alignItems: 'stretch', paddingTop: 10, paddingBottom: 10, minHeight: 'auto' }}>
          <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', width: '100%', gap: 10, flexWrap: 'wrap' }}>
            <div>
              <h1 className="page-head-title" style={{ fontSize: 20 }}>{liveOnly ? 'Operación día a día' : 'Simulaciones'}</h1>
            </div>
            <div style={{ display: liveOnly ? 'none' : 'flex', gap: 5, flexWrap: 'wrap', alignItems: 'center' }}>
              <button className="btn btn-primary" style={{ fontSize: 12, height: 32, padding: '0 12px' }} disabled={simulacionActiva || controlBusy} onClick={onStart}>{controlBusy && !stoppingVisible ? 'Preparando...' : 'Iniciar'}</button>
              <button className="btn btn-primary" style={{ fontSize: 12, height: 32, padding: '0 12px' }} disabled={!canResume || controlBusy} onClick={onResume}>Reanudar</button>
              <button className="btn btn-neutral" style={{ fontSize: 12, height: 32, padding: '0 12px' }} disabled={!sim?.running || Boolean(sim?.paused) || controlBusy} onClick={onPause}>Pausar</button>
              <button className="btn btn-danger" style={{ fontSize: 12, height: 32, padding: '0 12px' }} disabled={(!sim?.running && !sim?.paused) || controlBusy} onClick={onStop}>{controlBusy && stoppingVisible ? 'Deteniendo...' : 'Detener'}</button>
              <span style={{ width: 1, height: 20, background: '#32364f', margin: '0 2px' }} />
              <label style={{
                display: 'flex',
                alignItems: 'center',
                gap: 8,
                minWidth: 190,
                color: '#c7d1e6',
                fontSize: 12,
              }}>
                <span style={{ color: '#94a3b8' }}>Velocidad</span>
                <input
                  type="range"
                  min={MIN_SIM_SPEED}
                  max={MAX_SIM_SPEED}
                  step={1}
                  value={selectedSpeed}
                  disabled={config.scenario === 'DAY_TO_DAY' || !simulacionActiva || controlBusy}
                  onChange={(e) => void onSetSpeed(Number(e.target.value))}
                  style={{ flex: 1, accentColor: '#7a99ff' }}
                />
                <strong style={{ minWidth: 34, textAlign: 'right', color: '#eaf0ff', fontVariantNumeric: 'tabular-nums' }}>
                  x{selectedSpeed}
                </strong>
              </label>
            </div>
          </div>
          <div style={{ display: liveOnly ? 'none' : 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap', marginTop: 6 }}>
            <label style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: 12, color: '#9ca7c8' }}>
              Escenario
              <select disabled={!canEditConfig} value={config.scenario} onChange={(e) => patchConfig({ scenario: e.target.value as SimScenario })} style={{ ...field, width: 'auto', minWidth: 140, height: 32, fontSize: 12 }}>
                <option value="PERIOD_SIMULATION">Simulación de periodo</option>
                <option value="COLLAPSE_TEST">Prueba de colapso</option>
              </select>
            </label>
            <label style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: 12, color: '#9ca7c8' }}>
              Inicio
              <input lang="es-PE" disabled={!canEditConfig} type="datetime-local" step={60} value={config.scenarioStartDate} onChange={(e) => patchConfig({ scenarioStartDate: e.target.value })} style={{ ...field, width: 180, height: 32, fontSize: 12 }} />
            </label>
            {config.scenario === 'PERIOD_SIMULATION' && (
              <label style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: 12, color: '#9ca7c8' }}>
                Duración
                <select disabled={!canEditConfig} value={config.simulationDays} onChange={(e) => patchConfig({ simulationDays: Number(e.target.value) as 3 | 5 | 7 })} style={{ ...field, width: 'auto', minWidth: 90, height: 32, fontSize: 12 }}>
                  <option value={3}>3 días</option>
                  <option value={5}>5 días</option>
                  <option value={7}>7 días</option>
                </select>
              </label>
            )}
            <button className="btn btn-primary" disabled={!canEditConfig || savingConfig} onClick={() => void saveScenarioConfig()} style={{ height: 32, fontSize: 12, padding: '0 12px' }}>
              {savingConfig ? 'Guardando...' : 'Guardar'}
            </button>
            {sim?.dateAdjusted && sim?.dateAdjustmentReason ? (
              <span style={{ fontSize: 10, color: '#fbbf24' }}>{sim.dateAdjustmentReason}</span>
            ) : null}
          </div>
          {!liveOnly && controlBusy ? (
            <div style={{
              marginTop: 8,
              padding: '8px 10px',
              border: '1px solid rgba(96, 165, 250, 0.28)',
              background: 'rgba(30, 41, 59, 0.72)',
              borderRadius: 8,
              display: 'flex',
              alignItems: 'center',
              gap: 10,
              color: '#dbeafe',
              fontSize: 12,
              flexWrap: 'wrap',
            }}>
              <span style={{ width: 8, height: 8, borderRadius: 99, background: '#60a5fa', boxShadow: '0 0 0 4px rgba(96,165,250,0.18)' }} />
              <strong>{stoppingVisible ? 'Deteniendo simulación' : 'Preparando simulación'}</strong>
              <span style={{ color: '#a9b3cf' }}>{sim?.bootstrapMessage ?? (stoppingVisible ? 'Limpiando datos operativos.' : 'Preplanificando rutas iniciales.')}</span>
              {sim?.bootstrapTotalShipments ? (
                <span style={{ fontFamily: 'var(--font-mono)' }}>
                  {Math.min(sim.bootstrapPlannedShipments, sim.bootstrapTotalShipments).toLocaleString('es-PE')}/{sim.bootstrapTotalShipments.toLocaleString('es-PE')} envíos
                </span>
              ) : null}
              {sim?.periodPlanningBacklog ? (
                <span style={{ fontFamily: 'var(--font-mono)', color: '#bfdbfe' }}>
                  backlog plan: {sim.periodPlanningBacklog.toLocaleString('es-PE')}
                </span>
              ) : null}
            </div>
          ) : null}
        </header>

        <div style={{ flex: 1, display: 'flex', flexDirection: 'column', minHeight: 0, padding: '0 10px', position: 'relative' }}>
          <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
            {focusedShipmentCode ? (
              <div style={{
                position: 'absolute', top: 8, left: '50%', transform: 'translateX(-50%)', zIndex: 10,
                padding: '4px 12px', borderRadius: 8, background: '#1e1b4b', border: '1px solid #a78bfa',
                boxShadow: '0 4px 20px rgba(0,0,0,0.5)', color: '#e0e7ff', fontSize: 11, fontWeight: 600,
                display: 'flex', alignItems: 'center', gap: 6, whiteSpace: 'nowrap',
              }}>
                <span>📍</span>
                <span>{focusedShipmentCode}</span>
                <button type="button" onClick={() => setFocusedShipmentCode(null)} style={{ background: 'none', border: 'none', color: '#a5b4fc', cursor: 'pointer', fontSize: 12, padding: 0, lineHeight: 1 }} aria-label="Cerrar">✕</button>
              </div>
            ) : null}
            {focusedFlightCode ? (
              <div style={{
                position: 'absolute', top: 36, left: '50%', transform: 'translateX(-50%)', zIndex: 10,
                padding: '4px 12px', borderRadius: 8, background: '#1b2b4b', border: '1px solid #60a5fa',
                boxShadow: '0 4px 20px rgba(0,0,0,0.5)', color: '#e0e7ff', fontSize: 11, fontWeight: 600,
                display: 'flex', alignItems: 'center', gap: 6, whiteSpace: 'nowrap',
              }}>
                <span>✈</span>
                <span>{focusedFlightCode}</span>
                <button type="button" onClick={() => setFocusedFlightCode(null)} style={{ background: 'none', border: 'none', color: '#93c5fd', cursor: 'pointer', fontSize: 12, padding: 0, lineHeight: 1 }} aria-label="Cerrar">✕</button>
              </div>
            ) : null}
            <MapErrorBoundary>
              <SimulationMapPanel
                airports={filteredAirports}
                flights={renderedFlights}
                shipments={mapLive}
                renderedFlights={renderedFlights}
                active={simulacionActiva}
                mapMode={mapMode}
                mapRef={mapRef}
                onMapModeChange={setMapMode}
                selectedShipment={selectedShipment}
                selectedShipmentId={selectedShipmentId}
                selectedFlight={selectedFlight}
                selectedFlightId={selectedFlightId}
                selectedNode={selectedNode}
                onSelectedFlightIdChange={setSelectedFlightId}
                onSelectedFlightVisualChange={setSelectedFlightVisual}
                onSelectedNodeChange={setSelectedNode}
                onSelectedShipmentChange={setSelectedShipmentId}
                onOpenNode={openNode}
                onMapClick={() => setSelectedAirportIcao(null)}
                bootstrapping={bootstrapping || starting}
                bootstrapPlannedShipments={sim?.bootstrapPlannedShipments ?? 0}
                bootstrapTotalShipments={sim?.bootstrapTotalShipments ?? 0}
                bootstrapMessage={sim?.bootstrapMessage}
                maximized={mapMaximized}
                onToggleMaximized={() => {}}
                liveOnly={liveOnly}
                simClock={displayedSimTime}
                waitingForPlanning={showWaitingForPlanning}
                planningActive={planAheadActive}
                planningBacklog={planningBacklog}
                plannedThroughLabel={plannedThroughLabel}
              />
            </MapErrorBoundary>
            {showClosureReport ? (
              <ClosureReportOverlay
                results={closureResults}
                loading={loadingClosure}
                sim={sim}
                onDismiss={() => setShowClosureReport(false)}
              />
            ) : null}
          </div>
          <FloatingKpiBar />
        </div>
      </div>

      <div style={{ padding: '10px 16px 16px' }}>
        <div style={{ marginBottom: 8, display: 'flex', gap: 6, flexWrap: 'wrap', alignItems: 'center' }}>
          <span style={{ fontSize: 13, color: '#9ca7c8', fontWeight: 600 }}>Aeropuertos</span>
          <span style={{ width: 1, height: 16, background: '#32364f' }} />
          <button className={`chip${airportStatusFilter === 'ALL' ? ' is-active' : ''}`} onClick={() => setAirportStatusFilter('ALL')}>Todos</button>
          <button className={`chip${airportStatusFilter === 'SIN_USO' ? ' is-active' : ''}`} onClick={() => setAirportStatusFilter('SIN_USO')}>Sin uso</button>
          <button className={`chip${airportStatusFilter === 'NORMAL' ? ' is-active' : ''}`} onClick={() => setAirportStatusFilter('NORMAL')}>Normal</button>
          <button className={`chip${airportStatusFilter === 'ALERTA' ? ' is-active' : ''}`} onClick={() => setAirportStatusFilter('ALERTA')}>Alerta</button>
          <button className={`chip${airportStatusFilter === 'CRITICO' ? ' is-active' : ''}`} onClick={() => setAirportStatusFilter('CRITICO')}>Crítico</button>
        </div>
        <AirportPanel airports={filteredAirports} externalSelectedIcao={selectedAirportIcao} onFocusAirport={(icao) => {
          const airport = operationalAirports.find((a) => a.icaoCode === icao);
          if (airport && mapRef.current) {
            mapRef.current.flyTo({ center: [airport.longitude, airport.latitude], zoom: 5 });
          }
        }} liveOnly={liveOnly} />
        <OperationalShipmentsPanel
          active={simulacionActiva}
          simDate={sim?.simulatedNow ?? sim?.effectiveScenarioStartAt ?? null}
          liveShipments={mapLive}
          selectedShipmentId={selectedShipmentId}
          onSelectShipment={(shipment) => {
            setSelectedShipmentId(shipment.shipmentId);
            setSelectedFlightId(null);
            setSelectedNode(null);
            if (mapRef.current) {
              mapRef.current.flyTo({ center: [shipment.currentLongitude, shipment.currentLatitude], zoom: 5 });
            }
          }}
          onSelectPlannedShipment={(shipment) => {
            setSelectedShipmentId(shipment.id);
            setSelectedFlightId(null);
            setSelectedNode(null);
          }}
        />
      </div>

      {error ? (
        <div className="state-panel is-error" style={{ margin: '0 16px 8px' }}>
          <p className="state-panel-title">Error operativo</p>
          <p className="state-panel-copy">{error}</p>
        </div>
      ) : null}
    </div>
  );
}

function OperationalShipmentsPanel({
  active,
  simDate,
  liveShipments,
  selectedShipmentId,
  onSelectShipment,
  onSelectPlannedShipment,
}: {
  active: boolean;
  simDate: string | null;
  liveShipments: MapLiveShipment[];
  selectedShipmentId: number | null;
  onSelectShipment: (shipment: MapLiveShipment) => void;
  onSelectPlannedShipment: (shipment: ShipmentUpcoming) => void;
}) {
  const [query, setQuery] = useState('');
  const [planned, setPlanned] = useState<ShipmentUpcoming[]>([]);
  const [loading, setLoading] = useState(false);
  const day = useMemo(() => {
    if (!simDate) return '';
    const d = new Date(simDate);
    return Number.isNaN(d.getTime()) ? '' : d.toISOString().slice(0, 10);
  }, [simDate]);

  useEffect(() => {
    if (!active || !day) {
      setPlanned([]);
      return;
    }
    let cancelled = false;
    const load = async () => {
      setLoading(true);
      try {
        const page = await shipmentsApi.upcoming({ date: day, page: 0, size: 500 });
        if (!cancelled) setPlanned(page.content);
      } catch {
        if (!cancelled) setPlanned([]);
      } finally {
        if (!cancelled) setLoading(false);
      }
    };
    void load();
    const timer = setInterval(load, 10_000);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [active, day]);

  const normalized = query.trim().toUpperCase();
  const filteredLive = liveShipments
    .filter((s) => !normalized
      || s.shipmentCode.toUpperCase().includes(normalized)
      || s.originIcao.toUpperCase().includes(normalized)
      || s.destinationIcao.toUpperCase().includes(normalized)
      || (s.currentFlightCode ?? '').toUpperCase().includes(normalized))
    .slice(0, 500);
  const filteredPlanned = planned
    .filter((s) => !normalized
      || s.shipmentCode.toUpperCase().includes(normalized)
      || s.originAirport.icaoCode.toUpperCase().includes(normalized)
      || s.destinationAirport.icaoCode.toUpperCase().includes(normalized)
      || (s.nextFlightCode ?? '').toUpperCase().includes(normalized))
    .slice(0, 500);

  return (
    <section className="surface-panel" style={{ marginTop: 16, padding: 12 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
        <div>
          <p style={{ margin: 0, fontWeight: 700, color: '#eaf0ff' }}>Envios operativos</p>
          <p style={{ margin: '2px 0 0', fontSize: 11, color: '#7f89a8' }}>Ruta por ID, vuelo asignado y maletas transportadas</p>
        </div>
        <input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Buscar envio, ruta o vuelo"
          className="shipments-search"
          style={{ width: 260, height: 32, fontSize: 12 }}
        />
      </div>

      {!active ? (
        <p style={{ margin: '10px 0 0', color: '#7f89a8', fontSize: 12 }}>Inicia una simulacion u operacion para ver envios en mapa.</p>
      ) : (
        <div style={{ marginTop: 10, display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
          <ShipmentListBlock
            title={`En vuelo (${filteredLive.length})`}
            empty="Sin envios en vuelo visibles"
          >
            {filteredLive.map((shipment) => (
              <button
                key={shipment.shipmentId}
                type="button"
                onClick={() => onSelectShipment(shipment)}
                style={shipmentRowStyle(selectedShipmentId === shipment.shipmentId)}
              >
                <span>
                  <strong style={{ fontFamily: 'monospace', color: '#eaf0ff' }}>{shipment.shipmentCode}</strong>
                  <span style={{ color: '#93a0bf' }}> {shipment.originIcao}{' -> '}{shipment.destinationIcao}</span>
                </span>
                <span style={{ color: '#9ca3bf' }}>
                  {shipment.currentFlightCode ?? 'en nodo'} · {shipment.progressPct.toFixed(0)}%
                </span>
                <span style={{ color: '#bfdbfe', fontSize: 11 }}>Ver ruta en mapa</span>
              </button>
            ))}
          </ShipmentListBlock>

          <ShipmentListBlock
            title={`Planificados (${filteredPlanned.length})`}
            empty={loading ? 'Cargando planificacion...' : 'Sin envios planificados para el dia'}
          >
            {filteredPlanned.map((shipment) => (
              <button
                key={shipment.id}
                type="button"
                onClick={() => onSelectPlannedShipment(shipment)}
                style={shipmentRowStyle(selectedShipmentId === shipment.id)}
              >
                <span>
                  <strong style={{ fontFamily: 'monospace', color: '#eaf0ff' }}>{shipment.shipmentCode}</strong>
                  <span style={{ color: '#93a0bf' }}> {shipment.originAirport.icaoCode}{' -> '}{shipment.destinationAirport.icaoCode}</span>
                </span>
                <span style={{ color: '#9ca3bf' }}>
                  {shipment.nextFlightCode ?? 'sin vuelo'} · {shipment.luggageCount} maleta(s)
                </span>
                <span style={{ color: '#bfdbfe', fontSize: 11 }}>Dibujar ruta · <Link href={`/shipments?selected=${shipment.id}`} style={{ color: '#bfdbfe' }}>detalle</Link></span>
              </button>
            ))}
          </ShipmentListBlock>
        </div>
      )}
    </section>
  );
}

function ShipmentListBlock({ title, empty, children }: { title: string; empty: string; children: ReactNode }) {
  const hasChildren = Array.isArray(children) ? children.length > 0 : Boolean(children);
  return (
    <div style={{ minWidth: 0 }}>
      <p style={{ margin: '0 0 6px', color: '#cbd5e1', fontSize: 12, fontWeight: 700 }}>{title}</p>
      <div style={{ display: 'grid', gap: 5, maxHeight: 260, overflowY: 'auto' }}>
        {hasChildren ? children : <p style={{ margin: 0, color: '#7f89a8', fontSize: 12 }}>{empty}</p>}
      </div>
    </div>
  );
}

function shipmentRowStyle(selected: boolean): CSSProperties {
  return {
    width: '100%',
    display: 'grid',
    gridTemplateColumns: 'minmax(0, 1.2fr) minmax(140px, 0.8fr) auto',
    gap: 8,
    alignItems: 'center',
    padding: '7px 9px',
    borderRadius: 6,
    border: `1px solid ${selected ? '#5f82ff' : '#262940'}`,
    background: selected ? 'rgba(95,130,255,0.12)' : '#171a29',
    color: '#dce4ff',
    cursor: 'pointer',
    fontSize: 12,
    textAlign: 'left',
  };
}

function ClosureReportOverlay({
  results,
  loading,
  sim,
  onDismiss,
}: {
  results: SimulationResults | null;
  loading: boolean;
  sim: {
    scenario?: SimScenario;
    collapseDetectedAt?: string | null;
    collapseShipmentCode?: string | null;
    collapseSurvivalSeconds?: number | null;
    effectiveScenarioStartAt?: string | null;
    simulatedNow?: string | null;
    bootstrapTotalShipments?: number;
    bootstrapPlannedShipments?: number;
    startedAt?: string | null;
  } | null;
  onDismiss: () => void;
}) {
  const collapse = Boolean(sim?.collapseDetectedAt);
  const collapseScenarioCompleted = sim?.scenario === 'COLLAPSE_TEST' && !collapse;
  const scenarioLabel = sim?.scenario === 'COLLAPSE_TEST' ? 'Prueba de colapso'
    : sim?.scenario === 'PERIOD_SIMULATION' ? 'Simulación de período'
    : sim?.scenario ?? '-';

  const startTime = sim?.effectiveScenarioStartAt
    ? formatSimTime(new Date(sim.effectiveScenarioStartAt))
    : '-';
  const endTime = sim?.simulatedNow
    ? formatSimTime(new Date(sim.simulatedNow))
    : '-';
  const totalPlanned = results?.totalShipments ?? sim?.bootstrapTotalShipments ?? 0;
  const plannedShipments = results?.plannedShipments ?? sim?.bootstrapPlannedShipments ?? 0;
  const failedPlanning = results?.failedPlanningShipments ?? 0;
  const backlog = results?.periodPlanningBacklog ?? 0;
  const delivered = results?.kpis?.delivered ?? 0;
  const delayed = results?.kpis?.delayed ?? 0;
  const critical = results?.kpis?.critical ?? 0;
  const active = results?.activeShipments ?? results?.kpis?.active ?? 0;
  const late = delayed + critical;
  const avgFlightOccupancy = results?.kpis?.avgFlightOccupancyPct ?? 0;
  const avgNodeOccupancy = results?.avgNodeOccupancyPct ?? results?.kpis?.avgNodeOccupancyPct ?? 0;
  const onTimePct = results?.kpis?.deliveredOnTimePct ?? 0;
  const lastPlanningDurationMs = results?.lastPlanningDurationMs ?? 0;
  const replannings = results?.replannings ?? 0;

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
      <article
        className="surface-panel"
        style={{
          width: 'min(520px, 100%)',
          padding: 22,
          border: collapse ? '1px solid #ef4444' : '1px solid rgba(96, 165, 250, 0.28)',
          maxHeight: '90vh',
          overflowY: 'auto',
        }}
      >
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
          <p style={{ margin: 0, fontSize: 12, color: collapse ? '#fca5a5' : '#c7d2fe', letterSpacing: 1.2, textTransform: 'uppercase', fontWeight: 700 }}>
            {collapse ? 'Colapso detectado' : collapseScenarioCompleted ? 'Prueba completada sin colapso' : 'Simulación finalizada'}
          </p>
          <button
            type="button"
            onClick={onDismiss}
            style={{ background: 'transparent', border: 'none', color: '#8f98b6', cursor: 'pointer', fontSize: 18, padding: '2px 6px', lineHeight: 1 }}
            aria-label="Cerrar reporte"
          >
            ✕
          </button>
        </div>

        <h3 style={{ margin: '0 0 14px', color: '#eff3ff', fontSize: 22 }}>
          Reporte de cierre
        </h3>

        {loading ? (
          <p style={{ color: '#a9b3cf', fontSize: 14 }}>Cargando resultados...</p>
        ) : (
          <>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10, marginBottom: 14 }}>
              <div style={{ padding: '10px 12px', borderRadius: 10, background: '#171a29' }}>
                <span style={{ fontSize: 11, color: '#8f98b6' }}>Escenario</span>
                <p style={{ margin: '4px 0 0', color: '#edf1ff', fontSize: 15, fontWeight: 600 }}>{scenarioLabel}</p>
              </div>
              <div style={{ padding: '10px 12px', borderRadius: 10, background: '#171a29' }}>
                <span style={{ fontSize: 11, color: '#8f98b6' }}>Tiempo de inicio</span>
                <p style={{ margin: '4px 0 0', color: '#edf1ff', fontSize: 15, fontWeight: 600 }}>{startTime}</p>
              </div>
              <div style={{ padding: '10px 12px', borderRadius: 10, background: '#171a29' }}>
                <span style={{ fontSize: 11, color: '#8f98b6' }}>Tiempo de fin</span>
                <p style={{ margin: '4px 0 0', color: '#edf1ff', fontSize: 15, fontWeight: 600 }}>{endTime}</p>
              </div>
              <div style={{ padding: '10px 12px', borderRadius: 10, background: '#171a29' }}>
                <span style={{ fontSize: 11, color: '#8f98b6' }}>Ocupación flota</span>
                <p style={{ margin: '4px 0 0', color: '#edf1ff', fontSize: 15, fontWeight: 600 }}>{avgFlightOccupancy.toFixed(1)}%</p>
              </div>
            </div>

            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr 1fr', gap: 8, marginBottom: 14 }}>
              <KpiCard title="Total periodo" value={totalPlanned.toLocaleString('es-PE')} status="neutral" />
              <KpiCard title="Planificados" value={plannedShipments.toLocaleString('es-PE')} status="neutral" />
              <KpiCard title="Entregados" value={delivered.toLocaleString('es-PE')} status="ok" />
              <KpiCard title="Fuera de plazo" value={late.toLocaleString('es-PE')} status="bad" />
            </div>

            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr 1fr', gap: 8, marginBottom: 14 }}>
              <KpiCard title="Activos" value={active.toLocaleString('es-PE')} status="neutral" />
              <KpiCard title="Backlog plan" value={backlog.toLocaleString('es-PE')} status={backlog > 0 ? 'bad' : 'ok'} />
              <KpiCard title="Fallos plan" value={failedPlanning.toLocaleString('es-PE')} status={failedPlanning > 0 ? 'bad' : 'ok'} />
              <KpiCard title="Replanificaciones" value={replannings.toLocaleString('es-PE')} status="neutral" />
            </div>

            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 8, marginBottom: 14 }}>
              <KpiCard title="SLA a tiempo" value={`${onTimePct.toFixed(1)}%`} status={onTimePct >= 90 ? 'ok' : onTimePct >= 75 ? 'neutral' : 'bad'} />
              <KpiCard title="Ocup. aeropuertos" value={`${avgNodeOccupancy.toFixed(1)}%`} status={avgNodeOccupancy < 70 ? 'ok' : avgNodeOccupancy <= 90 ? 'neutral' : 'bad'} />
              <KpiCard title="Ta último plan" value={`${lastPlanningDurationMs} ms`} status="neutral" />
            </div>

            {collapse && sim?.collapseShipmentCode ? (
              <div style={{ padding: '12px 14px', borderRadius: 10, background: '#2a1414', border: '1px solid #ef4444', marginBottom: 14 }}>
                <p style={{ margin: 0, fontSize: 12, color: '#fca5a5', fontWeight: 700, textTransform: 'uppercase', letterSpacing: 0.4 }}>
                  Detalle del colapso
                </p>
                <div style={{ marginTop: 8, display: 'grid', gap: 6 }}>
                  <p style={{ margin: 0, color: '#fecaca', fontSize: 13 }}>
                    <strong>Envío:</strong> {sim.collapseShipmentCode}
                  </p>
                  <p style={{ margin: 0, color: '#fecaca', fontSize: 13 }}>
                    <strong>Supervivencia:</strong> {sim.collapseSurvivalSeconds != null ? formatDuration(sim.collapseSurvivalSeconds) : '-'}
                  </p>
                  <p style={{ margin: 0, color: '#f8b4b4', fontSize: 12 }}>
                    Primer envío que no llegó a tiempo. Simulación detenida.
                  </p>
                </div>
              </div>
            ) : null}
            {collapseScenarioCompleted ? (
              <div style={{ padding: '12px 14px', borderRadius: 10, background: '#102033', border: '1px solid rgba(96, 165, 250, 0.35)', marginBottom: 14 }}>
                <p style={{ margin: 0, fontSize: 12, color: '#bfdbfe', fontWeight: 700, textTransform: 'uppercase', letterSpacing: 0.4 }}>
                  Resultado de colapso
                </p>
                <p style={{ margin: '8px 0 0', color: '#dbeafe', fontSize: 13 }}>
                  No se detectó ningún envío fuera de plazo antes del fin de datos evaluado. La simulación se detuvo correctamente y conserva el reporte para auditoría.
                </p>
              </div>
            ) : null}
          </>
        )}
      </article>
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
