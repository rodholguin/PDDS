const BASE = 'http://127.0.0.1:8080';

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function fetchJson(path) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 20_000);
  try {
    const res = await fetch(`${BASE}${path}`, { signal: controller.signal });
    if (!res.ok) {
      return { error: `HTTP ${res.status}` };
    }
    return await res.json();
  } catch (error) {
    return { error: error instanceof Error ? error.message : String(error) };
  } finally {
    clearTimeout(timer);
  }
}

function countArray(value) {
  return Array.isArray(value) ? value.length : null;
}

async function main() {
  process.stdout.write(JSON.stringify({ startedAt: new Date().toISOString(), status: 'monitor-started' }) + '\n');

  while (true) {
    const timestamp = new Date().toISOString();
    const [state, system, overview, mapFlights, alerts, nodeOpkc] = await Promise.all([
      fetchJson('/api/simulation/state'),
      fetchJson('/api/dashboard/system-status'),
      fetchJson('/api/dashboard/overview'),
      fetchJson('/api/dashboard/map-live-flights'),
      fetchJson('/api/alerts'),
      fetchJson('/api/dashboard/nodes/OPKC'),
    ]);

    const sample = {
      timestamp,
      running: state?.running ?? null,
      paused: state?.paused ?? null,
      simulatedNow: state?.simulatedNow ?? null,
      lastTickAt: state?.lastTickAt ?? null,
      stateError: state?.error ?? null,
      inFlightFlights: system?.inFlightFlights ?? null,
      scheduledFlights: system?.scheduledFlights ?? null,
      criticoAirports: system?.criticoAirports ?? null,
      systemError: system?.error ?? null,
      totalActiveFlights: overview?.totalActiveFlights ?? null,
      nextScheduledFlights: overview?.nextScheduledFlights ?? null,
      shipmentsInRoute: overview?.shipmentsInRoute ?? null,
      unresolvedAlerts: overview?.unresolvedAlerts ?? null,
      overviewError: overview?.error ?? null,
      mapLiveFlightsCount: countArray(mapFlights),
      mapFlightsError: mapFlights?.error ?? null,
      alertsCount: countArray(alerts),
      alertsError: alerts?.error ?? null,
      nodeOpkcStatus: nodeOpkc?.status ?? null,
      nodeOpkcOccupancyPct: nodeOpkc?.occupancyPct ?? null,
      nodeOpkcScheduledFlights: nodeOpkc?.scheduledFlights ?? null,
      nodeError: nodeOpkc?.error ?? null,
    };

    process.stdout.write(JSON.stringify(sample) + '\n');

    if (state && state.running === false && state.paused === false) {
      process.stdout.write(JSON.stringify({ finishedAt: new Date().toISOString(), status: 'monitor-stopped' }) + '\n');
      break;
    }

    await sleep(30_000);
  }
}

main().catch((error) => {
  process.stderr.write(`${error instanceof Error ? error.stack ?? error.message : String(error)}\n`);
  process.exit(1);
});
