'use client';

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { airportsApi } from './api/airportsApi';
import { dashboardApi } from './api/dashboardApi';
import { flightsApi } from './api/flightsApi';
import { simulationApi } from './api/simulationApi';
import type {
  Airport,
  CollapseRisk,
  DashboardOverview,
  Flight,
  MapLiveFlight,
  MapLiveShipment,
  SimulationState,
  SystemStatus,
} from './types';

// Intervalos de polling. El reloj mostrado se EXTRAPOLA en cliente entre polls, así que bajar la
// frecuencia no resta fluidez al reloj pero sí recorta re-renders del dashboard (mapa incluido) y
// tráfico → la UI deja de sentirse trabada en cada click / cambio de velocidad.
const SIM_POLL_MS = 2_000;
const MAP_POLL_MS = 2_000;
const OVERVIEW_POLL_MS = 2_500;
const SYSTEM_POLL_MS = 6_000;
const AIRPORTS_POLL_MS = 30_000;
const UPCOMING_POLL_MS = 3_000;

function toIsoDate(value: string | null | undefined): string | null {
  if (!value) return null;
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return null;
  return date.toISOString().slice(0, 10);
}

function shiftIsoDate(date: string, deltaDays: number): string {
  const value = new Date(`${date}T00:00:00Z`);
  value.setUTCDate(value.getUTCDate() + deltaDays);
  return value.toISOString().slice(0, 10);
}

function normalizeAirportsForMode(airports: Airport[], mode: 'live' | 'sim'): Airport[] {
  if (mode !== 'live') return airports;
  return airports.map((airport) => ({
    ...airport,
    currentStorageLoad: 0,
    availableCapacity: airport.maxStorageCapacity,
    occupancyPct: 0,
    status: 'SIN_USO',
  }));
}

function periodEndMs(state: SimulationState | null): number | null {
  if (state?.periodEndAt) {
    const explicitEnd = new Date(state.periodEndAt).getTime();
    return Number.isNaN(explicitEnd) ? null : explicitEnd;
  }
  if (!state?.effectiveScenarioStartAt || state.scenario !== 'PERIOD_SIMULATION') {
    return null;
  }
  const start = new Date(state.effectiveScenarioStartAt).getTime();
  if (Number.isNaN(start)) return null;
  const days = Math.max(1, state.simulationDays || 1);
  return start + days * 24 * 60 * 60 * 1000;
}

function planningFrontierMs(state: SimulationState | null): number | null {
  if (!state || (state.scenario !== 'PERIOD_SIMULATION' && state.scenario !== 'COLLAPSE_TEST')) {
    return periodEndMs(state);
  }
  const end = periodEndMs(state);
  const plannedThrough = state.periodPlannedThrough ? new Date(state.periodPlannedThrough).getTime() : Number.NaN;
  const frontier = Number.isFinite(plannedThrough) ? plannedThrough : null;
  if (end == null) return frontier;
  if (frontier == null) return end;
  return Math.min(end, frontier);
}

interface SimulationCtx {
  /** Current simulation state from backend (null only before first successful poll). */
  sim: SimulationState | null;
  /** True after the first successful poll — use to distinguish "loading" from "no sim". */
  loaded: boolean;
  /** Push an updated state (e.g. after start/stop/pause actions). */
  setSim: (next: SimulationState) => void;
  /** Force an immediate poll of the backend state. */
  refresh: () => Promise<SimulationState | null>;

  // --- Live operational data shared across pages so navigation never shows empty state ---
  overview: DashboardOverview | null;
  system: SystemStatus | null;
  collapseRisk: CollapseRisk | null;
  airports: Airport[];
  mapLive: MapLiveShipment[];
  mapLiveFlights: MapLiveFlight[];
  upcomingFlights: Flight[];

  /** Projected simulation time on the client (extrapolated every 250 ms). */
  simulatedNowMs: number | null;
  simulatedClockWaitingForPlanning: boolean;
  simulatedClockPlannedThroughMs: number | null;
}

const ctx = createContext<SimulationCtx>({
  sim: null,
  loaded: false,
  setSim: () => {},
  refresh: async () => null,
  overview: null,
  system: null,
  collapseRisk: null,
  airports: [],
  mapLive: [],
  mapLiveFlights: [],
  upcomingFlights: [],
  simulatedNowMs: null,
  simulatedClockWaitingForPlanning: false,
  simulatedClockPlannedThroughMs: null,
});

export function SimulationProvider({ children, mode = 'live' }: { children: ReactNode; mode?: 'live' | 'sim' }) {
  const [sim, setSimRaw] = useState<SimulationState | null>(null);
  const [loaded, setLoaded] = useState(false);
  const simRef = useRef(sim);

  const [overview, setOverview] = useState<DashboardOverview | null>(null);
  const [system, setSystem] = useState<SystemStatus | null>(null);
  const [collapseRisk, setCollapseRisk] = useState<CollapseRisk | null>(null);
  const [airports, setAirports] = useState<Airport[]>([]);
  const [mapLive, setMapLive] = useState<MapLiveShipment[]>([]);
  const [mapLiveFlights, setMapLiveFlights] = useState<MapLiveFlight[]>([]);
  const [upcomingFlights, setUpcomingFlights] = useState<Flight[]>([]);
  const [simulatedNowMs, setSimulatedNowMs] = useState<number | null>(null);
  const [simulatedClockWaitingForPlanning, setSimulatedClockWaitingForPlanning] = useState(false);

  const setSim = useCallback((next: SimulationState) => {
    simRef.current = next;
    setSimRaw(next);
    setLoaded(true);
  }, []);

  const refresh = useCallback(async (): Promise<SimulationState | null> => {
    try {
      const state = await simulationApi.getState(mode);
      setSim(state);
      return state;
    } catch {
      return simRef.current;
    }
  }, [setSim, mode]);

  // -- Simulation state polling (1s) ---------------------------------------------------
  useEffect(() => {
    void refresh();
    const timer = setInterval(() => void refresh(), SIM_POLL_MS);
    return () => clearInterval(timer);
  }, [refresh]);

  const simActive = Boolean(sim?.running || sim?.paused);
  const simRunning = Boolean(sim?.running && !sim?.paused);

  // -- System status (8s) ---------------------------------------------------------------
  useEffect(() => {
    if (!simActive) {
      setSystem(null);
      setCollapseRisk(null);
      return;
    }
    if (!simRunning) return;
    let cancelled = false;
    const load = async () => {
      try {
        const [nextSystem, nextRisk] = await Promise.all([
          dashboardApi.getSystemStatus(mode),
          simulationApi.getCollapseRisk(),
        ]);
        if (!cancelled) {
          setSystem(nextSystem);
          setCollapseRisk(nextRisk);
        }
      } catch {
        if (!cancelled) {
          setCollapseRisk((prev) => prev ?? { risk: 0, bottlenecks: [], estimatedHoursToCollapse: -1, systemLoadPct: 0 });
        }
      }
    };
    void load();
    const timer = setInterval(load, SYSTEM_POLL_MS);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [simRunning, mode]);

  // -- Airports catalog (30s — rarely changes) ------------------------------------------
  useEffect(() => {
    let cancelled = false;
    const load = async () => {
      try {
        const next = await airportsApi.getAll();
        if (!cancelled) setAirports(normalizeAirportsForMode(next, mode));
      } catch {
        /* keep last */
      }
    };
    void load();
    const timer = setInterval(load, AIRPORTS_POLL_MS);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [mode]);

  // -- Overview (3s when sim is active; keeps last value across pauses / nav) -----------
  useEffect(() => {
    if (!simActive || !simRunning) return;
    let cancelled = false;
    const load = async () => {
      try {
        const next = await dashboardApi.getOverview(mode);
        if (!cancelled) setOverview(next);
      } catch {
        /* keep last */
      }
    };
    void load();
    const timer = setInterval(load, OVERVIEW_POLL_MS);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [simRunning, mode]);

  // -- Map live (1s when sim is active) -------------------------------------------------
  useEffect(() => {
    if (!simActive) {
      setMapLive([]);
      setMapLiveFlights([]);
      return;
    }
    if (!simRunning) return;
    let cancelled = false;
    const load = async () => {
      try {
        const [shipments, flights] = await Promise.all([
          dashboardApi.getMapLive(undefined, mode),
          dashboardApi.getMapLiveFlights(undefined, mode),
        ]);
        if (cancelled) return;
        setMapLive(shipments);
        setMapLiveFlights(flights);
      } catch {
        /* keep last */
      }
    };
    void load();
    const timer = setInterval(load, MAP_POLL_MS);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [simRunning, mode]);

  const anchorDate = useMemo(
    () => toIsoDate(sim?.simulatedNow ?? sim?.effectiveScenarioStartAt),
    [sim?.simulatedNow, sim?.effectiveScenarioStartAt],
  );

  // -- Upcoming flights (next 20 SCHEDULED with scheduledDeparture >= simulatedNow) -----
  useEffect(() => {
    if (!simActive) {
      setUpcomingFlights([]);
      return;
    }
    if (!simRunning || !anchorDate) return;
    let cancelled = false;
    const load = async () => {
      try {
        const page = await flightsApi.search({
          status: 'SCHEDULED',
          date: anchorDate,
          page: 0,
          size: 40,
          sort: 'scheduledDeparture',
          direction: 'asc',
        });
        if (cancelled) return;
        const nowMs = sim?.simulatedNow ? new Date(sim.simulatedNow).getTime() : Date.now();
        const next = page.content.filter((flight) => {
          const dep = new Date(flight.scheduledDeparture).getTime();
          return Number.isFinite(dep) && dep >= nowMs - 60_000;
        }).slice(0, 20);
        setUpcomingFlights(next);
      } catch {
        /* keep last */
      }
    };
    void load();
    const timer = setInterval(load, UPCOMING_POLL_MS);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [simRunning, anchorDate, sim?.simulatedNow]);

  // -- Client-side simulated-clock extrapolation ----------------------------------------
  // Anchor uses client wall-clock (Date.now()) — NOT sim.lastTickAt — to avoid negative
  // elapsed values caused by client/server clock skew. Drift vs. server sim time is
  // detected and softly re-anchored when it exceeds tolerance.
  const clockAnchorRef = useRef<{ simMs: number; wallMs: number; secondsPerRealSecond: number } | null>(null);

  useEffect(() => {
    if (!sim?.running || sim?.paused || !sim.simulatedNow) {
      clockAnchorRef.current = null;
      setSimulatedNowMs(sim?.simulatedNow ? new Date(sim.simulatedNow).getTime() : null);
      return;
    }

    const simMs = new Date(sim.simulatedNow).getTime();
    if (Number.isNaN(simMs)) return;
    const maxSimMs = planningFrontierMs(sim);
    const boundedSimMs = maxSimMs == null ? simMs : Math.min(simMs, maxSimMs);
    const tickIntervalMs = Math.max(1, sim.tickIntervalMs ?? 1_000);
    const wallNow = Date.now();
    const factor = Math.max(1, sim.simulationSecondsPerTick ?? 1) * (1_000 / tickIntervalMs);
    const current = clockAnchorRef.current;

    if (!current || current.secondsPerRealSecond !== factor) {
      clockAnchorRef.current = { simMs: boundedSimMs, wallMs: wallNow, secondsPerRealSecond: factor };
      setSimulatedNowMs(boundedSimMs);
      return;
    }

    const extrapolated = current.simMs + (wallNow - current.wallMs) * factor;
    const boundedExtrapolated = maxSimMs == null ? extrapolated : Math.min(extrapolated, maxSimMs);
    const backendLeadMs = boundedSimMs - boundedExtrapolated;
    // Tolerance = one poll's worth of simulated time ± 1.5 s guard
    const tolerance = Math.max(1_500, factor * 1_500);
    if (backendLeadMs > tolerance) {
      clockAnchorRef.current = { simMs: boundedSimMs, wallMs: wallNow, secondsPerRealSecond: factor };
      setSimulatedNowMs(boundedSimMs);
    }
  }, [sim?.running, sim?.paused, sim?.simulatedNow, sim?.simulationSecondsPerTick, sim?.tickIntervalMs, sim?.periodPlannedThrough, sim?.periodEndAt]);

  useEffect(() => {
    if (!sim?.running || sim?.paused) return;
    const interval = setInterval(() => {
      const anchor = clockAnchorRef.current;
      if (!anchor) return;
      const elapsedMs = Date.now() - anchor.wallMs;
      const projected = anchor.simMs + elapsedMs * anchor.secondsPerRealSecond;
      const maxSimMs = planningFrontierMs(simRef.current);
      const boundedProjected = maxSimMs == null ? projected : Math.min(projected, maxSimMs);
      setSimulatedNowMs(boundedProjected);
      const current = simRef.current;
      const planAhead = current?.scenario === 'PERIOD_SIMULATION' || current?.scenario === 'COLLAPSE_TEST';
      setSimulatedClockWaitingForPlanning(Boolean(
        planAhead
        && current?.running
        && !current?.paused
        && (current?.periodPlanningBacklog ?? 0) > 0
        && maxSimMs != null
        && projected >= maxSimMs - 1,
      ));
    }, 250);
    return () => clearInterval(interval);
  }, [sim?.running, sim?.paused]);

  useEffect(() => {
    if (!sim?.running || sim.paused) {
      setSimulatedClockWaitingForPlanning(false);
      return;
    }
    const maxSimMs = planningFrontierMs(sim);
    const planAhead = sim.scenario === 'PERIOD_SIMULATION' || sim.scenario === 'COLLAPSE_TEST';
    setSimulatedClockWaitingForPlanning(Boolean(
      planAhead
      && sim.periodPlanningBacklog > 0
      && maxSimMs != null
      && simulatedNowMs != null
      && simulatedNowMs >= maxSimMs - 1,
    ));
  }, [sim, simulatedNowMs]);

  const value = useMemo<SimulationCtx>(() => ({
    sim,
    loaded,
    setSim,
    refresh,
    overview,
    system,
    collapseRisk,
    airports,
    mapLive,
    mapLiveFlights,
    upcomingFlights,
    simulatedNowMs,
    simulatedClockWaitingForPlanning,
    simulatedClockPlannedThroughMs: planningFrontierMs(sim),
  }), [
    sim,
    loaded,
    setSim,
    refresh,
    overview,
    system,
    collapseRisk,
    airports,
    mapLive,
    mapLiveFlights,
    upcomingFlights,
    simulatedNowMs,
    simulatedClockWaitingForPlanning,
  ]);

  return <ctx.Provider value={value}>{children}</ctx.Provider>;
}

export function useSimulation() {
  return useContext(ctx);
}
