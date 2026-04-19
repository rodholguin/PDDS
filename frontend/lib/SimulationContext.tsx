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

const SIM_POLL_MS = 2_000;
const MAP_POLL_MS = 3_000;
const OVERVIEW_POLL_MS = 6_000;
const SYSTEM_POLL_MS = 15_000;
const AIRPORTS_POLL_MS = 30_000;
const UPCOMING_POLL_MS = 10_000;

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
  flightCatalog: Map<number, Flight>;
  upcomingFlights: Flight[];

  /** Projected simulation time on the client (extrapolated every 250 ms). */
  simulatedNowMs: number | null;
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
  flightCatalog: new Map(),
  upcomingFlights: [],
  simulatedNowMs: null,
});

export function SimulationProvider({ children }: { children: ReactNode }) {
  const [sim, setSimRaw] = useState<SimulationState | null>(null);
  const [loaded, setLoaded] = useState(false);
  const simRef = useRef(sim);

  const [overview, setOverview] = useState<DashboardOverview | null>(null);
  const [system, setSystem] = useState<SystemStatus | null>(null);
  const [collapseRisk, setCollapseRisk] = useState<CollapseRisk | null>(null);
  const [airports, setAirports] = useState<Airport[]>([]);
  const [mapLive, setMapLive] = useState<MapLiveShipment[]>([]);
  const [mapLiveFlights, setMapLiveFlights] = useState<MapLiveFlight[]>([]);
  const [flightCatalog, setFlightCatalog] = useState<Map<number, Flight>>(() => new Map());
  const [upcomingFlights, setUpcomingFlights] = useState<Flight[]>([]);
  const [simulatedNowMs, setSimulatedNowMs] = useState<number | null>(null);

  const setSim = useCallback((next: SimulationState) => {
    simRef.current = next;
    setSimRaw(next);
    setLoaded(true);
  }, []);

  const refresh = useCallback(async (): Promise<SimulationState | null> => {
    try {
      const state = await simulationApi.getState();
      setSim(state);
      return state;
    } catch {
      return simRef.current;
    }
  }, [setSim]);

  // -- Simulation state polling (1s) ---------------------------------------------------
  useEffect(() => {
    void refresh();
    const timer = setInterval(() => void refresh(), SIM_POLL_MS);
    return () => clearInterval(timer);
  }, [refresh]);

  const simActive = Boolean(sim?.running || sim?.paused);

  // -- System status (8s) ---------------------------------------------------------------
  useEffect(() => {
    if (!simActive) {
      setSystem(null);
      setCollapseRisk(null);
      return;
    }
    let cancelled = false;
    const load = async () => {
      try {
        const [nextSystem, nextRisk] = await Promise.all([
          dashboardApi.getSystemStatus(),
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
  }, [simActive]);

  // -- Airports catalog (30s — rarely changes) ------------------------------------------
  useEffect(() => {
    let cancelled = false;
    const load = async () => {
      try {
        const next = await airportsApi.getAll();
        if (!cancelled) setAirports(next);
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
  }, []);

  // -- Overview (3s when sim is active; keeps last value across pauses / nav) -----------
  useEffect(() => {
    if (!simActive) return;
    let cancelled = false;
    const load = async () => {
      try {
        const next = await dashboardApi.getOverview();
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
  }, [simActive]);

  // -- Map live (1s when sim is active) -------------------------------------------------
  useEffect(() => {
    if (!simActive) {
      setMapLive([]);
      setMapLiveFlights([]);
      return;
    }
    let cancelled = false;
    const load = async () => {
      try {
        const [shipments, flights] = await Promise.all([
          dashboardApi.getMapLive(),
          dashboardApi.getMapLiveFlights(),
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
  }, [simActive]);

  // -- Flight catalog (day±1 window — refreshed when the sim day changes) ---------------
  const anchorDate = useMemo(
    () => toIsoDate(sim?.simulatedNow ?? sim?.effectiveScenarioStartAt),
    [sim?.simulatedNow, sim?.effectiveScenarioStartAt],
  );

  useEffect(() => {
    if (!anchorDate) {
      setFlightCatalog(new Map());
      return;
    }
    let cancelled = false;
    (async () => {
      try {
        const [current, previous] = await Promise.all([
          flightsApi.list(undefined, anchorDate),
          flightsApi.list(undefined, shiftIsoDate(anchorDate, -1)),
        ]);
        if (cancelled) return;
        const next = new Map<number, Flight>();
        for (const flight of previous) next.set(flight.id, flight);
        for (const flight of current) next.set(flight.id, flight);
        setFlightCatalog(next);
      } catch {
        /* keep last */
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [anchorDate]);

  // -- Upcoming flights (next 20 SCHEDULED with scheduledDeparture >= simulatedNow) -----
  useEffect(() => {
    if (!simActive || !anchorDate) {
      setUpcomingFlights([]);
      return;
    }
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
  }, [simActive, anchorDate, sim?.simulatedNow]);

  // -- Client-side simulated-clock extrapolation ----------------------------------------
  // Anchor uses client wall-clock (Date.now()) — NOT sim.lastTickAt — to avoid negative
  // elapsed values caused by client/server clock skew. Drift vs. server sim time is
  // detected and softly re-anchored when it exceeds tolerance.
  const clockAnchorRef = useRef<{ simMs: number; wallMs: number; secondsPerRealSecond: number } | null>(null);

  useEffect(() => {
    if (!sim?.running || !sim.simulatedNow) {
      clockAnchorRef.current = null;
      setSimulatedNowMs(sim?.simulatedNow ? new Date(sim.simulatedNow).getTime() : null);
      return;
    }

    const simMs = new Date(sim.simulatedNow).getTime();
    if (Number.isNaN(simMs)) return;
    const factor = Math.max(1, sim.simulationSecondsPerTick ?? 1);
    const wallNow = Date.now();
    const current = clockAnchorRef.current;

    if (!current || current.secondsPerRealSecond !== factor) {
      clockAnchorRef.current = { simMs, wallMs: wallNow, secondsPerRealSecond: factor };
      setSimulatedNowMs(simMs);
      return;
    }

    const extrapolated = current.simMs + (wallNow - current.wallMs) * factor;
    const driftMs = Math.abs(simMs - extrapolated);
    // Tolerance = one poll's worth of simulated time ± 1.5 s guard
    const tolerance = Math.max(1_500, factor * 1_500);
    if (driftMs > tolerance) {
      clockAnchorRef.current = { simMs, wallMs: wallNow, secondsPerRealSecond: factor };
      setSimulatedNowMs(simMs);
    }
  }, [sim?.running, sim?.simulatedNow, sim?.simulationSecondsPerTick]);

  useEffect(() => {
    if (!sim?.running) return;
    const interval = setInterval(() => {
      const anchor = clockAnchorRef.current;
      if (!anchor) return;
      const elapsedMs = Date.now() - anchor.wallMs;
      const projected = anchor.simMs + elapsedMs * anchor.secondsPerRealSecond;
      setSimulatedNowMs(projected);
    }, 250);
    return () => clearInterval(interval);
  }, [sim?.running]);

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
    flightCatalog,
    upcomingFlights,
    simulatedNowMs,
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
    flightCatalog,
    upcomingFlights,
    simulatedNowMs,
  ]);

  return <ctx.Provider value={value}>{children}</ctx.Provider>;
}

export function useSimulation() {
  return useContext(ctx);
}
