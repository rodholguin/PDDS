'use client';

import { useCallback, useEffect, useMemo, useRef, useState, type ReactElement } from 'react';
import Map, { Layer, Marker, NavigationControl, Popup, Source, type MapRef } from 'react-map-gl/maplibre';
import type { StyleSpecification } from 'maplibre-gl';
import { dashboardApi } from '@/lib/api/dashboardApi';
import { airportsApi } from '@/lib/api/airportsApi';
import { alertsApi } from '@/lib/api/alertsApi';
import { simulationApi } from '@/lib/api/simulationApi';
import { shipmentsApi } from '@/lib/api/shipmentsApi';
import type {
  Airport,
  DashboardOverview,
  MapLiveShipment,
  NodeDetail,
  RouteNetworkEdge,
  ShipmentStatus,
  ShipmentSummary,
  SimulationState,
  SystemStatus,
  TravelStop,
} from '@/lib/types';

const MAP_STYLE = 'https://demotiles.maplibre.org/style.json';
const SATELLITE_STYLE: StyleSpecification = {
  version: 8,
  sources: {
    esri: {
      type: 'raster',
      tiles: [
        'https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',
      ],
      tileSize: 256,
      attribution:
        'Tiles © Esri — Source: Esri, i-cubed, USDA, USGS, AEX, GeoEye, Getmapping, Aerogrid, IGN',
    },
  },
  layers: [{ id: 'esri-world-imagery', type: 'raster', source: 'esri' }],
} as const;

type MapMode = 'MAP' | 'SATELLITE';
type MapFilter = 'ALL' | ShipmentStatus;
const POLL_INTERVAL_MS = 2000;
const SHIPMENTS_REFRESH_MS = 6000;
const MAP_STATIC_REFRESH_MS = 15000;
const MAP_LIVE_REFRESH_MS = 1200;
const MAX_SHIPMENTS_FETCH = 2500;
const MAX_MAP_SHIPMENTS = 1200;
const MAX_FLIGHT_ANIMATION = 200;
const PLANE_ICON_ROTATION_OFFSET_DEG = -90;
const MAP_FILTERS_STORAGE_KEY = 'pdds-map-filters-v3';
const DASHBOARD_CACHE_KEY = 'pdds-dashboard-cache-v1';
const INITIAL_VIEW_STATE = {
  latitude: 16,
  longitude: -38,
  zoom: 1.45,
  bearing: 0,
  pitch: 0,
  padding: { top: 40, right: 40, bottom: 40, left: 40 },
} as const;

type GeoPoint = {
  longitude: number;
  latitude: number;
};

type RouteData = {
  doneGeoJson: {
    type: 'FeatureCollection';
    features: Array<ReturnType<typeof lineFeature>>;
  };
  pendingGeoJson: {
    type: 'FeatureCollection';
    features: Array<ReturnType<typeof lineFeature>>;
  };
  routeNodes: Array<[number, number]>;
  planeBearing: number;
  referenceLongitude: number;
  activeFromIdx: number;
  activeToIdx: number;
};

type FlightLive = {
  shipment: ShipmentSummary;
  current: GeoPoint;
  next: GeoPoint;
  bearing: number;
};

type PersistedDashboardState = {
  systemStatus: SystemStatus | null;
  overview: DashboardOverview | null;
  sim: SimulationState | null;
  shipments: ShipmentSummary[];
  networkEdges: RouteNetworkEdge[];
  airportNodes: Airport[];
  activeAlerts: Array<{ id: number; shipmentCode: string; type: string; note: string }>;
  timestamp: number;
};

function statusLabel(status: ShipmentStatus): string {
  if (status === 'IN_ROUTE') return 'En ruta';
  if (status === 'PENDING') return 'Programado';
  if (status === 'CRITICAL' || status === 'DELAYED') return 'Critico';
  if (status === 'DELIVERED') return 'Entregado';
  return status;
}

function criticalLabel(reason?: string | null): string {
  if (!reason || !reason.trim()) return 'Sin detalle';
  const normalized = reason.toLowerCase();
  if (normalized.includes('deadline')) return 'Deadline vencido';
  if (normalized.includes('cancelad')) return 'Vuelo cancelado';
  if (normalized.includes('capacidad')) return 'Capacidad saturada';
  if (normalized.includes('replan')) return 'Replanificacion fallida';
  return reason;
}

function markerColor(shipment: ShipmentSummary): string {
  if (shipment.overdue || shipment.status === 'CRITICAL' || shipment.status === 'DELAYED') return '#ff5a64';
  if (shipment.status === 'PENDING') return '#5f82ff';
  if (shipment.atRisk) return '#f0c13a';
  return '#43d29d';
}

function pointFeature(lng: number, lat: number, props: Record<string, unknown>) {
  return {
    type: 'Feature' as const,
    geometry: {
      type: 'Point' as const,
      coordinates: [lng, lat],
    },
    properties: props,
  };
}

function lineFeature(points: Array<[number, number]>, props: Record<string, unknown>) {
  return {
    type: 'Feature' as const,
    geometry: {
      type: 'LineString' as const,
      coordinates: points,
    },
    properties: props,
  };
}

function normalizeLongitude(lon: number): number {
  let value = lon;
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

function computeBearing(from: GeoPoint, to: GeoPoint): number {
  const lat1 = (from.latitude * Math.PI) / 180;
  const lat2 = (to.latitude * Math.PI) / 180;
  const dLon = ((to.longitude - from.longitude) * Math.PI) / 180;
  const y = Math.sin(dLon) * Math.cos(lat2);
  const x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
  const brng = (Math.atan2(y, x) * 180) / Math.PI;
  return (brng + 360) % 360;
}

function distanceSq(a: GeoPoint, b: GeoPoint): number {
  const dx = a.longitude - b.longitude;
  const dy = a.latitude - b.latitude;
  return dx * dx + dy * dy;
}

function clamp01(value: number): number {
  return Math.max(0, Math.min(1, value));
}

function lerp(a: number, b: number, t: number): number {
  return a + (b - a) * t;
}

function projectPointToSegment(point: GeoPoint, from: GeoPoint, to: GeoPoint): GeoPoint {
  const vx = to.longitude - from.longitude;
  const vy = to.latitude - from.latitude;
  const wx = point.longitude - from.longitude;
  const wy = point.latitude - from.latitude;
  const denom = vx * vx + vy * vy;
  if (denom === 0) return { longitude: from.longitude, latitude: from.latitude };
  const t = clamp01((wx * vx + wy * vy) / denom);
  return {
    longitude: from.longitude + vx * t,
    latitude: from.latitude + vy * t,
  };
}

function snapPointToPolyline(point: GeoPoint, nodes: Array<[number, number]>): GeoPoint {
  if (nodes.length < 2) return point;
  let best = point;
  let bestDist = Number.POSITIVE_INFINITY;
  for (let i = 1; i < nodes.length; i++) {
    const from = { longitude: nodes[i - 1][0], latitude: nodes[i - 1][1] };
    const to = { longitude: nodes[i][0], latitude: nodes[i][1] };
    const projected = projectPointToSegment(point, from, to);
    const dist = distanceSq(point, projected);
    if (dist < bestDist) {
      best = projected;
      bestDist = dist;
    }
  }
  return best;
}

function bearingToDestination(current: GeoPoint, destination: GeoPoint): number {
  const wrappedDestination = {
    longitude: nearestWrappedLongitude(destination.longitude, current.longitude),
    latitude: destination.latitude,
  };
  return computeBearing(current, wrappedDestination);
}

function routeLayers(stops: TravelStop[]): RouteData | null {
  if (stops.length < 2) return null;

  const raw = stops
    .map((stop) => ({
      longitude: stop.airportLongitude,
      latitude: stop.airportLatitude,
      status: stop.stopStatus,
      order: stop.stopOrder,
    }))
    .filter((point) => Number.isFinite(point.longitude) && Number.isFinite(point.latitude))
    .sort((a, b) => a.order - b.order);

  const coords: Array<[number, number]> = [];
  let previousLon: number | null = null;
  for (const point of raw) {
    const normalized = normalizeLongitude(point.longitude);
    const unwrapped: number = previousLon == null ? normalized : nearestWrappedLongitude(normalized, previousLon);
    previousLon = unwrapped;
    coords.push([unwrapped, point.latitude]);
  }

  if (coords.length < 2) return null;

  const doneIndex = Math.max(
    0,
    raw.reduce((idx, stop, current) => (stop.status === 'COMPLETED' ? current : idx), -1)
  );

  const doneCoords = coords.slice(0, Math.max(2, doneIndex + 1));
  const pendingCoords = coords.slice(Math.max(0, doneIndex));
  const safeDoneCoords = doneCoords.length >= 2 ? doneCoords : coords.slice(0, 2);
  const safePendingCoords = pendingCoords.length >= 2
    ? pendingCoords
    : coords.slice(Math.max(0, coords.length - 2));

  const transitIndex = raw.findIndex((stop) => stop.status === 'IN_TRANSIT');
  const pendingFlightIndex = raw.findIndex((stop, idx) => stop.status === 'PENDING' && idx > 0);
  const activeToIdx = transitIndex >= 1
    ? transitIndex
    : pendingFlightIndex >= 1
      ? pendingFlightIndex
      : Math.max(1, Math.min(coords.length - 1, doneIndex + 1));
  const activeFromIdx = Math.max(0, activeToIdx - 1);
  const fromPoint = coords[activeFromIdx] ?? coords[0];
  const toPoint = coords[activeToIdx] ?? coords[Math.min(coords.length - 1, activeFromIdx + 1)] ?? coords[coords.length - 1];
  const planeBearing = computeBearing(
    { longitude: fromPoint[0], latitude: fromPoint[1] },
    { longitude: toPoint[0], latitude: toPoint[1] }
  );

  return {
    doneGeoJson: {
      type: 'FeatureCollection' as const,
      features: safeDoneCoords.length >= 2 ? [lineFeature(safeDoneCoords, { state: 'done' })] : [],
    },
    pendingGeoJson: {
      type: 'FeatureCollection' as const,
      features: safePendingCoords.length >= 2 ? [lineFeature(safePendingCoords, { state: 'pending' })] : [],
    },
    routeNodes: coords,
    planeBearing,
    referenceLongitude: fromPoint[0],
    activeFromIdx,
    activeToIdx,
  };
}

export default function HomePage() {
  const mapRef = useRef<MapRef | null>(null);
  const [systemStatus, setSystemStatus] = useState<SystemStatus | null>(null);
  const [overview, setOverview] = useState<DashboardOverview | null>(null);
  const [sim, setSim] = useState<SimulationState | null>(null);
  const [shipments, setShipments] = useState<ShipmentSummary[]>([]);
  const [selectedShipmentId, setSelectedShipmentId] = useState<number | null>(null);
  const [selectedStops, setSelectedStops] = useState<TravelStop[]>([]);
  const [search, setSearch] = useState('');
  const [mapMode, setMapMode] = useState<MapMode>('MAP');
  const [showOnlyActive, setShowOnlyActive] = useState(false);
  const [statusFilter, setStatusFilter] = useState<MapFilter>('ALL');
  const [loading, setLoading] = useState(true);
  const [bootstrapped, setBootstrapped] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [isTransitioning, setIsTransitioning] = useState(false);
  const [selectedNodePopup, setSelectedNodePopup] = useState<{ detail: NodeDetail; point: GeoPoint } | null>(null);
  const [nodeAgendaDate, setNodeAgendaDate] = useState(() => new Date().toISOString().slice(0, 10));
  const [networkEdges, setNetworkEdges] = useState<RouteNetworkEdge[]>([]);
  const [airportNodes, setAirportNodes] = useState<Airport[]>([]);
  const [activeAlerts, setActiveAlerts] = useState<Array<{ id: number; shipmentCode: string; type: string; note: string }>>([]);
  const [mapLive, setMapLive] = useState<MapLiveShipment[]>([]);
  const [lastLiveRefresh, setLastLiveRefresh] = useState<string>('—');
  const [showNetworkLayer, setShowNetworkLayer] = useState(true);
  const [showShipmentFlights, setShowShipmentFlights] = useState(true);
  const filteredShipmentsRef = useRef<ShipmentSummary[]>([]);
  const [smoothedPositions, setSmoothedPositions] = useState<Record<number, GeoPoint>>({});
  const previousTargetsRef = useRef<Record<number, GeoPoint>>({});
  const lastBearingRef = useRef<Record<number, number>>({});
  const animationFrameRef = useRef<number | null>(null);
  const liveTargetsRef = useRef<MapLiveShipment[]>([]);
  const shipmentsForAnimRef = useRef<ShipmentSummary[]>([]);
  interface AnimSegment { fromLon: number; fromLat: number; toLon: number; toLat: number; startTs: number; durationMs: number; }
  const segmentRef = useRef<Record<number, AnimSegment>>({});
  const FALLBACK_DURATION_MS = 1200;
  const simRunningRef = useRef(false);
  const simTimeReceivedAtRef = useRef<number>(0);
  const simTimeBaseRef = useRef<string | null>(null);
  const simSpeedRef = useRef<number>(1);

  useEffect(() => {
    try {
      const raw = window.localStorage.getItem(MAP_FILTERS_STORAGE_KEY);
      if (!raw) return;
      const parsed = JSON.parse(raw) as {
        statusFilter?: MapFilter;
        showOnlyActive?: boolean;
      };
      setTimeout(() => {
        if (parsed.statusFilter) setStatusFilter(parsed.statusFilter);
        if (typeof parsed.showOnlyActive === 'boolean') setShowOnlyActive(parsed.showOnlyActive);
      }, 0);
    } catch {
      // Ignore malformed local storage data.
    }
  }, []);

  useEffect(() => {
    try {
      const raw = window.sessionStorage.getItem(DASHBOARD_CACHE_KEY);
      if (!raw) return;
      const parsed = JSON.parse(raw) as PersistedDashboardState;
      const maxAge = parsed.sim?.running ? 5_000 : 120_000;
      if (!parsed?.timestamp || Date.now() - parsed.timestamp > maxAge) return;
      setSystemStatus(parsed.systemStatus ?? null);
      setOverview(parsed.overview ?? null);
      setSim(parsed.sim ?? null);
      setShipments(parsed.shipments ?? []);
      setNetworkEdges(parsed.networkEdges ?? []);
      setAirportNodes(parsed.airportNodes ?? []);
      setActiveAlerts(parsed.activeAlerts ?? []);
      setLoading(false);
      setBootstrapped(true);
    } catch {
      // Ignore malformed cache.
    }
  }, []);

  useEffect(() => {
    window.localStorage.setItem(
      MAP_FILTERS_STORAGE_KEY,
      JSON.stringify({
        statusFilter,
        showOnlyActive,
      })
    );
  }, [showOnlyActive, statusFilter]);

  useEffect(() => {
    if (!bootstrapped) return;
    const payload: PersistedDashboardState = {
      systemStatus,
      overview,
      sim,
      shipments,
      networkEdges,
      airportNodes,
      activeAlerts,
      timestamp: Date.now(),
    };
    try {
      window.sessionStorage.setItem(DASHBOARD_CACHE_KEY, JSON.stringify(payload));
    } catch {
      // Ignore storage quota errors.
    }
  }, [bootstrapped, systemStatus, overview, sim, shipments, networkEdges, airportNodes, activeAlerts]);

  const inFlightRef = useRef<{ core: boolean; shipments: boolean; static: boolean; live: boolean }>({
    core: false,
    shipments: false,
    static: false,
    live: false,
  });

  const loadCore = useCallback(async () => {
    if (inFlightRef.current.core) return;
    inFlightRef.current.core = true;
    try {
      const [sysRes, overviewRes, simRes] = await Promise.allSettled([
        dashboardApi.getSystemStatus(),
        dashboardApi.getOverview(),
        simulationApi.getState(),
      ]);

      if (sysRes.status === 'fulfilled') setSystemStatus(sysRes.value);
      if (overviewRes.status === 'fulfilled') setOverview(overviewRes.value);
      if (simRes.status === 'fulfilled') setSim(simRes.value);

      if ([sysRes, overviewRes, simRes].every((r) => r.status === 'rejected')) {
        setError('No se pudo conectar con el backend en puerto 8080.');
      } else {
        setError(null);
      }
    } finally {
      inFlightRef.current.core = false;
      if (!bootstrapped) {
        setBootstrapped(true);
        setLoading(false);
      }
    }
  }, [bootstrapped]);

  const loadShipments = useCallback(async () => {
    if (inFlightRef.current.shipments) return;
    inFlightRef.current.shipments = true;
    try {
      const result = await dashboardApi.getShipmentSummaries({
        limit: MAX_SHIPMENTS_FETCH,
      });
      const priority = (s: ShipmentStatus): number => {
        if (s === 'IN_ROUTE') return 0;
        if (s === 'PENDING') return 1;
        if (s === 'CRITICAL' || s === 'DELAYED') return 2;
        return 3;
      };
      const sorted = result.sort((a, b) => {
        const p = priority(a.status) - priority(b.status);
        if (p !== 0) return p;
        return b.id - a.id;
      });
      setShipments(sorted);
    } finally {
      inFlightRef.current.shipments = false;
    }
  }, []);

  const loadStaticMap = useCallback(async () => {
    if (inFlightRef.current.static) return;
    inFlightRef.current.static = true;
    try {
      const [routesRes, airportsRes, alertsRes] = await Promise.allSettled([
        dashboardApi.getRoutesNetwork(),
        airportsApi.getAll(),
        alertsApi.list(),
      ]);
      if (routesRes.status === 'fulfilled') setNetworkEdges(routesRes.value);
      if (airportsRes.status === 'fulfilled') setAirportNodes(airportsRes.value);
      if (alertsRes.status === 'fulfilled') {
        setActiveAlerts(alertsRes.value.map((a) => ({ id: a.id, shipmentCode: a.shipmentCode, type: a.type, note: a.note })));
      }
    } finally {
      inFlightRef.current.static = false;
    }
  }, []);

  const loadMapLive = useCallback(async () => {
    if (inFlightRef.current.live) return;
    inFlightRef.current.live = true;
    try {
      const live = await dashboardApi.getMapLive(MAX_MAP_SHIPMENTS);
      setMapLive(live);
      setLastLiveRefresh(new Date().toLocaleTimeString());
    } finally {
      inFlightRef.current.live = false;
    }
  }, []);

  const loadSelectedShipment = useCallback(async (id: number) => {
    try {
      const detail = await shipmentsApi.getById(id);
      setSelectedStops(detail.stops ?? []);
    } catch {
      setSelectedStops([]);
    }
  }, []);

  const focusShipment = useCallback((id: number) => {
    setSelectedShipmentId(id);
    setSelectedNodePopup(null);
    const shipment = shipments.find((item) => item.id === id);
    if (!shipment || !mapRef.current) return;
    const smooth = smoothedPositions[id];
    const longitude = smooth ? smooth.longitude : shipment.currentLongitude;
    const latitude = smooth ? smooth.latitude : shipment.currentLatitude;
    mapRef.current.easeTo({
      center: [longitude, latitude],
      zoom: Math.max(mapRef.current.getZoom(), 3.2),
      duration: 700,
    });
  }, [shipments, smoothedPositions]);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setSelectedShipmentId(null);
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, []);

  useEffect(() => {
    if (selectedShipmentId === null) return;
    const stillExists = shipments.some((shipment) => shipment.id === selectedShipmentId);
    if (!stillExists) {
      setTimeout(() => setSelectedShipmentId(null), 0);
    }
  }, [shipments, selectedShipmentId]);

  useEffect(() => { liveTargetsRef.current = mapLive; }, [mapLive]);
  useEffect(() => { shipmentsForAnimRef.current = shipments; }, [shipments]);

  // Helper: compute visual position from a segment at a given time.
  // No coast — lerp capped at t=1.0 with duration stretched to 130% of poll interval,
  // so the plane is always slightly behind the real position and never overshoots.
  function getSegVisualPos(seg: AnimSegment, now: number): { lon: number; lat: number } {
    const t = Math.min(1.0, (now - seg.startTs) / seg.durationMs);
    return {
      lon: seg.fromLon + (seg.toLon - seg.fromLon) * t,
      lat: seg.fromLat + (seg.toLat - seg.fromLat) * t,
    };
  }

  // Update animation segments when new poll data arrives.
  // Strategy: stretch lerp duration to 130% of measured poll interval so the plane
  // never catches up to the real position. Always start from current visual position
  // so movement is continuous forward — no snap-backs possible.
  useEffect(() => {
    const now = performance.now();
    const POS_EPSILON = 0.0002;
    const DURATION_STRETCH = 1.3;
    for (const row of mapLive) {
      const id = row.shipmentId;
      const existing = segmentRef.current[id];
      const prev = previousTargetsRef.current[id];

      const wrapRef = existing ? existing.toLon : (prev?.longitude ?? row.originLongitude ?? row.currentLongitude);
      const wrappedCurrent = nearestWrappedLongitude(row.currentLongitude, wrapRef);

      if (existing) {
        const posChanged =
          Math.abs(wrappedCurrent - existing.toLon) > POS_EPSILON ||
          Math.abs(row.currentLatitude - existing.toLat) > POS_EPSILON;

        if (!posChanged) continue; // no tick yet, keep current segment running

        const measuredMs = Math.max(400, now - existing.startTs);
        const visualPos = getSegVisualPos(existing, now);

        // Always start from visual position — never snap
        segmentRef.current[id] = {
          fromLon: visualPos.lon, fromLat: visualPos.lat,
          toLon: wrappedCurrent, toLat: row.currentLatitude,
          startTs: now, durationMs: measuredMs * DURATION_STRETCH,
        };
      } else {
        const originLon = row.originLongitude ?? row.currentLongitude;
        const originLat = row.originLatitude ?? row.currentLatitude;
        segmentRef.current[id] = {
          fromLon: nearestWrappedLongitude(originLon, wrappedCurrent),
          fromLat: originLat,
          toLon: wrappedCurrent, toLat: row.currentLatitude,
          startTs: now, durationMs: FALLBACK_DURATION_MS,
        };
      }
    }
  }, [mapLive]);

  // Animation loop: pure linear interpolation per segment, no velocity prediction
  useEffect(() => {
    const FRAME_MIN_MS = 45;
    let lastRenderTs = 0;

    const animate = (now: number) => {
      const liveData = liveTargetsRef.current;
      const shipData = shipmentsForAnimRef.current;
      const liveIndex = new globalThis.Map<number, MapLiveShipment>();
      for (const row of liveData) liveIndex.set(row.shipmentId, row);

      const next: Record<number, GeoPoint> = {};

      for (const row of liveData) {
        const seg = segmentRef.current[row.shipmentId];
        if (!seg) {
          next[row.shipmentId] = {
            longitude: row.originLongitude ?? row.currentLongitude,
            latitude: row.originLatitude ?? row.currentLatitude,
          };
          continue;
        }

        const pos = getSegVisualPos(seg, now);
        next[row.shipmentId] = {
          longitude: normalizeLongitude(pos.lon),
          latitude: pos.lat,
        };
      }

      for (const shipment of shipData) {
        if (!liveIndex.has(shipment.id)) {
          next[shipment.id] = previousTargetsRef.current[shipment.id] ?? {
            longitude: shipment.currentLongitude,
            latitude: shipment.currentLatitude,
          };
        }
      }

      previousTargetsRef.current = next;
      if (now - lastRenderTs >= FRAME_MIN_MS) {
        setSmoothedPositions(next);
        lastRenderTs = now;
      }
      animationFrameRef.current = requestAnimationFrame(animate);
    };

    animationFrameRef.current = requestAnimationFrame(animate);
    return () => {
      if (animationFrameRef.current != null) cancelAnimationFrame(animationFrameRef.current);
    };
  }, []);

  useEffect(() => {
    const activeIds = new Set(shipments.map(s => s.id));
    for (const key of Object.keys(lastBearingRef.current)) {
      if (!activeIds.has(Number(key))) {
        delete lastBearingRef.current[Number(key)];
      }
    }
    for (const key of Object.keys(previousTargetsRef.current)) {
      if (!activeIds.has(Number(key))) {
        delete previousTargetsRef.current[Number(key)];
      }
    }
    for (const key of Object.keys(segmentRef.current)) {
      if (!activeIds.has(Number(key))) {
        delete segmentRef.current[Number(key)];
      }
    }
  }, [shipments]);

  useEffect(() => {
    if (sim?.simulatedNow) {
      simTimeReceivedAtRef.current = Date.now();
      simTimeBaseRef.current = sim.simulatedNow;
      simSpeedRef.current = sim.speed ?? 1;
    }
  }, [sim?.simulatedNow, sim?.speed]);

  const [displayedSimTime, setDisplayedSimTime] = useState<string | null>(null);

  useEffect(() => {
    if (!sim?.running || sim?.paused) {
      if (sim?.simulatedNow) {
        const parsed = new Date(sim.simulatedNow);
        setDisplayedSimTime(Number.isNaN(parsed.getTime()) ? sim.simulatedNow : parsed.toLocaleString());
      } else {
        setDisplayedSimTime(null);
      }
      return;
    }
    const tick = setInterval(() => {
      if (!simTimeBaseRef.current) return;
      const base = new Date(simTimeBaseRef.current).getTime();
      if (Number.isNaN(base)) return;
      const elapsed = Date.now() - simTimeReceivedAtRef.current;
      const projected = new Date(base + elapsed * simSpeedRef.current);
      setDisplayedSimTime(projected.toLocaleString());
    }, 500);
    return () => clearInterval(tick);
  }, [sim?.running, sim?.paused, sim?.simulatedNow]);

  async function onStart(): Promise<void> {
    setIsTransitioning(true);
    try {
      const res = await simulationApi.start();
      setSim(res.state);
      setShowShipmentFlights(true);
      setShowNetworkLayer(true);
      if (selectedShipmentId === null) {
        const currentFiltered = filteredShipmentsRef.current;
        const firstInRoute = currentFiltered.find((shipment) => shipment.status === 'IN_ROUTE');
        const fallback = currentFiltered[0] ?? null;
        const candidate = firstInRoute ?? fallback;
        if (candidate) {
          setSelectedShipmentId(candidate.id);
        }
      }
      setError(null);
      void Promise.all([loadCore(), loadShipments()]);
      setTimeout(() => {
        void loadShipments();
      }, 1200);
    } catch {
      setError('No fue posible iniciar la simulacion.');
    } finally {
      setTimeout(() => setIsTransitioning(false), 500);
    }
  }

  async function onStop(): Promise<void> {
    setIsTransitioning(true);
    try {
      const res = await simulationApi.stop();
      setSim(res.state);
      setError(null);
      void Promise.all([loadCore(), loadShipments()]);
    } catch {
      setError('No fue posible detener la simulacion.');
    } finally {
      setTimeout(() => setIsTransitioning(false), 500);
    }
  }

  async function onPause(): Promise<void> {
    try {
      const res = await simulationApi.pause();
      setSim(res.state);
      setError(null);
      void loadCore();
    } catch {
      setError('No fue posible pausar la simulacion.');
    }
  }

  async function onSetSpeed(speed: number): Promise<void> {
    const prevSim = sim;
    setSim((prev) => (prev ? { ...prev, speed } : prev));
    try {
      const res = await simulationApi.setSpeed(speed);
      setSim(res.state);
      setError(null);
      void loadCore();
    } catch {
      setSim(prevSim);
      setError('No fue posible cambiar la velocidad de simulacion.');
    }
  }

  useEffect(() => { simRunningRef.current = !!sim?.running; }, [sim?.running]);

  useEffect(() => {
    const initial = setTimeout(() => {
      void Promise.all([loadCore(), loadShipments(), loadStaticMap(), loadMapLive()]);
    }, 0);
    const coreTimer = setInterval(() => {
      void loadCore();
    }, POLL_INTERVAL_MS);
    const staticTimer = setInterval(() => {
      void loadStaticMap();
    }, MAP_STATIC_REFRESH_MS);
    const liveTimer = setInterval(() => {
      void loadMapLive();
    }, MAP_LIVE_REFRESH_MS);
    return () => {
      clearTimeout(initial);
      clearInterval(coreTimer);
      clearInterval(staticTimer);
      clearInterval(liveTimer);
    };
  }, [loadCore, loadStaticMap, loadMapLive]);

  useEffect(() => {
    const shipmentsTimer = setInterval(() => {
      void loadShipments();
    }, simRunningRef.current ? 2200 : SHIPMENTS_REFRESH_MS);
    return () => clearInterval(shipmentsTimer);
  }, [loadShipments]);

  useEffect(() => {
    if (!selectedShipmentId) return;
    const delayed = setTimeout(() => {
      void loadSelectedShipment(selectedShipmentId);
    }, 0);
    return () => clearTimeout(delayed);
  }, [selectedShipmentId, loadSelectedShipment]);

  const filteredShipments = useMemo(() => {
    const needle = search.trim().toLowerCase();
    const base = shipments.filter((shipment) => {
      if (showOnlyActive && shipment.status === 'DELIVERED') return false;
      if (statusFilter !== 'ALL' && shipment.status !== statusFilter) return false;
      if (!needle) return true;
      return (
        shipment.shipmentCode.toLowerCase().includes(needle) ||
        shipment.originIcao.toLowerCase().includes(needle) ||
        shipment.destinationIcao.toLowerCase().includes(needle) ||
        shipment.airlineName.toLowerCase().includes(needle)
      );
    });

    if (statusFilter !== 'ALL') {
      return base;
    }

    const priority = (s: ShipmentStatus): number => {
      if (s === 'IN_ROUTE') return 0;
      if (s === 'PENDING') return 1;
      if (s === 'CRITICAL' || s === 'DELAYED') return 2;
      return 3;
    };
    return [...base].sort((a, b) => {
      const p = priority(a.status) - priority(b.status);
      if (p !== 0) return p;
      return b.id - a.id;
    });
  }, [shipments, search, showOnlyActive, statusFilter]);

  const visibleShipments = useMemo(
    () => filteredShipments.slice(0, MAX_MAP_SHIPMENTS),
    [filteredShipments]
  );

  useEffect(() => {
    filteredShipmentsRef.current = filteredShipments;
  }, [filteredShipments]);

  const markerGeoJson = useMemo(() => {
    return {
      type: 'FeatureCollection' as const,
      features: filteredShipments
        .map((shipment) => {
          const smooth = smoothedPositions[shipment.id];
          const longitude = smooth ? smooth.longitude : shipment.currentLongitude;
          const latitude = smooth ? smooth.latitude : shipment.currentLatitude;
          return pointFeature(longitude, latitude, {
            id: shipment.id,
            code: shipment.shipmentCode,
            status: shipment.status,
            color: markerColor(shipment),
            selected: shipment.id === selectedShipmentId,
          });
        })
        .filter(
          (feature) =>
            Number.isFinite(feature.geometry.coordinates[0]) &&
            Number.isFinite(feature.geometry.coordinates[1])
        ),
    };
  }, [filteredShipments, selectedShipmentId, smoothedPositions]);

  const routeData = useMemo(() => routeLayers(selectedStops), [selectedStops]);
  const selectedShipment = useMemo(
    () => shipments.find((shipment) => shipment.id === selectedShipmentId) ?? null,
    [shipments, selectedShipmentId]
  );

  const selectedSmoothed = useMemo(() => {
    if (!selectedShipment) return null;
    const point = smoothedPositions[selectedShipment.id];
    return point ?? {
      longitude: selectedShipment.currentLongitude,
      latitude: selectedShipment.currentLatitude,
    };
  }, [selectedShipment, smoothedPositions]);

  const planePosition = useMemo(() => {
    if (!selectedShipment || !selectedSmoothed || !routeData) return null;

    const unwrappedPoint = {
      longitude: nearestWrappedLongitude(selectedSmoothed.longitude, routeData.referenceLongitude),
      latitude: selectedSmoothed.latitude,
    };
    const snapped = snapPointToPolyline(unwrappedPoint, routeData.routeNodes);
    const activeToNode = routeData.routeNodes[Math.min(routeData.activeToIdx, routeData.routeNodes.length - 1)] ?? routeData.routeNodes[routeData.routeNodes.length - 1];
    const bearing = bearingToDestination(snapped, {
      longitude: activeToNode[0],
      latitude: activeToNode[1],
    });

    return {
      longitude: normalizeLongitude(snapped.longitude),
      latitude: snapped.latitude,
      bearing,
    };
  }, [selectedShipment, selectedSmoothed, routeData]);

  const planeTrailGeoJson = useMemo(() => {
    if (!routeData || !planePosition) {
      return { type: 'FeatureCollection' as const, features: [] };
    }
    const planePoint: [number, number] = [planePosition.longitude, planePosition.latitude];
    const nodes = routeData.routeNodes;
    let bestSeg = 0;
    let bestDist = Number.POSITIVE_INFINITY;
    for (let i = 1; i < nodes.length; i++) {
      const from = { longitude: nodes[i - 1][0], latitude: nodes[i - 1][1] };
      const to = { longitude: nodes[i][0], latitude: nodes[i][1] };
      const proj = projectPointToSegment({ longitude: planePoint[0], latitude: planePoint[1] }, from, to);
      const d = distanceSq({ longitude: planePoint[0], latitude: planePoint[1] }, proj);
      if (d < bestDist) { bestDist = d; bestSeg = i; }
    }
    const trailCoords: Array<[number, number]> = [];
    for (let i = 0; i <= bestSeg - 1; i++) trailCoords.push(nodes[i]);
    trailCoords.push(planePoint);
    if (trailCoords.length < 2) return { type: 'FeatureCollection' as const, features: [] };
    return {
      type: 'FeatureCollection' as const,
      features: [lineFeature(trailCoords, { state: 'trail' })],
    };
  }, [routeData, planePosition]);

  const flightsLive = useMemo<FlightLive[]>(() => {
    const shipmentsIndex = new globalThis.Map<number, ShipmentSummary>();
    for (const shipment of shipments) {
      shipmentsIndex.set(shipment.id, shipment);
    }

    return mapLive
      .slice(0, MAX_FLIGHT_ANIMATION)
      .map((liveRow) => {
        const shipment: ShipmentSummary = shipmentsIndex.get(liveRow.shipmentId) ?? {
          id: liveRow.shipmentId,
          shipmentCode: liveRow.shipmentCode,
          status: 'IN_ROUTE' as ShipmentStatus,
          originIcao: liveRow.originIcao,
          destinationIcao: liveRow.destinationIcao,
          airlineName: '',
          originLatitude: liveRow.originLatitude,
          originLongitude: liveRow.originLongitude,
          destinationLatitude: liveRow.nextLatitude,
          destinationLongitude: liveRow.nextLongitude,
          currentLatitude: liveRow.currentLatitude,
          currentLongitude: liveRow.currentLongitude,
          progressPct: liveRow.progressPct,
          overdue: false,
          atRisk: false,
          criticalReason: null,
          lastVisitedNode: liveRow.originIcao,
          remainingTime: '',
        };
        const current: GeoPoint = {
          longitude: liveRow.currentLongitude ?? shipment.currentLongitude,
          latitude: liveRow.currentLatitude ?? shipment.currentLatitude,
        };
        const destinationWrapped: GeoPoint = {
          longitude: nearestWrappedLongitude(liveRow.nextLongitude, current.longitude),
          latitude: liveRow.nextLatitude,
        };

        const fallbackBearing = lastBearingRef.current[shipment.id] ?? bearingToDestination(current, destinationWrapped);
        const rawBearing = bearingToDestination(current, destinationWrapped);
        const delta = ((((rawBearing - fallbackBearing) % 360) + 540) % 360) - 180;
        const bearing = ((fallbackBearing + Math.max(-60, Math.min(60, delta))) + 360) % 360;
        lastBearingRef.current[shipment.id] = bearing;

        return {
          shipment,
          current: {
            longitude: normalizeLongitude(current.longitude),
            latitude: current.latitude,
          },
          next: {
            longitude: normalizeLongitude(destinationWrapped.longitude),
            latitude: destinationWrapped.latitude,
          },
          bearing,
        };
      })
      .filter((entry): entry is FlightLive => entry !== null);
  }, [mapLive, shipments]);

  const networkRouteFeatures = useMemo(() => {
    const features = networkEdges
      .map((edge) => {
        const lon1 = edge.originLongitude;
        const lon2 = nearestWrappedLongitude(edge.destinationLongitude, lon1);
        return lineFeature(
          [
            [lon1, edge.originLatitude],
            [lon2, edge.destinationLatitude],
          ],
          {
            operational: edge.operational,
            suspended: edge.suspended,
            scheduledCount: edge.scheduledCount,
            inFlightCount: edge.inFlightCount,
            cancelledCount: edge.cancelledCount,
          }
        );
      })
      .filter((feature) => feature.geometry.coordinates.length >= 2);

    return {
      type: 'FeatureCollection' as const,
      features,
    };
  }, [networkEdges]);

  const networkNodes = useMemo(() => {
    const index = new globalThis.Map<string, { icao: string; latitude: number; longitude: number }>();
    for (const edge of networkEdges) {
      if (!index.has(edge.originIcao)) {
        index.set(edge.originIcao, {
          icao: edge.originIcao,
          latitude: edge.originLatitude,
          longitude: edge.originLongitude,
        });
      }
      if (!index.has(edge.destinationIcao)) {
        index.set(edge.destinationIcao, {
          icao: edge.destinationIcao,
          latitude: edge.destinationLatitude,
          longitude: edge.destinationLongitude,
        });
      }
    }
    return [...index.values()];
  }, [networkEdges]);

  const occupancyNodes = useMemo(
    () => airportNodes
      .filter((airport) => Number.isFinite(airport.latitude) && Number.isFinite(airport.longitude)),
    [airportNodes]
  );

  async function onNodeClick(icao: string, point: GeoPoint, date?: string): Promise<void> {
    try {
      const detail = await dashboardApi.getNodeDetail(icao, date ?? nodeAgendaDate);
      setSelectedNodePopup({ detail, point });
    } catch {
      setSelectedNodePopup(null);
    }
  }

  const flightsRouteGeoJson = useMemo(() => {
    const features = mapLive
      .map((row) => {
        const coords: Array<[number, number]> = [];
        if (row.originLongitude != null && row.originLatitude != null) {
          coords.push([row.originLongitude, row.originLatitude]);
        }
        coords.push([row.currentLongitude, row.currentLatitude]);
        coords.push([row.nextLongitude, row.nextLatitude]);
        return lineFeature(coords, { id: row.shipmentId });
      })
      .filter((feature) => feature.geometry.coordinates.length >= 2);
    return {
      type: 'FeatureCollection' as const,
      features,
    };
  }, [mapLive]);

  const selectedFlightLive = useMemo(
    () => (showShipmentFlights
      ? flightsLive.find((flight) => flight.shipment.id === selectedShipmentId) ?? null
      : null),
    [flightsLive, selectedShipmentId, showShipmentFlights]
  );

  const alertCount = systemStatus?.alertaAirports ?? 0;
  const criticalCount = systemStatus?.criticoAirports ?? 0;
  const normalCount = systemStatus?.normalAirports ?? 0;
  const nodesAvailability = overview?.availableNodesPct ?? 0;
  const unresolvedAlerts = overview?.unresolvedAlerts ?? 0;
  const simNowText = displayedSimTime;
  const scenarioLabel = (() => {
    if (!sim?.scenario) return 'Sin escenario';
    if (sim.scenario === 'PERIOD_SIMULATION') return 'Por periodo';
    if (sim.scenario === 'COLLAPSE_TEST') return 'Prueba de colapso';
    return 'Dia a dia';
  })();
  return (
    <div className="app-page">
      <header className="page-head dashboard-head">
        <div>
          <h1 className="page-head-title">Panel de Control</h1>
          <p className="page-head-subtitle">Monitoreo operativo en tiempo real</p>
        </div>

        <div className="dashboard-head-controls">
          <button className="btn btn-primary" onClick={onStart} disabled={isTransitioning || Boolean(sim?.running && !sim?.paused)}>
            {isTransitioning ? 'Procesando...' : sim?.running && !sim?.paused ? 'En ejecucion' : 'Iniciar'}
          </button>
          <button className="btn btn-danger" onClick={onStop} disabled={isTransitioning || (!sim?.running && !sim?.paused)}>Detener</button>
          <button className="btn btn-neutral" onClick={onPause} disabled={isTransitioning || !sim?.running}>Pausar</button>
          {[1, 5, 10, 20].map((speed) => (
            <button
              key={speed}
              className={`chip ${sim?.speed === speed ? 'is-active' : ''}`}
              onClick={() => onSetSpeed(speed)}
            >
              {speed}x
            </button>
          ))}
          {simNowText ? <span className="chip">SimTime {simNowText}</span> : null}
          <span className="chip is-active">Escenario {scenarioLabel}</span>
        </div>
      </header>

      <div className="dashboard-grid">
        <aside className="surface-panel dashboard-left">
          <div className="dashboard-left-head">
            <p>Envios</p>
            <span className="chip is-active">{filteredShipments.length}</span>
          </div>

            <div className="dashboard-filters">
              <p className="filter-block-title">Busqueda rapida</p>
              <input
                value={search}
                onChange={(event) => setSearch(event.target.value)}
                placeholder="Codigo, origen, destino, aerolinea"
                className="dashboard-search"
              />

              <div className="dashboard-status-tabs">
                {(['ALL', 'IN_ROUTE', 'PENDING', 'CRITICAL'] as const).map((status) => (
                  <button
                    key={status}
                    className={`chip${statusFilter === status ? ' is-active' : ''}`}
                    onClick={() => setStatusFilter(status)}
                  >
                    {status === 'ALL' ? 'Todos' : statusLabel(status)}
                  </button>
                ))}
              </div>

              <div className="filter-actions">
                <label className="chip" style={{ cursor: 'pointer' }}>
                  <input
                    type="checkbox"
                    checked={showOnlyActive}
                    onChange={(event) => setShowOnlyActive(event.target.checked)}
                    style={{ accentColor: '#5f82ff' }}
                  />
                  Solo envios activos
                </label>
              </div>
            </div>

          <div className="dashboard-shipment-list">
            {loading ? (
              <div className="state-panel" style={{ margin: 12 }}>
                <p className="state-panel-title">Cargando envios</p>
                <p className="state-panel-copy">Actualizando el flujo operativo desde el simulador.</p>
              </div>
            ) : filteredShipments.length === 0 ? (
              <div className="state-panel" style={{ margin: 12 }}>
                <p className="state-panel-title">Sin envios para mostrar</p>
                <p className="state-panel-copy">Cambia los filtros o desactiva Solo envios activos.</p>
              </div>
            ) : (
              visibleShipments.map((shipment) => (
                <button
                  key={shipment.id}
                  className={`dashboard-shipment-item${selectedShipmentId === shipment.id ? ' is-selected' : ''}`}
                  onClick={() => focusShipment(shipment.id)}
                >
                  <div>
                    <p className="shipment-code">{shipment.shipmentCode}</p>
                    <p className="shipment-route">
                      x{Math.max(1, Math.round(shipment.progressPct / 10))} maletas | {shipment.originIcao}{' -> '}{shipment.destinationIcao}
                    </p>
                  </div>
                  <span className={`status-badge ${
                    shipment.status === 'CRITICAL' || shipment.status === 'DELAYED'
                      ? 'status-critico'
                      : shipment.status === 'PENDING'
                        ? 'status-neutral'
                        : 'status-normal'
                  }`}
                    title={shipment.status === 'CRITICAL' || shipment.status === 'DELAYED'
                      ? criticalLabel(shipment.criticalReason)
                      : undefined}
                  >
                    {statusLabel(shipment.status)}
                  </span>
                </button>
              ))
            )}

            {!loading && filteredShipments.length > visibleShipments.length ? (
              <div style={{ padding: 12 }}>
                <p className="state-panel-copy">Mostrando {MAX_MAP_SHIPMENTS} de {filteredShipments.length}. Usa filtros para acotar.</p>
              </div>
            ) : null}
          </div>
        </aside>

        <section className="dashboard-main">
          <div className="dashboard-map-toolbar">
            <button
              className={`chip${mapMode === 'MAP' ? ' is-active' : ''}`}
              onClick={() => setMapMode('MAP')}
            >
              Mapa
            </button>
            <button
              className={`chip${mapMode === 'SATELLITE' ? ' is-active' : ''}`}
              onClick={() => setMapMode('SATELLITE')}
            >
              Satelite
            </button>
            <button className={`chip${showNetworkLayer ? ' is-active' : ''}`} onClick={() => setShowNetworkLayer((value) => !value)}>
              Rutas programadas {showNetworkLayer ? 'ON' : 'OFF'}
            </button>
            <button className={`chip${showShipmentFlights ? ' is-active' : ''}`} onClick={() => setShowShipmentFlights((value) => !value)}>
              Aviones de envios {showShipmentFlights ? 'ON' : 'OFF'}
            </button>
            <button className={`chip${showOnlyActive ? ' is-active' : ''}`} onClick={() => setShowOnlyActive((value) => !value)}>
              Entregados {showOnlyActive ? 'OFF' : 'ON'}
            </button>
            <span className="chip dashboard-live-pill" title="Vuelos en ruta actualmente visibles en el mapa">
              Vuelos live {mapLive.length}
            </span>
            <span className="chip" title="Hora local de la ultima actualizacion del feed live">
              Actualizado {lastLiveRefresh}
            </span>
          </div>

          <div className="surface-panel dashboard-map-wrap">
            <Map
              ref={mapRef}
              mapStyle={mapMode === 'MAP' ? MAP_STYLE : SATELLITE_STYLE}
              reuseMaps
              renderWorldCopies={false}
              onClick={() => {
                setSelectedShipmentId(null);
                setSelectedNodePopup(null);
              }}
              style={{ width: '100%', height: '100%' }}
              initialViewState={INITIAL_VIEW_STATE}
            >
              <NavigationControl position="top-left" />

              {showNetworkLayer && networkRouteFeatures.features.length > 0 ? (
                <Source id="network-routes" type="geojson" data={networkRouteFeatures}>
                  <Layer
                    id="network-routes-layer"
                    type="line"
                    paint={{
                      'line-color': [
                        'case',
                        ['get', 'suspended'], '#ff6f7b',
                        '#5f82ff',
                      ],
                      'line-width': 1.2,
                      'line-opacity': 0.35,
                      'line-dasharray': [
                        'case',
                        ['get', 'suspended'], ['literal', [2, 2]],
                        ['literal', [1, 0]],
                      ],
                    }}
                  />
                </Source>
              ) : null}

              {showNetworkLayer && networkNodes.map((node) => (
                <Marker
                  key={`network-node-${node.icao}`}
                  longitude={node.longitude}
                  latitude={node.latitude}
                  anchor="center"
                  style={{ zIndex: 2 }}
                  onClick={(event) => {
                    event.originalEvent.stopPropagation();
                    void onNodeClick(node.icao, { longitude: node.longitude, latitude: node.latitude }, nodeAgendaDate);
                  }}
                >
                  <button className="network-node-marker" title={`Nodo ${node.icao}`} />
                </Marker>
              ))}

              {occupancyNodes.map((airport) => (
                <Marker
                  key={`occupancy-node-${airport.id}`}
                  longitude={airport.longitude}
                  latitude={airport.latitude}
                  anchor="center"
                  style={{ zIndex: 1 }}
                  onClick={(event) => {
                    event.originalEvent.stopPropagation();
                    void onNodeClick(airport.icaoCode, { longitude: airport.longitude, latitude: airport.latitude }, nodeAgendaDate);
                  }}
                >
                  <button
                    className={`airport-occupancy-marker ${airport.status === 'CRITICO' ? 'is-critical' : airport.status === 'ALERTA' ? 'is-warning' : 'is-normal'}`}
                    title={`${airport.icaoCode} ${airport.occupancyPct.toFixed(1)}%`}
                  />
                </Marker>
              ))}

              {showShipmentFlights && flightsRouteGeoJson.features.length > 0 ? (
                <Source id="flights-live-routes" type="geojson" data={flightsRouteGeoJson}>
                  <Layer
                    id="flights-live-routes-line"
                    type="line"
                    paint={{
                      'line-color': '#8eaaff',
                      'line-width': 1.4,
                      'line-opacity': 0.5,
                      'line-dasharray': [3, 2],
                    }}
                  />
                </Source>
              ) : null}

              {routeData && routeData.pendingGeoJson.features.length > 0 ? (
                <Source id="pending-route" type="geojson" data={routeData.pendingGeoJson}>
                  <Layer
                    id="pending-route-line"
                    type="line"
                    paint={{
                      'line-color': '#8899bb',
                      'line-width': 1.8,
                      'line-opacity': 0.45,
                      'line-dasharray': [4, 3],
                    }}
                  />
                </Source>
              ) : null}

              {planeTrailGeoJson.features.length > 0 ? (
                <Source id="plane-trail" type="geojson" data={planeTrailGeoJson}>
                  <Layer
                    id="plane-trail-line"
                    type="line"
                    paint={{
                      'line-color': '#4a9eff',
                      'line-width': 2.5,
                      'line-opacity': 0.75,
                    }}
                  />
                </Source>
              ) : null}

              {routeData && routeData.doneGeoJson.features.length > 0 ? (
                <Source id="done-route" type="geojson" data={routeData.doneGeoJson}>
                  <Layer
                    id="done-route-line"
                    type="line"
                    paint={{
                      'line-color': '#36d399',
                      'line-width': 2.5,
                      'line-opacity': 0.7,
                    }}
                  />
                </Source>
              ) : null}

              {showShipmentFlights ? flightsLive.map(({ shipment, current, bearing }): ReactElement => {
                const smooth = smoothedPositions[shipment.id];
                const pos = smooth ?? current;
                return (
                  <Marker
                    key={`flight-${shipment.id}`}
                    longitude={pos.longitude}
                    latitude={pos.latitude}
                    anchor="center"
                    style={{ zIndex: selectedShipmentId === shipment.id ? 8 : 6 }}
                    onClick={(event) => {
                      event.originalEvent.stopPropagation();
                      focusShipment(shipment.id);
                    }}
                  >
                    <button
                      className={`flight-radar-marker${selectedShipmentId === shipment.id ? ' is-selected' : ''}`}
                      style={{ borderColor: markerColor(shipment) }}
                      title={`${shipment.shipmentCode} · ${statusLabel(shipment.status)}`}
                    >
                      <span style={{ transform: `rotate(${bearing + PLANE_ICON_ROTATION_OFFSET_DEG}deg)` }}>✈</span>
                    </button>
                  </Marker>
                );
              }) : null}

              <Source id="shipment-points" type="geojson" data={markerGeoJson}>
                <Layer
                  id="shipment-halo"
                  type="circle"
                  paint={{
                    'circle-color': ['get', 'color'],
                    'circle-radius': ['case', ['get', 'selected'], 9, 6],
                    'circle-opacity': 0.18,
                    'circle-stroke-width': 0,
                  }}
                />
              </Source>


              {selectedNodePopup ? (
                <Popup
                  longitude={selectedNodePopup.point.longitude}
                  latitude={selectedNodePopup.point.latitude}
                  closeButton
                  closeOnClick={false}
                  anchor="top"
                  className="node-popup"
                  onClose={() => setSelectedNodePopup(null)}
                  offset={14}
                  style={{ zIndex: 30 }}
                >
                  <div className="map-popup-body">
                    <p className="map-popup-title">{selectedNodePopup.detail.icaoCode} · {selectedNodePopup.detail.city}</p>
                    <label className="map-popup-field">
                      <span>Agenda</span>
                      <input
                        type="date"
                        value={nodeAgendaDate}
                        onChange={(event) => {
                          const value = event.target.value;
                          setNodeAgendaDate(value);
                          void onNodeClick(selectedNodePopup.detail.icaoCode, selectedNodePopup.point, value);
                        }}
                      />
                    </label>
                    <p className="map-popup-line">Capacidad {selectedNodePopup.detail.currentStorageLoad}/{selectedNodePopup.detail.maxStorageCapacity}</p>
                    <p className="map-popup-line">Ocupacion {selectedNodePopup.detail.occupancyPct.toFixed(1)}%</p>
                    <p className="map-popup-line">Vuelos programados {selectedNodePopup.detail.scheduledFlights}</p>
                    <p className="map-popup-line">Envios almacenados {selectedNodePopup.detail.storedShipments}</p>
                    <div className="map-popup-agenda">
                      {(selectedNodePopup.detail.nextFlights ?? []).slice(0, 4).map((flight) => (
                        <p key={`${flight.flightCode}-${flight.departure}`} className="map-popup-line">
                          {flight.flightCode}: {flight.originIcao} {'->'} {flight.destinationIcao}
                        </p>
                      ))}
                      {(selectedNodePopup.detail.nextFlights ?? []).length === 0 ? (
                        <p className="map-popup-line">Sin vuelos para esta fecha.</p>
                      ) : null}
                    </div>
                  </div>
                </Popup>
              ) : null}

              {selectedFlightLive ? (
                <Popup
                  longitude={selectedFlightLive.current.longitude}
                  latitude={selectedFlightLive.current.latitude}
                  closeButton={false}
                  closeOnClick={false}
                  anchor="top"
                  className="shipment-popup"
                  offset={16}
                  style={{ zIndex: 30 }}
                >
                  <div className="map-popup-body">
                    <p className="map-popup-title">{selectedFlightLive.shipment.shipmentCode}</p>
                    <p className="map-popup-line">Origen {selectedFlightLive.shipment.originIcao}</p>
                    <p className="map-popup-line">Destino {selectedFlightLive.shipment.destinationIcao}</p>
                    <p className="map-popup-line">Estado {statusLabel(selectedFlightLive.shipment.status)}</p>
                    {selectedFlightLive.shipment.criticalReason ? (
                      <p className="map-popup-line">Motivo {criticalLabel(selectedFlightLive.shipment.criticalReason)}</p>
                    ) : null}
                    <p className="map-popup-line">Ultimo nodo {selectedFlightLive.shipment.lastVisitedNode || 'N/A'}</p>
                    <p className="map-popup-line">Plazo restante {selectedFlightLive.shipment.remainingTime || 'N/A'}</p>
                  </div>
                </Popup>
              ) : null}
            </Map>

          </div>

          <div className="dashboard-kpis">
            <article className="surface-panel kpi-card">
              <p>Vuelos Activos</p>
              <strong>{loading ? '-' : (overview?.totalActiveFlights ?? 0)}</strong>
              <small className="kpi-meta">
                <span className="kpi-dot kpi-dot-ok" />
                +{overview?.shipmentsInRoute ?? 0} en transito
              </small>
            </article>
            <article className="surface-panel kpi-card">
              <p>Envios en Ruta</p>
              <strong>{loading ? '-' : (overview?.shipmentsInRoute ?? 0)}</strong>
              <small className="kpi-meta">
                <span className="kpi-dot kpi-dot-ok" />
                de {overview?.totalShipmentsToday ?? 0} total
              </small>
            </article>
            <article className="surface-panel kpi-card">
              <p>Alertas pendientes</p>
              <strong>{loading ? '-' : unresolvedAlerts}</strong>
              <small className="kpi-meta">
                <span className={`kpi-dot ${unresolvedAlerts === 0 ? 'kpi-dot-ok' : unresolvedAlerts < 5 ? 'kpi-dot-warn' : 'kpi-dot-bad'}`} />
                {activeAlerts.length} visibles
              </small>
            </article>
            <article className="surface-panel kpi-card">
              <p>Nodos disponibles</p>
              <strong>{loading ? '-' : `${(overview?.availableNodesPct ?? 0).toFixed(1)}%`}</strong>
              <small className="kpi-meta">
                <span className={`kpi-dot ${nodesAvailability >= 90 ? 'kpi-dot-ok' : nodesAvailability >= 70 ? 'kpi-dot-warn' : 'kpi-dot-bad'}`} />
                normal {normalCount} / alerta {alertCount} / critico {criticalCount}
              </small>
            </article>
          </div>

          {error ? (
            <div className="state-panel is-error">
              <p className="state-panel-title">Error de conexion</p>
              <p className="state-panel-copy">{error}</p>
            </div>
          ) : null}
        </section>
      </div>
    </div>
  );
}
