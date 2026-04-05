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
const POLL_INTERVAL_MS = 12000;
const MAP_FILTERS_STORAGE_KEY = 'pdds-map-filters-v2';
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
  origin: GeoPoint;
  originIcao: string;
  destination: GeoPoint;
  destinationIcao: string;
  bearing: number;
};

function statusLabel(status: ShipmentStatus): string {
  if (status === 'IN_ROUTE') return 'Normal';
  if (status === 'PENDING') return 'Alerta';
  if (status === 'CRITICAL' || status === 'DELAYED') return 'Critico';
  if (status === 'DELIVERED') return 'Entregado';
  return status;
}

function markerColor(shipment: ShipmentSummary): string {
  if (shipment.overdue || shipment.status === 'CRITICAL' || shipment.status === 'DELAYED') return '#ff5a64';
  if (shipment.atRisk || shipment.status === 'PENDING') return '#f0c13a';
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

function snapPointToSegmentRange(
  point: GeoPoint,
  nodes: Array<[number, number]>,
  fromIdx: number,
  toIdx: number
) {
  if (nodes.length < 2) {
    return {
      point,
      segmentStart: point,
      segmentEnd: point,
    };
  }

  const safeFrom = Math.max(0, Math.min(nodes.length - 2, fromIdx));
  const safeTo = Math.max(safeFrom + 1, Math.min(nodes.length - 1, toIdx));

  let best = point;
  let bestDist = Number.POSITIVE_INFINITY;
  let bestStart = { longitude: nodes[safeFrom][0], latitude: nodes[safeFrom][1] };
  let bestEnd = { longitude: nodes[safeTo][0], latitude: nodes[safeTo][1] };

  for (let i = safeFrom + 1; i <= safeTo; i++) {
    const start = { longitude: nodes[i - 1][0], latitude: nodes[i - 1][1] };
    const end = { longitude: nodes[i][0], latitude: nodes[i][1] };
    const projected = projectPointToSegmentMercator(point, start, end);
    const dist = mercatorDistanceSq(point, projected);
    if (dist < bestDist) {
      best = projected;
      bestDist = dist;
      bestStart = start;
      bestEnd = end;
    }
  }

  return {
    point: best,
    segmentStart: bestStart,
    segmentEnd: bestEnd,
  };
}

function mercatorY(lat: number): number {
  const clamped = Math.max(-85.05112878, Math.min(85.05112878, lat));
  const radians = (clamped * Math.PI) / 180;
  return Math.log(Math.tan(Math.PI / 4 + radians / 2));
}

function inverseMercatorY(y: number): number {
  return (2 * Math.atan(Math.exp(y)) - Math.PI / 2) * (180 / Math.PI);
}

function mercatorDistanceSq(a: GeoPoint, b: GeoPoint): number {
  const dx = a.longitude - b.longitude;
  const dy = mercatorY(a.latitude) - mercatorY(b.latitude);
  return dx * dx + dy * dy;
}

function projectPointToSegmentMercator(point: GeoPoint, from: GeoPoint, to: GeoPoint): GeoPoint {
  const py = mercatorY(point.latitude);
  const fy = mercatorY(from.latitude);
  const ty = mercatorY(to.latitude);

  const vx = to.longitude - from.longitude;
  const vy = ty - fy;
  const wx = point.longitude - from.longitude;
  const wy = py - fy;
  const denom = vx * vx + vy * vy;
  if (denom === 0) return { longitude: from.longitude, latitude: from.latitude };
  const t = clamp01((wx * vx + wy * vy) / denom);
  const projectedY = fy + vy * t;
  return {
    longitude: from.longitude + vx * t,
    latitude: inverseMercatorY(projectedY),
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
  const activeToIdx = transitIndex >= 1
    ? transitIndex
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
  const [searchCode, setSearchCode] = useState('');
  const [mapMode, setMapMode] = useState<MapMode>('MAP');
  const [showOnlyActive, setShowOnlyActive] = useState(true);
  const [statusFilter, setStatusFilter] = useState<MapFilter>('ALL');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selectedNodePopup, setSelectedNodePopup] = useState<{ detail: NodeDetail; point: GeoPoint } | null>(null);
  const [nodeAgendaDate, setNodeAgendaDate] = useState(() => new Date().toISOString().slice(0, 10));
  const [networkEdges, setNetworkEdges] = useState<RouteNetworkEdge[]>([]);
  const [airportNodes, setAirportNodes] = useState<Airport[]>([]);
  const [activeAlerts, setActiveAlerts] = useState<Array<{ id: number; shipmentCode: string; type: string; note: string }>>([]);
  const [showNetworkLayer, setShowNetworkLayer] = useState(true);
  const [showShipmentFlights, setShowShipmentFlights] = useState(true);
  const [smoothedPositions, setSmoothedPositions] = useState<Record<number, GeoPoint>>({});
  const previousTargetsRef = useRef<Record<number, GeoPoint>>({});
  const animationFrameRef = useRef<number | null>(null);
  const animationStartRef = useRef<number>(0);

  const [stopsByShipment, setStopsByShipment] = useState<Record<number, TravelStop[]>>({});

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
    window.localStorage.setItem(
      MAP_FILTERS_STORAGE_KEY,
      JSON.stringify({
        statusFilter,
        showOnlyActive,
      })
    );
  }, [showOnlyActive, statusFilter]);

  const loadDashboard = useCallback(async () => {
    const [sysRes, overviewRes, simRes, shipmentRes] = await Promise.allSettled([
      dashboardApi.getSystemStatus(),
      dashboardApi.getOverview(),
      simulationApi.getState(),
      dashboardApi.getShipmentSummaries({
        status: statusFilter === 'ALL' ? undefined : statusFilter,
      }),
    ]);

    if (sysRes.status === 'fulfilled') setSystemStatus(sysRes.value);
    if (overviewRes.status === 'fulfilled') setOverview(overviewRes.value);
    if (simRes.status === 'fulfilled') setSim(simRes.value);
      if (shipmentRes.status === 'fulfilled') {
        const sorted = shipmentRes.value.sort((a, b) => {
          if (a.status === 'DELIVERED' && b.status !== 'DELIVERED') return 1;
          if (a.status !== 'DELIVERED' && b.status === 'DELIVERED') return -1;
          return b.id - a.id;
        });
        setShipments(sorted);
      }

    const routesRes = await dashboardApi.getRoutesNetwork().catch(() => null);
    if (routesRes) setNetworkEdges(routesRes);
    const airportsRes = await airportsApi.getAll().catch(() => null);
    if (airportsRes) setAirportNodes(airportsRes);
    const alertsRes = await alertsApi.list().catch(() => null);
    if (alertsRes) {
      setActiveAlerts(alertsRes.map((a) => ({ id: a.id, shipmentCode: a.shipmentCode, type: a.type, note: a.note })));
    }

    if ([sysRes, overviewRes, simRes, shipmentRes].every((r) => r.status === 'rejected')) {
      setError('No se pudo conectar con el backend en puerto 8080.');
    } else {
      setError(null);
    }

    setLoading(false);
  }, [statusFilter]);

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

  useEffect(() => {
    return () => {
      if (animationFrameRef.current != null) {
        cancelAnimationFrame(animationFrameRef.current);
      }
    };
  }, []);

  useEffect(() => {
    const targets: Record<number, GeoPoint> = {};
    for (const shipment of shipments) {
      targets[shipment.id] = {
        longitude: shipment.currentLongitude,
        latitude: shipment.currentLatitude,
      };
    }

    const previous = previousTargetsRef.current;
    const startPositions: Record<number, GeoPoint> = {};
    for (const shipment of shipments) {
      const prev = previous[shipment.id];
      const target = targets[shipment.id];
      startPositions[shipment.id] = prev
        ? { longitude: prev.longitude, latitude: prev.latitude }
        : { longitude: target.longitude, latitude: target.latitude };
    }

    previousTargetsRef.current = targets;
    if (animationFrameRef.current != null) cancelAnimationFrame(animationFrameRef.current);

    animationStartRef.current = performance.now();

    const animate = (now: number) => {
      const t = clamp01((now - animationStartRef.current) / POLL_INTERVAL_MS);

      const next: Record<number, GeoPoint> = {};
      for (const shipment of shipments) {
        const from = startPositions[shipment.id];
        const to = targets[shipment.id];
        const wrappedToLon = nearestWrappedLongitude(to.longitude, from.longitude);
        const interpolatedLon = normalizeLongitude(lerp(from.longitude, wrappedToLon, t));
        next[shipment.id] = {
          longitude: interpolatedLon,
          latitude: lerp(from.latitude, to.latitude, t),
        };
      }

      setSmoothedPositions(next);

      if (t < 1) {
        animationFrameRef.current = requestAnimationFrame(animate);
      }
    };

    animationFrameRef.current = requestAnimationFrame(animate);
  }, [shipments]);

  async function onStart(): Promise<void> {
    try {
      await simulationApi.start();
      await loadDashboard();
    } catch {
      setError('No fue posible iniciar la simulacion.');
    }
  }

  async function onStop(): Promise<void> {
    try {
      await simulationApi.stop();
      await loadDashboard();
    } catch {
      setError('No fue posible detener la simulacion.');
    }
  }

  async function onPause(): Promise<void> {
    try {
      await simulationApi.pause();
      await loadDashboard();
    } catch {
      setError('No fue posible pausar la simulacion.');
    }
  }

  async function onRefreshDashboard(): Promise<void> {
    try {
      await loadDashboard();
    } catch {
      setError('No fue posible refrescar el panel.');
    }
  }

  async function onSearchByCode(): Promise<void> {
    const code = searchCode.trim().toUpperCase();
    if (!code) return;
    try {
      const result = await dashboardApi.searchShipmentByCode(code);
      setSelectedShipmentId(result.id);
      setSelectedNodePopup(null);
      setShowOnlyActive(false);
      mapRef.current?.easeTo({
        center: [result.currentLongitude, result.currentLatitude],
        zoom: Math.max(mapRef.current.getZoom(), 4),
        duration: 700,
      });
      await loadSelectedShipment(result.id);
      setError(null);
    } catch {
      setError(`No se encontro envio con codigo ${code}.`);
    }
  }

  useEffect(() => {
    const initial = setTimeout(() => {
      void loadDashboard();
    }, 0);
    const timer = setInterval(() => {
      void loadDashboard();
    }, POLL_INTERVAL_MS);
    return () => {
      clearTimeout(initial);
      clearInterval(timer);
    };
  }, [loadDashboard]);

  useEffect(() => {
    if (!selectedShipmentId) return;
    const delayed = setTimeout(() => {
      void loadSelectedShipment(selectedShipmentId);
    }, 0);
    return () => clearTimeout(delayed);
  }, [selectedShipmentId, loadSelectedShipment]);

  const filteredShipments = useMemo(() => {
    const needle = search.trim().toLowerCase();
    return shipments.filter((shipment) => {
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
  }, [shipments, search, showOnlyActive, statusFilter]);

  const activeShipmentIds = useMemo(
    () => filteredShipments
      .filter((shipment) => shipment.status !== 'DELIVERED')
      .map((shipment) => shipment.id),
    [filteredShipments]
  );

  useEffect(() => {
    let cancelled = false;
    if (activeShipmentIds.length === 0) {
      setTimeout(() => {
        if (!cancelled) {
          setStopsByShipment({});
        }
      }, 0);
      return;
    }

    void Promise.all(
      activeShipmentIds.map(async (id) => {
        try {
          const detail = await shipmentsApi.getById(id);
          return [id, detail.stops ?? []] as const;
        } catch {
          return [id, []] as const;
        }
      })
    ).then((entries) => {
      if (cancelled) return;
      const next: Record<number, TravelStop[]> = {};
      for (const [id, stops] of entries) {
        next[id] = [...stops];
      }
      setStopsByShipment(next);
    });

    return () => {
      cancelled = true;
    };
  }, [activeShipmentIds]);

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
      return {
        type: 'FeatureCollection' as const,
        features: [],
      };
    }

    return {
      type: 'FeatureCollection' as const,
      features: [lineFeature([
        routeData.routeNodes[0],
        [planePosition.longitude, planePosition.latitude],
      ], { state: 'trail' })],
    };
  }, [routeData, planePosition]);

  const routeDataByShipment = useMemo(() => {
    const index: Record<number, RouteData> = {};
    for (const shipment of filteredShipments) {
      const data = routeLayers(stopsByShipment[shipment.id] ?? []);
      if (data) {
        index[shipment.id] = data;
      }
    }
    return index;
  }, [filteredShipments, stopsByShipment]);

  const flightsLive = useMemo<FlightLive[]>(() => {
    return filteredShipments
      .filter((shipment) => shipment.status !== 'DELIVERED')
      .map((shipment) => {
        const smooth = smoothedPositions[shipment.id];
        const current = {
          longitude: smooth ? smooth.longitude : shipment.currentLongitude,
          latitude: smooth ? smooth.latitude : shipment.currentLatitude,
        };
        const stops = stopsByShipment[shipment.id] ?? [];
        const ordered = [...stops].sort((a, b) => a.stopOrder - b.stopOrder);

        const legIndex = ordered.findIndex(
          (stop) => stop.stopStatus === 'IN_TRANSIT' || stop.stopStatus === 'PENDING'
        );

        const legDestinationStop =
          legIndex >= 0 ? ordered[legIndex] : ordered.length > 0 ? ordered[ordered.length - 1] : null;
        const legOriginStop =
          legIndex > 0 ? ordered[legIndex - 1] : legDestinationStop && ordered.length > 1 ? ordered[ordered.length - 2] : null;

        const originPoint: GeoPoint = legOriginStop
          ? { longitude: legOriginStop.airportLongitude, latitude: legOriginStop.airportLatitude }
          : { longitude: shipment.originLongitude, latitude: shipment.originLatitude };
        const destinationPoint: GeoPoint = legDestinationStop
          ? { longitude: legDestinationStop.airportLongitude, latitude: legDestinationStop.airportLatitude }
          : { longitude: shipment.destinationLongitude, latitude: shipment.destinationLatitude };

        const originIcao = legOriginStop ? legOriginStop.airportIcaoCode : shipment.originIcao;
        const destinationIcao = legDestinationStop ? legDestinationStop.airportIcaoCode : shipment.destinationIcao;

        const route = routeDataByShipment[shipment.id];
        const currentWrapped = route
          ? {
            longitude: nearestWrappedLongitude(current.longitude, route.referenceLongitude),
            latitude: current.latitude,
          }
          : current;
        const snappedWithSegment = route
          ? snapPointToSegmentRange(
            currentWrapped,
            route.routeNodes,
            route.activeFromIdx,
            route.activeToIdx
          )
          : null;
        const snappedCurrent = snappedWithSegment ? snappedWithSegment.point : currentWrapped;

        const destinationWrapped = {
          longitude: nearestWrappedLongitude(destinationPoint.longitude, snappedCurrent.longitude),
          latitude: destinationPoint.latitude,
        };

        const bearing = bearingToDestination(snappedCurrent, destinationWrapped);

        return {
          shipment,
          current: {
            longitude: normalizeLongitude(snappedCurrent.longitude),
            latitude: snappedCurrent.latitude,
          },
          origin: originPoint,
          originIcao,
          destination: destinationPoint,
          destinationIcao,
          bearing,
        };
      });
  }, [filteredShipments, routeDataByShipment, smoothedPositions, stopsByShipment]);

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

  const allRoutes = useMemo(() => {
    return flightsLive
      .map(({ shipment }) => {
        const data = routeDataByShipment[shipment.id];
        return data ? { shipmentId: shipment.id, data } : null;
      })
      .filter((entry): entry is { shipmentId: number; data: RouteData } => entry !== null);
  }, [flightsLive, routeDataByShipment]);

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

  return (
    <div className="app-page">
      <header className="page-head dashboard-head">
        <div>
          <h1 className="page-head-title">Panel de Control</h1>
          <p className="page-head-subtitle">Monitoreo operativo en tiempo real</p>
        </div>

        <div className="dashboard-head-controls">
          <button className="btn btn-primary" onClick={onStart} disabled={Boolean(sim?.running && !sim?.paused)}>
            {sim?.running && !sim?.paused ? 'En ejecucion' : 'Iniciar'}
          </button>
          <button className="btn btn-danger" onClick={onStop} disabled={!sim?.running && !sim?.paused}>Detener</button>
          <button className="btn btn-neutral" onClick={onPause} disabled={!sim?.running}>Pausar</button>
          <button className="chip" onClick={onRefreshDashboard}>Refrescar</button>
          <input
            value={searchCode}
            onChange={(event) => setSearchCode(event.target.value)}
            placeholder="Buscar envio por codigo"
            className="dashboard-search"
            style={{ width: 220 }}
          />
          <button className="chip" onClick={onSearchByCode}>Buscar</button>
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
              filteredShipments.slice(0, 10).map((shipment) => (
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
                  >
                    {statusLabel(shipment.status)}
                  </span>
                </button>
              ))
            )}
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

              {allRoutes.flatMap(({ shipmentId, data }): ReactElement[] => {
                const selected = shipmentId === selectedShipmentId;
                const elements: ReactElement[] = [
                  <Source key={`done-source-${shipmentId}`} id={`route-done-${shipmentId}`} type="geojson" data={data.doneGeoJson}>
                    <Layer
                      id={`route-done-line-${shipmentId}`}
                      type="line"
                      paint={{
                        'line-color': selected ? '#62f0b7' : '#43d29d',
                        'line-width': selected ? 3.5 : 2.4,
                        'line-opacity': selected ? 0.95 : 0.55,
                      }}
                    />
                  </Source>,
                  <Source key={`pending-source-${shipmentId}`} id={`route-pending-${shipmentId}`} type="geojson" data={data.pendingGeoJson}>
                    <Layer
                      id={`route-pending-line-${shipmentId}`}
                      type="line"
                      paint={{
                        'line-color': selected ? '#8ea7ff' : '#5f82ff',
                        'line-width': selected ? 2.6 : 1.8,
                        'line-dasharray': [2, 2],
                        'line-opacity': selected ? 0.95 : 0.5,
                      }}
                    />
                  </Source>,
                ];

                if (selected) {
                  for (let index = 0; index < data.routeNodes.length; index++) {
                    const point = data.routeNodes[index];
                    elements.push(
                      <Marker key={`route-node-${shipmentId}-${index}`} longitude={point[0]} latitude={point[1]} anchor="center">
                        <span className={`route-node-marker${index === 0 ? ' is-origin' : index === data.routeNodes.length - 1 ? ' is-destination' : ''}`} />
                      </Marker>
                    );
                  }
                }

                return elements;
              })}

              {planeTrailGeoJson.features.length > 0 ? (
                <Source id="plane-trail" type="geojson" data={planeTrailGeoJson}>
                  <Layer
                    id="plane-trail-line"
                    type="line"
                    paint={{
                      'line-color': '#9ab3ff',
                      'line-width': 2,
                      'line-opacity': 0.6,
                    }}
                  />
                </Source>
              ) : null}

              {showShipmentFlights ? flightsLive.flatMap(({ shipment, current, origin, originIcao, destination, destinationIcao, bearing }): ReactElement[] => [
                <Marker
                  key={`origin-${shipment.id}`}
                  longitude={origin.longitude}
                  latitude={origin.latitude}
                  anchor="center"
                  style={{ zIndex: selectedShipmentId === shipment.id ? 6 : 4 }}
                  onClick={(event) => {
                    event.originalEvent.stopPropagation();
                    void onNodeClick(originIcao, origin, nodeAgendaDate);
                  }}
                >
                  <span className="airport-node airport-node-origin" title={`Origen ${originIcao}`} />
                </Marker>,
                <Marker
                  key={`destination-${shipment.id}`}
                  longitude={destination.longitude}
                  latitude={destination.latitude}
                  anchor="center"
                  style={{ zIndex: selectedShipmentId === shipment.id ? 7 : 5 }}
                  onClick={(event) => {
                    event.originalEvent.stopPropagation();
                    void onNodeClick(destinationIcao, destination, nodeAgendaDate);
                  }}
                >
                  <span className="airport-node airport-node-destination" title={`Destino ${destinationIcao}`} />
                </Marker>,
                <Marker
                  key={`flight-${shipment.id}`}
                  longitude={current.longitude}
                  latitude={current.latitude}
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
                    <span style={{ transform: `rotate(${bearing}deg)` }}>✈</span>
                  </button>
                </Marker>,
              ]) : null}

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
