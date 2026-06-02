import fs from 'node:fs/promises';
import path from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { chromium } from 'playwright';

const API_URL = 'http://127.0.0.1:8080';
const UI_URL = 'http://127.0.0.1:3000';
const ARTIFACTS_DIR = path.resolve('artifacts', `period-audit-${new Date().toISOString().replace(/[:.]/g, '-')}`);
const SAMPLE_MS = Number(process.env.SAMPLE_MS ?? '30000');
const MAX_SAMPLES = Number(process.env.MAX_SAMPLES ?? '0');
const MAX_STAGNANT_SAMPLES = Number(process.env.MAX_STAGNANT_SAMPLES ?? '6');
const MAX_API_FAILURES = Number(process.env.MAX_API_FAILURES ?? '3');
const MAX_UI_FAILURES = Number(process.env.MAX_UI_FAILURES ?? '3');
const MAX_AUDIT_MINUTES = Number(process.env.MAX_AUDIT_MINUTES ?? '90');
const SCENARIO_START_DATE = process.env.SCENARIO_START_DATE ?? '2029-06-16';
const SIMULATION_SPEED = Number(process.env.SIMULATION_SPEED ?? '1');
const MIN_PROGRESS_RATIO = Number(process.env.MIN_PROGRESS_RATIO ?? '0.85');
const MIN_PROGRESS_WINDOW_MINUTES = Number(process.env.MIN_PROGRESS_WINDOW_MINUTES ?? '5');
const VISUAL_TRACKED_FLIGHTS = Number(process.env.VISUAL_TRACKED_FLIGHTS ?? '3');
const VISUAL_FREEZE_MS = Number(process.env.VISUAL_FREEZE_MS ?? '2000');
const VISUAL_CONVERGENCE_MS = Number(process.env.VISUAL_CONVERGENCE_MS ?? '2000');
const VISUAL_DIVERGENCE_PX = Number(process.env.VISUAL_DIVERGENCE_PX ?? '80');
const VISUAL_TELEPORT_PX = Number(process.env.VISUAL_TELEPORT_PX ?? '180');
const VISUAL_JITTER_DIRECTION_CHANGES = Number(process.env.VISUAL_JITTER_DIRECTION_CHANGES ?? '3');
const VISUAL_SMALL_MOVE_PX = Number(process.env.VISUAL_SMALL_MOVE_PX ?? '4');
const execFileAsync = promisify(execFile);

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function ensureDir(dir) {
  await fs.mkdir(dir, { recursive: true });
}

async function writeJsonArtifact(filename, payload) {
  await fs.writeFile(path.join(ARTIFACTS_DIR, filename), JSON.stringify(payload, null, 2));
}

async function fetchJson(endpoint, init, timeoutMs = 20_000) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const res = await fetch(`${API_URL}${endpoint}`, {
      ...init,
      signal: controller.signal,
      headers: {
        'Content-Type': 'application/json',
        ...(init?.headers ?? {}),
      },
    });
    const text = await res.text();
    const body = text ? JSON.parse(text) : null;
    return { ok: res.ok, status: res.status, body };
  } catch (error) {
    return {
      ok: false,
      status: 0,
      body: null,
      error: error instanceof Error ? error.message : String(error),
    };
  } finally {
    clearTimeout(timeout);
  }
}

async function fetchJsonWithRetry(endpoint, init, attempts = 4, delayMs = 3000, timeoutMs = 20_000) {
  let last = null;
  for (let i = 1; i <= attempts; i += 1) {
    last = await fetchJson(endpoint, init, timeoutMs);
    if (last.ok) {
      return last;
    }
    if (i < attempts) {
      await sleep(delayMs);
    }
  }
  return last;
}

async function waitForBackendIdle(maxWaitMs = 120_000) {
  const deadline = Date.now() + maxWaitMs;
  let consecutiveReady = 0;
  let lastState = null;

  while (Date.now() < deadline) {
    const state = await fetchJson('/api/simulation/state', undefined, 10_000);
    if (state.ok) {
      lastState = state;
      const body = state.body;
      if (body?.running === false && body?.paused === false) {
        consecutiveReady += 1;
        if (consecutiveReady >= 2) {
          return state;
        }
      } else {
        consecutiveReady = 0;
      }
    } else {
      consecutiveReady = 0;
    }
    await sleep(2_000);
  }

  throw new Error(`Backend no quedó inactivo tras stop: ${JSON.stringify(lastState)}`);
}

async function waitForBackendAvailable(maxWaitMs = 120_000) {
  const deadline = Date.now() + maxWaitMs;
  let lastState = null;

  while (Date.now() < deadline) {
    const state = await fetchJson('/api/simulation/state', undefined, 10_000);
    lastState = state;
    if (state.ok) {
      return state;
    }
    await sleep(2_000);
  }

  throw new Error(`Backend no respondió antes de iniciar la auditoría: ${JSON.stringify(lastState)}`);
}

async function captureBackendLogs(filename = 'backend-logs.txt') {
  try {
    const { stdout, stderr } = await execFileAsync('docker', ['logs', '--tail', '300', 'tasfb2b-backend'], {
      timeout: 30_000,
      maxBuffer: 2 * 1024 * 1024,
    });
    await fs.writeFile(path.join(ARTIFACTS_DIR, filename), `${stdout}${stderr ? `\n${stderr}` : ''}`);
  } catch (error) {
    const message = error instanceof Error ? (error.stack ?? error.message) : String(error);
    await fs.writeFile(path.join(ARTIFACTS_DIR, filename), message);
  }
}

async function captureDockerStats(filename = 'docker-stats.txt') {
  try {
    const { stdout, stderr } = await execFileAsync('docker', ['stats', '--no-stream', '--format', 'table {{.Name}}\t{{.MemUsage}}\t{{.CPUPerc}}'], {
      timeout: 30_000,
      maxBuffer: 512 * 1024,
    });
    await fs.writeFile(path.join(ARTIFACTS_DIR, filename), `${stdout}${stderr ? `\n${stderr}` : ''}`);
  } catch (error) {
    const message = error instanceof Error ? (error.stack ?? error.message) : String(error);
    await fs.writeFile(path.join(ARTIFACTS_DIR, filename), message);
  }
}

function safeCount(value) {
  return Array.isArray(value) ? value.length : null;
}

function countMismatch(left, right, tolerance = 1) {
  return left != null && right != null && Math.abs(left - right) > tolerance;
}

function buildQuery(params) {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value != null && value !== '') {
      query.set(key, String(value));
    }
  }
  const encoded = query.toString();
  return encoded ? `?${encoded}` : '';
}

function finiteNumber(value) {
  return typeof value === 'number' && Number.isFinite(value);
}

function validCoordinate(latitude, longitude) {
  return finiteNumber(latitude)
    && finiteNumber(longitude)
    && latitude >= -90 && latitude <= 90
    && longitude >= -180 && longitude <= 180;
}

function normalizeUiNumber(value) {
  if (value == null) return null;
  const parsed = Number(String(value).replace(/[^\d.-]/g, ''));
  return Number.isFinite(parsed) ? parsed : null;
}

function clamp01(value) {
  return Math.max(0, Math.min(1, value));
}

function normalizeLongitude(value) {
  if (!Number.isFinite(value)) return value;
  return ((((value + 180) % 360) + 360) % 360) - 180;
}

function nearestWrappedLongitude(target, reference) {
  if (!finiteNumber(target) || !finiteNumber(reference)) return target;
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

function mercatorY(latitude) {
  if (!finiteNumber(latitude)) return latitude;
  const safe = Math.max(-85, Math.min(85, latitude));
  const rad = (safe * Math.PI) / 180;
  return Math.log(Math.tan(Math.PI / 4 + rad / 2));
}

function euclideanDistance(a, b) {
  if (!a || !b || !finiteNumber(a.x) || !finiteNumber(a.y) || !finiteNumber(b.x) || !finiteNumber(b.y)) {
    return null;
  }
  return Math.hypot(a.x - b.x, a.y - b.y);
}

function bearingBetween(fromLat, fromLon, toLat, toLon) {
  if (![fromLat, fromLon, toLat, toLon].every(finiteNumber)) return null;
  const toRad = (value) => (value * Math.PI) / 180;
  const toDeg = (value) => (value * 180) / Math.PI;
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

function angleDifference(a, b) {
  if (!finiteNumber(a) || !finiteNumber(b)) return null;
  return Math.abs((((a - b) + 540) % 360) - 180);
}

function routeProgress(flight) {
  if (!flight) return null;
  const fromLon = normalizeLongitude(flight.originLongitude);
  const toLon = nearestWrappedLongitude(normalizeLongitude(flight.destinationLongitude), fromLon);
  const currentLon = nearestWrappedLongitude(normalizeLongitude(flight.currentLongitude), fromLon);
  const totalLon = toLon - fromLon;
  const totalLat = mercatorY(flight.destinationLatitude) - mercatorY(flight.originLatitude);
  const totalNorm = (totalLon * totalLon) + (totalLat * totalLat);
  if (totalNorm <= 0 || ![flight.originLatitude, flight.originLongitude, flight.destinationLatitude, flight.destinationLongitude, flight.currentLatitude, flight.currentLongitude].every(finiteNumber)) {
    return null;
  }
  const progress = (((currentLon - fromLon) * totalLon) + ((mercatorY(flight.currentLatitude) - mercatorY(flight.originLatitude)) * totalLat)) / totalNorm;
  return clamp01(progress);
}

function roundCoord(value) {
  return finiteNumber(value) ? Number(value.toFixed(6)) : null;
}

function roundPixel(value) {
  return finiteNumber(value) ? Number(value.toFixed(2)) : null;
}

function pickReferenceFlights(mapFlights) {
  if (!Array.isArray(mapFlights) || mapFlights.length === 0) return [];
  return [...mapFlights]
    .filter((flight) => finiteNumber(flight?.currentLatitude) && finiteNumber(flight?.currentLongitude))
    .sort((a, b) => {
      const inRouteProgressA = routeProgress(a);
      const inRouteProgressB = routeProgress(b);
      const activeProgressA = inRouteProgressA != null && inRouteProgressA > 0.02 && inRouteProgressA < 0.98 ? 1 : 0;
      const activeProgressB = inRouteProgressB != null && inRouteProgressB > 0.02 && inRouteProgressB < 0.98 ? 1 : 0;
      if (activeProgressB !== activeProgressA) return activeProgressB - activeProgressA;
      const loadDiff = (b?.loadPct ?? 0) - (a?.loadPct ?? 0);
      if (loadDiff !== 0) return loadDiff;
      return String(a?.flightCode ?? '').localeCompare(String(b?.flightCode ?? ''));
    })
    .slice(0, Math.max(1, VISUAL_TRACKED_FLIGHTS))
    .map((flight) => ({
      flightId: flight.flightId ?? null,
      flightCode: flight.flightCode ?? null,
    }));
}

async function captureFlightVisualState(page, backendFlights) {
  return page.evaluate((flights) => {
    const api = window.__PDDS_AUDIT__;
    if (!api || typeof api.captureFlightVisualState !== 'function') {
      return { error: 'UI audit bridge unavailable' };
    }
    return api.captureFlightVisualState(flights);
  }, backendFlights);
}

function makeVisualEvidence(timestamp, simulatedNow, flight, anomaly, severity, extra = {}) {
  return {
    timestamp,
    simulatedNow,
    flightId: flight?.flightId ?? null,
    flightCode: flight?.flightCode ?? null,
    uiPosition: {
      latitude: roundCoord(flight?.ui?.currentLatitude),
      longitude: roundCoord(flight?.ui?.currentLongitude),
      x: roundPixel(flight?.uiPoint?.x),
      y: roundPixel(flight?.uiPoint?.y),
    },
    backendPosition: {
      latitude: roundCoord(flight?.backend?.currentLatitude),
      longitude: roundCoord(flight?.backend?.currentLongitude),
      x: roundPixel(flight?.backendPoint?.x),
      y: roundPixel(flight?.backendPoint?.y),
    },
    anomaly,
    severity,
    ...extra,
  };
}

function parseUiCards(bodyText) {
  const lines = bodyText.split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
  const pick = (label) => {
    const idx = lines.indexOf(label);
    return idx >= 0 && idx + 1 < lines.length ? lines[idx + 1] : null;
  };
  return {
    visibleFlights: pick('Vuelos visibles'),
    nextFlights: pick('Próximos vuelos'),
    visibleShipments: pick('Envíos operativos'),
    sla: pick('SLA actual'),
    criticalNodes: pick('Nodos críticos'),
    alerts: pick('Alertas operativas'),
    collapseRisk: pick('Riesgo de colapso'),
    clock: pick('Reloj simulado'),
  };
}

async function captureUi(page) {
  if (!page.url().startsWith(UI_URL)) {
    await page.goto(UI_URL, { waitUntil: 'domcontentloaded', timeout: 60_000 });
    await page.waitForLoadState('networkidle', { timeout: 15_000 }).catch(() => {});
    await page.waitForTimeout(4_000);
  }
  const runtime = await page.locator('.state-panel-copy').allTextContents().catch(() => []);
  const body = await page.locator('body').innerText().catch(() => '');
  const flightMarkerButtons = await page.locator('button.map-flight-button').count().catch(() => 0);
  return {
    runtime,
    cards: parseUiCards(body),
    flightMarkerButtons,
    body,
  };
}

async function captureUiWithRetry(page, attempts = 3, delayMs = 2_000) {
  let lastError = null;
  for (let i = 1; i <= attempts; i += 1) {
    try {
      return await captureUi(page);
    } catch (error) {
      lastError = error;
      if (i < attempts) {
        await sleep(delayMs);
      }
    }
  }
  throw lastError instanceof Error ? lastError : new Error(String(lastError));
}

function analyzeFlightVisuals(tracker, timestamp, simulatedNow, backendMapFlights, visualState) {
  const issues = [];
  const evidence = [];

  if (!tracker.references || tracker.references.length === 0) {
    tracker.references = pickReferenceFlights(backendMapFlights);
    if (tracker.references.length > 0) {
      evidence.push({
        timestamp,
        simulatedNow,
        anomaly: 'REFERENCE_SELECTION',
        severity: 'info',
        references: tracker.references,
      });
    }
  }

  const backendByKey = new Map();
  for (const flight of Array.isArray(backendMapFlights) ? backendMapFlights : []) {
    if (flight?.flightId != null) backendByKey.set(`id:${flight.flightId}`, flight);
    if (flight?.flightCode) backendByKey.set(`code:${flight.flightCode}`, flight);
  }
  const uiByKey = new Map();
  for (const flight of visualState?.uiFlights ?? []) {
    if (flight?.flightId != null) uiByKey.set(`id:${flight.flightId}`, flight);
    if (flight?.flightCode) uiByKey.set(`code:${flight.flightCode}`, flight);
  }
  const backendProjectedByKey = new Map();
  for (const flight of visualState?.backendProjectedFlights ?? []) {
    if (flight?.flightId != null) backendProjectedByKey.set(`id:${flight.flightId}`, flight);
    if (flight?.flightCode) backendProjectedByKey.set(`code:${flight.flightCode}`, flight);
  }

  for (const ref of tracker.references) {
    const key = ref.flightId != null ? `id:${ref.flightId}` : `code:${ref.flightCode}`;
    if (!key) continue;
    const backend = backendByKey.get(key) ?? null;
    const ui = uiByKey.get(key) ?? null;
    const backendProjected = backendProjectedByKey.get(key) ?? null;
    const state = tracker.flightStates.get(key) ?? {
      flightId: ref.flightId ?? null,
      flightCode: ref.flightCode ?? null,
      lastUiPoint: null,
      lastBackendPoint: null,
      lastUiProgress: null,
      lastBackendChangeAtMs: null,
      stationarySinceMs: null,
      hiddenSinceMs: null,
      convergenceLagMs: 0,
      directionChanges: [],
      previousDirectionSign: null,
      snapBackStreak: 0,
      maxJumpPx: 0,
      maxDivergencePx: 0,
    };

    const currentMs = new Date(timestamp).getTime();
    const uiPoint = ui && finiteNumber(ui.projectedX) && finiteNumber(ui.projectedY)
      ? { x: ui.projectedX, y: ui.projectedY }
      : (ui && finiteNumber(ui.buttonCenterX) && finiteNumber(ui.buttonCenterY)
          ? { x: ui.buttonCenterX, y: ui.buttonCenterY }
          : null);
    const backendPoint = backendProjected && finiteNumber(backendProjected.projectedX) && finiteNumber(backendProjected.projectedY)
      ? { x: backendProjected.projectedX, y: backendProjected.projectedY }
      : null;
    const snapshot = { flightId: state.flightId ?? backend?.flightId ?? ui?.flightId ?? null, flightCode: state.flightCode ?? backend?.flightCode ?? ui?.flightCode ?? null, ui, backend, uiPoint, backendPoint };

    if (!backend) {
      if (ui) {
        issues.push(`Vuelo ${snapshot.flightCode ?? snapshot.flightId} visible en UI pero ausente en backend map-live-flights`);
        evidence.push(makeVisualEvidence(timestamp, simulatedNow, snapshot, 'UI_BACKEND_DIVERGENCE', 'high'));
      }
      tracker.flightStates.set(key, state);
      continue;
    }

    const backendJumpPx = euclideanDistance(state.lastBackendPoint, backendPoint) ?? 0;
    if (backendJumpPx > VISUAL_SMALL_MOVE_PX) {
      state.lastBackendChangeAtMs = currentMs;
    }

    if (!ui || ui.visible === false) {
      if (state.hiddenSinceMs == null) {
        state.hiddenSinceMs = currentMs;
      }
      const progress = routeProgress(backend);
      if (progress == null || progress < 0.95) {
        issues.push(`Vuelo ${snapshot.flightCode ?? snapshot.flightId} desapareció en UI antes de acercarse al destino`);
        evidence.push(makeVisualEvidence(timestamp, simulatedNow, snapshot, 'UNJUSTIFIED_DISAPPEARANCE', 'high', { progress }));
      }
      tracker.flightStates.set(key, state);
      continue;
    }

    state.hiddenSinceMs = null;
    const uiProgress = routeProgress({
      originLatitude: backend.originLatitude,
      originLongitude: backend.originLongitude,
      destinationLatitude: backend.destinationLatitude,
      destinationLongitude: backend.destinationLongitude,
      currentLatitude: ui.currentLatitude,
      currentLongitude: ui.currentLongitude,
    });
    const jumpPx = euclideanDistance(state.lastUiPoint, uiPoint) ?? 0;
    const divergencePx = euclideanDistance(uiPoint, backendPoint);
    state.maxJumpPx = Math.max(state.maxJumpPx, jumpPx);
    state.maxDivergencePx = Math.max(state.maxDivergencePx, divergencePx ?? 0);

    if (jumpPx > VISUAL_TELEPORT_PX && backendJumpPx < jumpPx * 0.5) {
      issues.push(`Vuelo ${snapshot.flightCode ?? snapshot.flightId} presentó teleport visual=${jumpPx.toFixed(1)}px backend=${backendJumpPx.toFixed(1)}px`);
      evidence.push(makeVisualEvidence(timestamp, simulatedNow, snapshot, 'TELEPORT', 'high', {
        jumpPx: roundPixel(jumpPx),
        backendJumpPx: roundPixel(backendJumpPx),
      }));
    }

    if (state.lastUiProgress != null && uiProgress != null) {
      const progressDelta = uiProgress - state.lastUiProgress;
      if (progressDelta < -0.01) {
        state.snapBackStreak += 1;
        issues.push(`Vuelo ${snapshot.flightCode ?? snapshot.flightId} retrocedió visualmente en la ruta (${progressDelta.toFixed(3)})`);
        evidence.push(makeVisualEvidence(timestamp, simulatedNow, snapshot, 'SNAP_BACK', state.snapBackStreak >= 2 ? 'high' : 'medium', {
          progressDelta: Number(progressDelta.toFixed(4)),
          streak: state.snapBackStreak,
        }));
      } else if (progressDelta > 0.002) {
        state.snapBackStreak = 0;
      }

      const directionSign = Math.abs(progressDelta) < 0.002 ? 0 : (progressDelta > 0 ? 1 : -1);
      if (directionSign !== 0) {
        if (state.previousDirectionSign != null && state.previousDirectionSign !== directionSign) {
          state.directionChanges.push(currentMs);
        }
        state.previousDirectionSign = directionSign;
      }
      state.directionChanges = state.directionChanges.filter((value) => currentMs - value <= 5000);
      if (state.directionChanges.length >= VISUAL_JITTER_DIRECTION_CHANGES) {
        issues.push(`Vuelo ${snapshot.flightCode ?? snapshot.flightId} presentó jitter visual con ${state.directionChanges.length} cambios de dirección`);
        evidence.push(makeVisualEvidence(timestamp, simulatedNow, snapshot, 'JITTER', 'medium', {
          directionChanges: state.directionChanges.length,
        }));
      }
    }

    if (jumpPx <= VISUAL_SMALL_MOVE_PX) {
      if (state.stationarySinceMs == null) {
        state.stationarySinceMs = currentMs;
      }
    } else {
      state.stationarySinceMs = null;
    }
    if (state.stationarySinceMs != null && state.lastBackendChangeAtMs != null) {
      const stationaryMs = currentMs - state.stationarySinceMs;
      const backendFreshMs = currentMs - state.lastBackendChangeAtMs;
      if (stationaryMs > VISUAL_FREEZE_MS && backendFreshMs <= VISUAL_FREEZE_MS) {
        issues.push(`Vuelo ${snapshot.flightCode ?? snapshot.flightId} quedó congelado ${stationaryMs}ms mientras backend seguía cambiando`);
        evidence.push(makeVisualEvidence(timestamp, simulatedNow, snapshot, 'FREEZE', 'high', {
          stationaryMs,
          backendFreshMs,
        }));
      }
    }

    if (divergencePx != null) {
      state.convergenceLagMs = divergencePx > VISUAL_DIVERGENCE_PX ? state.convergenceLagMs + SAMPLE_MS : 0;
      if (state.convergenceLagMs > VISUAL_CONVERGENCE_MS) {
        issues.push(`Vuelo ${snapshot.flightCode ?? snapshot.flightId} no converge a backend en ${state.convergenceLagMs}ms (divergencia=${divergencePx.toFixed(1)}px)`);
        evidence.push(makeVisualEvidence(timestamp, simulatedNow, snapshot, 'DIVERGENCE', 'high', {
          divergencePx: roundPixel(divergencePx),
          convergenceLagMs: state.convergenceLagMs,
        }));
      }
    }

    const expectedBearing = bearingBetween(ui.currentLatitude, ui.currentLongitude, ui.destinationLatitude, ui.destinationLongitude);
    const rotationDelta = angleDifference(ui.rotationDeg, expectedBearing);
    if (rotationDelta != null && rotationDelta > 70) {
      issues.push(`Vuelo ${snapshot.flightCode ?? snapshot.flightId} tiene rotación errática delta=${rotationDelta.toFixed(1)}°`);
      evidence.push(makeVisualEvidence(timestamp, simulatedNow, snapshot, 'ERRATIC_ROTATION', 'medium', {
        rotationDeg: roundPixel(ui.rotationDeg),
        expectedBearingDeg: roundPixel(expectedBearing),
        rotationDeltaDeg: roundPixel(rotationDelta),
      }));
    }

    const antimeridianRoute = Math.abs(normalizeLongitude(backend.destinationLongitude) - normalizeLongitude(backend.originLongitude)) > 150;
    if (antimeridianRoute && jumpPx > VISUAL_TELEPORT_PX) {
      issues.push(`Vuelo ${snapshot.flightCode ?? snapshot.flightId} mostró cruce anómalo del antimeridiano`);
      evidence.push(makeVisualEvidence(timestamp, simulatedNow, snapshot, 'ANTIMERIDIAN_CROSSING', 'high', {
        jumpPx: roundPixel(jumpPx),
      }));
    }

    state.lastUiPoint = uiPoint;
    state.lastBackendPoint = backendPoint;
    state.lastUiProgress = uiProgress;
    tracker.flightStates.set(key, state);
  }

  return { issues, evidence };
}

function summarizeVisualTracker(tracker) {
  return {
    startedAt: tracker.startedAt,
    references: tracker.references,
    flights: [...tracker.flightStates.values()].map((state) => ({
      flightId: state.flightId,
      flightCode: state.flightCode,
      maxJumpPx: roundPixel(state.maxJumpPx),
      maxDivergencePx: roundPixel(state.maxDivergencePx),
      lastUiProgress: state.lastUiProgress != null ? Number(state.lastUiProgress.toFixed(4)) : null,
    })),
    anomalyCount: tracker.evidence.length,
  };
}

function evaluateVisualOutcome(tracker) {
  const evidence = [];
  const findings = [];
  const anomalies = tracker.evidence.filter((entry) => entry?.anomaly && entry.anomaly !== 'REFERENCE_SELECTION');
  if ((tracker.references?.length ?? 0) > 0) {
    evidence.push(`Se monitorearon ${tracker.references.length} vuelos de referencia para consistencia visual`);
  } else {
    findings.push('No se pudieron seleccionar vuelos de referencia para la validación visual');
  }
  if (anomalies.length === 0 && (tracker.references?.length ?? 0) > 0) {
    evidence.push('Movimiento visual de aviones sin snap-backs, teleports o freezes relevantes en la muestra monitoreada');
  }
  return { evidence, findings };
}

function hasBackendFailure(backend) {
  return Object.values(backend).some((entry) => !entry?.ok);
}

function listBackendFailures(backend) {
  return Object.entries(backend)
    .filter(([, entry]) => !entry?.ok)
    .map(([key, entry]) => `${key}: status=${entry?.status ?? 'n/a'} error=${entry?.error ?? 'unknown'}`);
}

function compareBackendUi(backend, ui) {
  const findings = [];
  const flightTolerance = Math.max(5, Math.ceil((safeCount(backend.mapFlights?.body) ?? 0) * 0.05));
  const shipmentTolerance = 2;
  const nextFlightsTolerance = 5;

  const mapFlights = safeCount(backend.mapFlights?.body);
  const mapShipments = safeCount(backend.mapShipments?.body);
  const uiFlights = normalizeUiNumber(ui.cards.visibleFlights);
  const uiShipments = normalizeUiNumber(ui.cards.visibleShipments);
  const uiAlerts = normalizeUiNumber(ui.cards.alerts);
  const uiCritical = normalizeUiNumber(ui.cards.criticalNodes);
  const uiNext = normalizeUiNumber(ui.cards.nextFlights);
  const uiFlightMarkers = normalizeUiNumber(ui.flightMarkerButtons);

  if (backend.state?.body?.running === true && backend.state?.body?.paused !== true && !ui.runtime.some((line) => line.includes('Simulación corriendo'))) {
    findings.push('UI no muestra simulación corriendo mientras backend indica running=true');
  }
  if (backend.state?.body?.running === false && ui.runtime.some((line) => line.includes('Simulación corriendo'))) {
    findings.push('UI muestra simulación corriendo mientras backend indica running=false');
  }
  if (countMismatch(mapFlights, uiFlights, flightTolerance)) {
    findings.push(`Desfase vuelos visibles UI=${uiFlights} backend=${mapFlights}`);
  }
  if (countMismatch(mapFlights, uiFlightMarkers, flightTolerance)) {
    findings.push(`Desfase marcadores de aviones UI=${uiFlightMarkers} backend=${mapFlights}`);
  }
  if (countMismatch(uiFlights, uiFlightMarkers, 1)) {
    findings.push(`KPI vuelos visibles UI=${uiFlights} no coincide con marcadores reales=${uiFlightMarkers}`);
  }
  if (countMismatch(mapShipments, uiShipments, shipmentTolerance)) {
    findings.push(`Desfase envíos operativos UI=${uiShipments} backend=${mapShipments}`);
  }
  if (backend.overview?.body?.unresolvedAlerts != null && uiAlerts != null && backend.overview.body.unresolvedAlerts !== uiAlerts) {
    findings.push(`Desfase alertas UI=${uiAlerts} backend=${backend.overview.body.unresolvedAlerts}`);
  }
  if (backend.system?.body?.criticoAirports != null && uiCritical != null && backend.system.body.criticoAirports !== uiCritical) {
    findings.push(`Desfase nodos críticos UI=${uiCritical} backend=${backend.system.body.criticoAirports}`);
  }
  if ((mapFlights ?? 0) > 0 && countMismatch(backend.overview?.body?.nextScheduledFlights, uiNext, nextFlightsTolerance)) {
    findings.push(`Desfase próximos vuelos UI=${uiNext} backend=${backend.overview.body.nextScheduledFlights}`);
  }
  return findings;
}

function minutesBetween(start, end) {
  if (!start || !end) return null;
  const startMs = new Date(start).getTime();
  const endMs = new Date(end).getTime();
  if (!Number.isFinite(startMs) || !Number.isFinite(endMs)) return null;
  return (endMs - startMs) / 60_000;
}

function evaluatePositiveSignals(samples) {
  const evidence = [];
  const findings = [];
  if (!Array.isArray(samples) || samples.length === 0) {
    findings.push('La auditoría no produjo muestras para validar señales positivas');
    return { evidence, findings };
  }

  const first = samples[0];
  const last = samples[samples.length - 1];
  const firstState = first.backend?.state;
  const lastState = last.backend?.state;
  const elapsedRealMinutes = minutesBetween(first.timestamp, last.timestamp);
  const simulatedMinutes = minutesBetween(firstState?.effectiveScenarioStartAt ?? firstState?.simulatedNow, lastState?.simulatedNow);
  const expectedMinutesPerRealMinute = lastState?.tickIntervalMs && lastState?.simulationSecondsPerTick
    ? (60_000 / lastState.tickIntervalMs) * (lastState.simulationSecondsPerTick / 60)
    : null;
  const progressRatio = elapsedRealMinutes && expectedMinutesPerRealMinute
    ? simulatedMinutes / (elapsedRealMinutes * expectedMinutesPerRealMinute)
    : null;

  if (progressRatio != null && elapsedRealMinutes != null && elapsedRealMinutes >= MIN_PROGRESS_WINDOW_MINUTES) {
    if (progressRatio >= MIN_PROGRESS_RATIO) {
      evidence.push(`Progreso temporal sostenido: ratio=${progressRatio.toFixed(2)} sobre esperado mínimo=${MIN_PROGRESS_RATIO}`);
    } else {
      findings.push(`Progreso temporal insuficiente: ratio=${progressRatio.toFixed(2)} por debajo del mínimo=${MIN_PROGRESS_RATIO}`);
    }
  }

  const hadVisibleFlights = samples.some((sample) => (sample.backend?.mapFlightsCount ?? 0) > 0 || (sample.ui?.flightMarkerButtons ?? 0) > 0);
  if (hadVisibleFlights) {
    evidence.push('Se observaron vuelos activos reales en backend/UI durante la corrida');
  } else {
    findings.push('No se observaron vuelos activos reales durante la corrida');
  }

  const hadOperationalShipments = samples.some((sample) => (sample.backend?.mapShipmentsCount ?? 0) > 0);
  const hadPendingShipmentInventory = samples.some((sample) => {
    const page = sample.backend?.shipmentsPage;
    return Array.isArray(page?.content) && page.content.length > 0;
  });
  const finishedCleanly = lastState && lastState.running === false && lastState.paused === false;
  if (hadOperationalShipments) {
    evidence.push('Se observaron envíos operativos reales en el mapa durante la corrida');
  } else if (!finishedCleanly && hadPendingShipmentInventory) {
    evidence.push('Se observó inventario operativo de envíos planificables, pero la corrida terminó antes de evidenciar movimiento en el mapa');
  } else {
    findings.push('No se observaron envíos operativos reales durante la corrida');
  }

  const hadActiveFlights = samples.some((sample) => (sample.backend?.overview?.totalActiveFlights ?? 0) > 0 || (sample.backend?.system?.inFlightFlights ?? 0) > 0);
  if (hadActiveFlights) {
    evidence.push('Los KPIs operativos reportaron vuelos activos durante la corrida');
  } else {
    findings.push('Los KPIs operativos no reportaron vuelos activos durante la corrida');
  }

  const hadRouteInventory = samples.every((sample) => (sample.backend?.routesCount ?? 0) > 0);
  if (hadRouteInventory) {
    evidence.push('La red de rutas estuvo disponible en todas las muestras');
  } else {
    findings.push('La red de rutas faltó en al menos una muestra');
  }

  if (finishedCleanly) {
    evidence.push('La simulación terminó limpia por backend sin quedar corriendo ni pausada');
  }

  return { evidence, findings };
}

function analyzeBusinessRules(backend) {
  const findings = [];
  const state = backend.state?.body;
  const overview = backend.overview?.body;
  const system = backend.system?.body;
  const alerts = backend.alerts?.body;
  const mapFlights = backend.mapFlights?.body;
  const mapShipments = backend.mapShipments?.body;
  const node = backend.nodeOpkc?.body;
  const flightTolerance = Math.max(5, Math.ceil((safeCount(mapFlights) ?? 0) * 0.05));
  const shipmentTolerance = 2;

  if (overview && system && countMismatch(overview.totalActiveFlights, safeCount(mapFlights), flightTolerance)) {
    findings.push(`KPI totalActiveFlights=${overview.totalActiveFlights} no coincide con map-live-flights=${safeCount(mapFlights)}`);
  }
  const visibleMapShipments = safeCount(mapShipments);
  if (overview && countMismatch(overview.operationalShipments, visibleMapShipments, shipmentTolerance)) {
    findings.push(`KPI operationalShipments=${overview.operationalShipments} no coincide con map-live=${visibleMapShipments}`);
  }
  if (overview && safeCount(alerts) != null && overview.unresolvedAlerts !== safeCount(alerts)) {
    findings.push(`KPI unresolvedAlerts=${overview.unresolvedAlerts} no coincide con /api/alerts=${safeCount(alerts)}`);
  }
  if (system && mapFlights && countMismatch(system.inFlightFlights, safeCount(mapFlights), flightTolerance)) {
    findings.push(`system.inFlightFlights=${system.inFlightFlights} no coincide con map-live-flights=${safeCount(mapFlights)}`);
  }
  if (node && node.scheduledFlights > 500) {
    findings.push(`Nodo OPKC reporta scheduledFlights anormalmente alto: ${node.scheduledFlights}`);
  }
  if (mapFlights && Array.isArray(mapFlights)) {
    const overloaded = mapFlights.filter((flight) => typeof flight.loadPct === 'number' && flight.loadPct > 100);
    if (overloaded.length > 0) {
      findings.push(`Hay ${overloaded.length} vuelos con loadPct > 100 en el mapa`);
    }
  }
  if (state?.running && state?.lastTickAt == null) {
    findings.push('Simulación corriendo sin lastTickAt');
  }
  return findings;
}

async function configureAndStart() {
  await waitForBackendAvailable();
  const stop = await fetchJsonWithRetry('/api/simulation/stop', { method: 'POST' }, 2, 5000, 120_000);
  if (!stop.ok) {
    const reset = await fetchJsonWithRetry('/api/simulation/reset-to-initial', { method: 'POST' }, 2, 5000, 120_000);
    if (!reset.ok) {
      throw new Error(`No se pudo limpiar la simulación con stop/reset: ${JSON.stringify({ stop, reset })}`);
    }
  }
  await waitForBackendIdle();

  const configure = await fetchJsonWithRetry('/api/simulation/configure', {
    method: 'POST',
    body: JSON.stringify({
      scenario: 'PERIOD_SIMULATION',
      simulationDays: 5,
      scenarioStartDate: SCENARIO_START_DATE,
      normalThresholdPct: 70,
      warningThresholdPct: 90,
    }),
  }, 5, 3000, 120_000);
  if (!configure.ok) throw new Error(`No se pudo configurar simulación: ${JSON.stringify(configure)}`);

  const stateAfterConfig = await fetchJsonWithRetry('/api/simulation/state', undefined, 5, 2000, 15_000);
  if (!stateAfterConfig.body?.projectedDemandReady) {
    const project = await fetchJsonWithRetry('/api/import/demand/project-future', {
      method: 'POST',
      body: JSON.stringify({ projectionStart: '2028-04-09', projectionEnd: '2030-12-31' }),
    }, 4, 5000, 120_000);
    if (!project.ok) {
      throw new Error(`No se pudo generar demanda futura: ${JSON.stringify(project)}`);
    }
  }

  const speed = await fetchJsonWithRetry('/api/simulation/speed', {
    method: 'POST',
    body: JSON.stringify({ speed: SIMULATION_SPEED }),
  }, 5, 2000, 20_000);
  if (!speed.ok) throw new Error(`No se pudo ajustar velocidad: ${JSON.stringify(speed)}`);

  const start = await fetchJsonWithRetry('/api/simulation/start', { method: 'POST' }, 5, 3000, 120_000);
  if (!start.ok) throw new Error(`No se pudo iniciar simulación: ${JSON.stringify(start)}`);
  return start.body;
}

async function sampleBackend() {
  const [state, overview, system, alerts, mapFlights, mapShipments, collapseRisk, routes, nodeOpkc, shipments] = await Promise.all([
    fetchJson('/api/simulation/state'),
    fetchJsonWithRetry('/api/dashboard/overview', undefined, 3, 1500, 30000),
    fetchJson('/api/dashboard/system-status'),
    fetchJson('/api/alerts'),
    fetchJson('/api/dashboard/map-live-flights'),
    fetchJson('/api/dashboard/map-live'),
    fetchJson('/api/simulation/collapse-risk'),
    fetchJson('/api/dashboard/routes-network'),
    fetchJson('/api/dashboard/nodes/OPKC'),
    fetchJsonWithRetry('/api/shipments?status=PENDING&date=2029-06-15&fromDate=true&size=50', undefined, 3, 1500, 30000),
  ]);
  return { state, overview, system, alerts, mapFlights, mapShipments, collapseRisk, routes, nodeOpkc, shipments };
}

async function fetchFinalConsistencyData(finalState, samples) {
  const simulatedNow = finalState?.simulatedNow ?? null;
  const effectiveDate = simulatedNow ? new Date(simulatedNow).toISOString().slice(0, 10) : SCENARIO_START_DATE;
  const lastSample = Array.isArray(samples) && samples.length > 0 ? samples[samples.length - 1] : null;
  const shipmentIds = new Set();
  const flightIds = new Set();

  const addShipmentIds = (items, field = 'shipmentId') => {
    if (!Array.isArray(items)) return;
    for (const item of items) {
      const value = item?.[field] ?? item?.id ?? null;
      if (value != null) shipmentIds.add(value);
    }
  };
  const addFlightIds = (items, field = 'flightId') => {
    if (!Array.isArray(items)) return;
    for (const item of items) {
      const value = item?.[field] ?? item?.id ?? null;
      if (value != null) flightIds.add(value);
    }
  };

  addShipmentIds(lastSample?.backend?.mapShipments ?? []);
  addShipmentIds(lastSample?.backend?.shipmentsPage?.content ?? [], 'id');
  addFlightIds(lastSample?.backend?.mapFlights ?? []);

  const [state, overview, system, alerts, mapFlights, mapShipments, routes, airports, bottlenecks, results, flightsScheduled, flightsInFlight, flightsCompleted, shipmentsPending, shipmentsInRoute, shipmentsDelivered, shipmentsCritical] = await Promise.all([
    fetchJson('/api/simulation/state'),
    fetchJson('/api/dashboard/overview'),
    fetchJson('/api/dashboard/system-status'),
    fetchJson('/api/alerts'),
    fetchJson('/api/dashboard/map-live-flights'),
    fetchJson('/api/dashboard/map-live'),
    fetchJson('/api/dashboard/routes-network'),
    fetchJson('/api/airports'),
    fetchJson('/api/airports/bottlenecks'),
    fetchJson('/api/simulation/results'),
    fetchJson(`/api/flights/search${buildQuery({ status: 'SCHEDULED', date: effectiveDate, page: 0, size: 200 })}`),
    fetchJson(`/api/flights/search${buildQuery({ status: 'IN_FLIGHT', date: effectiveDate, page: 0, size: 200 })}`),
    fetchJson(`/api/flights/search${buildQuery({ status: 'COMPLETED', date: effectiveDate, page: 0, size: 200 })}`),
    fetchJson(`/api/shipments${buildQuery({ status: 'PENDING', date: effectiveDate, fromDate: true, size: 200 })}`),
    fetchJson(`/api/shipments${buildQuery({ status: 'IN_ROUTE', date: effectiveDate, fromDate: true, size: 200 })}`),
    fetchJson(`/api/shipments${buildQuery({ status: 'DELIVERED', date: effectiveDate, fromDate: true, size: 200 })}`),
    fetchJson('/api/shipments/critical'),
  ]);

  addShipmentIds(mapShipments.body ?? []);
  addShipmentIds(shipmentsPending.body?.content ?? [], 'id');
  addShipmentIds(shipmentsInRoute.body?.content ?? [], 'id');
  addShipmentIds(shipmentsDelivered.body?.content ?? [], 'id');
  addShipmentIds(shipmentsCritical.body ?? [], 'id');
  addFlightIds(mapFlights.body ?? []);
  addFlightIds(flightsScheduled.body?.content ?? [], 'id');
  addFlightIds(flightsInFlight.body?.content ?? [], 'id');
  addFlightIds(flightsCompleted.body?.content ?? [], 'id');

  const sampledShipmentIds = [...shipmentIds].slice(0, 12);
  const sampledFlightIds = [...flightIds].slice(0, 12);
  const shipmentDetails = await Promise.all(sampledShipmentIds.map(async (id) => ({
    id,
    detail: await fetchJson(`/api/shipments/${id}`),
  })));
  const flightDetails = await Promise.all(sampledFlightIds.map(async (id) => ({
    id,
    detail: await fetchJson(`/api/flights/${id}`),
  })));

  return {
    effectiveDate,
    state,
    overview,
    system,
    alerts,
    mapFlights,
    mapShipments,
    routes,
    airports,
    bottlenecks,
    results,
    flightsScheduled,
    flightsInFlight,
    flightsCompleted,
    shipmentsPending,
    shipmentsInRoute,
    shipmentsDelivered,
    shipmentsCritical,
    shipmentDetails,
    flightDetails,
  };
}

function evaluateFinalConsistency(data, ui) {
  const findings = [];
  const evidence = [];

  const allResponses = [
    data.state,
    data.overview,
    data.system,
    data.alerts,
    data.mapFlights,
    data.mapShipments,
    data.routes,
    data.airports,
    data.bottlenecks,
    data.results,
    data.flightsScheduled,
    data.flightsInFlight,
    data.flightsCompleted,
    data.shipmentsPending,
    data.shipmentsInRoute,
    data.shipmentsDelivered,
    data.shipmentsCritical,
    ...data.shipmentDetails.map((entry) => entry.detail),
    ...data.flightDetails.map((entry) => entry.detail),
  ];

  const backendErrors = allResponses
    .filter((entry) => !entry?.ok)
    .map((entry) => `status=${entry?.status ?? 'n/a'} error=${entry?.error ?? 'unknown'}`);
  if (backendErrors.length > 0) {
    findings.push(`Fallos en validación final de backend: ${backendErrors.join(' | ')}`);
    return { findings, evidence };
  }

  const state = data.state.body;
  const overview = data.overview.body;
  const system = data.system.body;
  const alerts = data.alerts.body ?? [];
  const mapFlights = data.mapFlights.body ?? [];
  const mapShipments = data.mapShipments.body ?? [];
  const routes = data.routes.body ?? [];
  const airports = data.airports.body ?? [];
  const bottlenecks = data.bottlenecks.body ?? [];
  const kpis = data.results.body?.kpis ?? null;
  const scheduledFlights = data.flightsScheduled.body?.content ?? [];
  const inFlightFlights = data.flightsInFlight.body?.content ?? [];
  const completedFlights = data.flightsCompleted.body?.content ?? [];
  const pendingShipments = data.shipmentsPending.body?.content ?? [];
  const inRouteShipments = data.shipmentsInRoute.body?.content ?? [];
  const deliveredShipments = data.shipmentsDelivered.body?.content ?? [];
  const criticalShipments = data.shipmentsCritical.body ?? [];
  const flightDetails = data.flightDetails.map((entry) => entry.detail.body).filter(Boolean);

  const uiFlights = normalizeUiNumber(ui.cards.visibleFlights);
  const uiShipments = normalizeUiNumber(ui.cards.visibleShipments);
  const uiAlerts = normalizeUiNumber(ui.cards.alerts);
  const uiCritical = normalizeUiNumber(ui.cards.criticalNodes);
  const uiNext = normalizeUiNumber(ui.cards.nextFlights);
  const uiFlightMarkers = normalizeUiNumber(ui.flightMarkerButtons);
  const flightTolerance = Math.max(3, Math.ceil(mapFlights.length * 0.05));
  const shipmentTolerance = 2;

  if (state?.running !== false || state?.paused !== false) {
    findings.push('La simulación no quedó detenida al cierre');
  }
  if (!state?.simulatedNow) {
    findings.push('El estado final no expone simulatedNow');
  }

  if (countMismatch(overview?.totalActiveFlights, mapFlights.length, flightTolerance)) {
    findings.push(`Cierre inconsistente: totalActiveFlights=${overview?.totalActiveFlights} vs map-live-flights=${mapFlights.length}`);
  }
  if (countMismatch(system?.inFlightFlights, mapFlights.length, flightTolerance)) {
    findings.push(`Cierre inconsistente: inFlightFlights=${system?.inFlightFlights} vs map-live-flights=${mapFlights.length}`);
  }
  if (countMismatch(overview?.operationalShipments, mapShipments.length, shipmentTolerance)) {
    findings.push(`Cierre inconsistente: operationalShipments=${overview?.operationalShipments} vs map-live=${mapShipments.length}`);
  }
  if (countMismatch(overview?.unresolvedAlerts, alerts.length, 0)) {
    findings.push(`Cierre inconsistente: unresolvedAlerts=${overview?.unresolvedAlerts} vs alerts=${alerts.length}`);
  }
  if (countMismatch(system?.criticoAirports, bottlenecks.filter((airport) => airport?.status === 'CRITICO').length, 0)) {
    findings.push(`Cierre inconsistente: criticoAirports=${system?.criticoAirports} vs bottlenecks críticos=${bottlenecks.filter((airport) => airport?.status === 'CRITICO').length}`);
  }

  if (countMismatch(uiFlights, mapFlights.length, flightTolerance)) {
    findings.push(`UX final inconsistente: vuelos visibles UI=${uiFlights} backend=${mapFlights.length}`);
  }
  if (countMismatch(uiFlightMarkers, mapFlights.length, flightTolerance)) {
    findings.push(`UX final inconsistente: marcadores vuelos UI=${uiFlightMarkers} backend=${mapFlights.length}`);
  }
  if (countMismatch(uiShipments, mapShipments.length, shipmentTolerance)) {
    findings.push(`UX final inconsistente: envíos operativos UI=${uiShipments} backend=${mapShipments.length}`);
  }
  if (countMismatch(uiAlerts, overview?.unresolvedAlerts, 0)) {
    findings.push(`UX final inconsistente: alertas UI=${uiAlerts} backend=${overview?.unresolvedAlerts}`);
  }
  if (countMismatch(uiCritical, system?.criticoAirports, 0)) {
    findings.push(`UX final inconsistente: nodos críticos UI=${uiCritical} backend=${system?.criticoAirports}`);
  }
  if ((mapFlights.length > 0 || (overview?.nextScheduledFlights ?? 0) > 0) && countMismatch(uiNext, overview?.nextScheduledFlights, 5)) {
    findings.push(`UX final inconsistente: próximos vuelos UI=${uiNext} backend=${overview?.nextScheduledFlights}`);
  }
  if (!ui.runtime.some((line) => line.includes('Simulación detenida'))) {
    findings.push('UX final inconsistente: la UI no muestra simulación detenida');
  }

  for (const airport of airports) {
    if (!finiteNumber(airport?.occupancyPct) || airport.occupancyPct < 0 || airport.occupancyPct > 100) {
      findings.push(`Aeropuerto ${airport?.icaoCode ?? 'desconocido'} con occupancyPct inválido=${airport?.occupancyPct}`);
      continue;
    }
    if (!Number.isInteger(airport?.maxStorageCapacity) || airport.maxStorageCapacity < 0) {
      findings.push(`Aeropuerto ${airport?.icaoCode ?? 'desconocido'} con maxStorageCapacity inválido=${airport?.maxStorageCapacity}`);
    }
    if (!Number.isInteger(airport?.currentStorageLoad) || airport.currentStorageLoad < 0) {
      findings.push(`Aeropuerto ${airport?.icaoCode ?? 'desconocido'} con currentStorageLoad inválido=${airport?.currentStorageLoad}`);
    }
    if (airport?.currentStorageLoad > airport?.maxStorageCapacity) {
      findings.push(`Aeropuerto ${airport?.icaoCode ?? 'desconocido'} excede capacidad ${airport.currentStorageLoad}/${airport.maxStorageCapacity}`);
    }
  }

  for (const flight of [...scheduledFlights, ...inFlightFlights, ...completedFlights]) {
    const ref = flight.flightCode ?? flight.id ?? 'vuelo';
    if (!Number.isInteger(flight?.maxCapacity) || flight.maxCapacity <= 0) {
      findings.push(`Vuelo ${ref} con maxCapacity inválido=${flight?.maxCapacity}`);
    }
    if (!Number.isInteger(flight?.currentLoad) || flight.currentLoad < 0) {
      findings.push(`Vuelo ${ref} con currentLoad inválido=${flight?.currentLoad}`);
    }
    if (!Number.isInteger(flight?.availableCapacity) || flight.availableCapacity < 0) {
      findings.push(`Vuelo ${ref} con availableCapacity inválido=${flight?.availableCapacity}`);
    }
    if (flight?.currentLoad > flight?.maxCapacity) {
      findings.push(`Vuelo ${ref} excede capacidad ${flight.currentLoad}/${flight.maxCapacity}`);
    }
    if (flight?.availableCapacity !== flight?.maxCapacity - flight?.currentLoad) {
      findings.push(`Vuelo ${ref} tiene capacidad inconsistente: available=${flight.availableCapacity} max=${flight.maxCapacity} load=${flight.currentLoad}`);
    }
    if (finiteNumber(flight?.loadPct) && flight.loadPct > 100.0001) {
      findings.push(`Vuelo ${ref} tiene loadPct inválido=${flight.loadPct}`);
    }
  }

  for (const flight of mapFlights) {
    const ref = flight.flightCode ?? flight.flightId ?? 'vuelo-mapa';
    if (!validCoordinate(flight?.currentLatitude, flight?.currentLongitude)) {
      findings.push(`Vuelo en mapa ${ref} tiene coordenadas inválidas`);
    }
    if (!validCoordinate(flight?.originLatitude, flight?.originLongitude) || !validCoordinate(flight?.destinationLatitude, flight?.destinationLongitude)) {
      findings.push(`Vuelo en mapa ${ref} tiene coordenadas origen/destino inválidas`);
    }
    if (finiteNumber(flight?.loadPct) && flight.loadPct > 100.0001) {
      findings.push(`Vuelo en mapa ${ref} tiene loadPct inválido=${flight.loadPct}`);
    }
  }

  for (const shipment of mapShipments) {
    const ref = shipment.shipmentCode ?? shipment.shipmentId ?? 'envio-mapa';
    if (!validCoordinate(shipment?.currentLatitude, shipment?.currentLongitude)) {
      findings.push(`Envío en mapa ${ref} tiene coordenadas actuales inválidas`);
    }
    if (!validCoordinate(shipment?.originLatitude, shipment?.originLongitude) || !validCoordinate(shipment?.nextLatitude, shipment?.nextLongitude)) {
      findings.push(`Envío en mapa ${ref} tiene coordenadas origen/siguiente inválidas`);
    }
    if (!finiteNumber(shipment?.progressPct) || shipment.progressPct < 0 || shipment.progressPct > 100) {
      findings.push(`Envío en mapa ${ref} tiene progreso inválido=${shipment?.progressPct}`);
    }
  }

  for (const route of routes) {
    const ref = `${route?.originIcao ?? '?'}->${route?.destinationIcao ?? '?'}`;
    if (!validCoordinate(route?.originLatitude, route?.originLongitude) || !validCoordinate(route?.destinationLatitude, route?.destinationLongitude)) {
      findings.push(`Ruta ${ref} tiene coordenadas inválidas`);
    }
    for (const field of ['scheduledCount', 'inFlightCount', 'cancelledCount']) {
      if (!Number.isInteger(route?.[field]) || route[field] < 0) {
        findings.push(`Ruta ${ref} tiene ${field} inválido=${route?.[field]}`);
      }
    }
    const shouldBeOperational = (route?.scheduledCount ?? 0) > 0 || (route?.inFlightCount ?? 0) > 0;
    const shouldBeSuspended = !shouldBeOperational && (route?.cancelledCount ?? 0) > 0;
    if (route?.operational !== shouldBeOperational) {
      findings.push(`Ruta ${ref} tiene operational=${route?.operational} pero conteos indican ${shouldBeOperational}`);
    }
    if (route?.suspended !== shouldBeSuspended) {
      findings.push(`Ruta ${ref} tiene suspended=${route?.suspended} pero conteos indican ${shouldBeSuspended}`);
    }
  }

  for (const shipment of pendingShipments) {
    if (!finiteNumber(shipment?.progressPercentage) || shipment.progressPercentage < 0 || shipment.progressPercentage > 100) {
      findings.push(`Envío pendiente ${shipment?.shipmentCode ?? shipment?.id} tiene progressPercentage inválido=${shipment?.progressPercentage}`);
    }
    if (shipment?.deliveredAt != null) {
      findings.push(`Envío pendiente ${shipment?.shipmentCode ?? shipment?.id} tiene deliveredAt informado`);
    }
  }
  for (const shipment of inRouteShipments) {
    if (!finiteNumber(shipment?.progressPercentage) || shipment.progressPercentage < 0 || shipment.progressPercentage > 100) {
      findings.push(`Envío en ruta ${shipment?.shipmentCode ?? shipment?.id} tiene progressPercentage inválido=${shipment?.progressPercentage}`);
    }
    if (shipment?.deliveredAt != null) {
      findings.push(`Envío en ruta ${shipment?.shipmentCode ?? shipment?.id} tiene deliveredAt informado`);
    }
  }
  for (const shipment of deliveredShipments) {
    const ref = shipment?.shipmentCode ?? shipment?.id;
    if (shipment?.deliveredAt == null) {
      findings.push(`Envío entregado ${ref} no tiene deliveredAt`);
    }
    if (!finiteNumber(shipment?.progressPercentage) || shipment.progressPercentage < 99.999) {
      findings.push(`Envío entregado ${ref} no quedó en 100% de progreso`);
    }
  }
  for (const shipment of criticalShipments) {
    const ref = shipment?.shipmentCode ?? shipment?.id;
    if (!['CRITICAL', 'DELAYED'].includes(String(shipment?.status))) {
      findings.push(`Listado crítico incluye envío ${ref} con estado=${shipment?.status}`);
    }
  }

  for (const { id, detail } of data.shipmentDetails) {
    const shipment = detail.body;
    if (!shipment) continue;
    const ref = shipment.shipmentCode ?? id;
    if (!Array.isArray(shipment.stops) || shipment.stops.length === 0) {
      if (shipment.status !== 'PENDING') {
        findings.push(`Envío ${ref} con estado=${shipment.status} no tiene stops`);
      }
      continue;
    }

    let previousOrder = -1;
    let completedCount = 0;
    for (const stop of shipment.stops) {
      if (!Number.isInteger(stop?.stopOrder) || stop.stopOrder < 0 || stop.stopOrder <= previousOrder) {
        findings.push(`Envío ${ref} tiene stopOrder no creciente en stop=${stop?.stopOrder}`);
      }
      previousOrder = stop?.stopOrder ?? previousOrder;
      if (!['PENDING', 'IN_TRANSIT', 'COMPLETED'].includes(String(stop?.stopStatus))) {
        findings.push(`Envío ${ref} tiene stopStatus inválido=${stop?.stopStatus}`);
      }
      if (stop?.stopStatus === 'COMPLETED') {
        completedCount += 1;
      }
      if (stop?.stopStatus === 'COMPLETED' && stop?.actualArrival == null) {
        findings.push(`Envío ${ref} tiene stop completado sin actualArrival`);
      }
      if ((stop?.stopStatus === 'IN_TRANSIT' || stop?.stopStatus === 'COMPLETED') && !stop?.flightCode) {
        findings.push(`Envío ${ref} tiene stop ${stop?.stopOrder} sin flightCode en estado ${stop?.stopStatus}`);
      }
    }

    if (shipment.status === 'DELIVERED') {
      const allCompleted = shipment.stops.every((stop) => stop?.stopStatus === 'COMPLETED');
      if (!allCompleted) {
        findings.push(`Envío entregado ${ref} mantiene stops no completados`);
      }
      if (shipment.deliveredAt == null) {
        findings.push(`Detalle de envío entregado ${ref} no tiene deliveredAt`);
      }
    }
    if (shipment.status === 'PENDING' && completedCount > 0) {
      findings.push(`Envío pendiente ${ref} ya tiene stops completados`);
    }
    if (shipment.status === 'DELIVERED' && shipment.currentLeg != null) {
      findings.push(`Envío entregado ${ref} mantiene currentLeg informado`);
    }
    if (shipment.status === 'DELIVERED' && shipment.nextLeg != null) {
      findings.push(`Envío entregado ${ref} mantiene nextLeg informado`);
    }
  }

  for (const detail of flightDetails) {
    const flight = detail?.flight;
    if (!flight) continue;
    const ref = flight.flightCode ?? flight.id ?? 'vuelo-detalle';
    const assigned = Array.isArray(detail.assignedShipments) ? detail.assignedShipments : [];
    const luggageSum = assigned.reduce((sum, shipment) => sum + (Number.isFinite(shipment?.luggageCount) ? shipment.luggageCount : 0), 0);
    if (flight.currentLoad > flight.maxCapacity) {
      findings.push(`Detalle de vuelo ${ref} excede capacidad ${flight.currentLoad}/${flight.maxCapacity}`);
    }
    if (flight.availableCapacity !== flight.maxCapacity - flight.currentLoad) {
      findings.push(`Detalle de vuelo ${ref} tiene availableCapacity inconsistente`);
    }
    if (assigned.length > 0 && luggageSum > flight.maxCapacity) {
      findings.push(`Detalle de vuelo ${ref} tiene envíos asignados que exceden capacidad total=${luggageSum}/${flight.maxCapacity}`);
    }
    for (const shipment of assigned) {
      if (!Number.isInteger(shipment?.stopOrder) || shipment.stopOrder < 0) {
        findings.push(`Detalle de vuelo ${ref} tiene shipment ${shipment?.shipmentCode ?? shipment?.shipmentId} con stopOrder inválido=${shipment?.stopOrder}`);
      }
      if (!['PENDING', 'IN_ROUTE', 'DELIVERED', 'DELAYED', 'CRITICAL'].includes(String(shipment?.status))) {
        findings.push(`Detalle de vuelo ${ref} tiene shipment ${shipment?.shipmentCode ?? shipment?.shipmentId} con status inválido=${shipment?.status}`);
      }
    }
  }

  if (kpis) {
    const activeTotal = (data.shipmentsPending.body?.totalElements ?? pendingShipments.length) + (data.shipmentsInRoute.body?.totalElements ?? inRouteShipments.length);
    const delayedTotal = criticalShipments.filter((shipment) => shipment?.status === 'DELAYED').length;
    const criticalTotal = criticalShipments.length;
    const deliveredTotal = data.shipmentsDelivered.body?.totalElements ?? deliveredShipments.length;
    if (countMismatch(kpis.active, activeTotal, Math.max(10, Math.ceil(activeTotal * 0.01)))) {
      findings.push(`KPIs finales inconsistentes: active=${kpis.active} vs pending+inRoute=${activeTotal}`);
    }
    if (countMismatch(kpis.delayed, delayedTotal, 0)) {
      findings.push(`KPIs finales inconsistentes: delayed=${kpis.delayed} vs critical-list delayed=${delayedTotal}`);
    }
    if (countMismatch(kpis.critical, criticalTotal, 0)) {
      findings.push(`KPIs finales inconsistentes: critical=${kpis.critical} vs critical-list total=${criticalTotal}`);
    }
    if (countMismatch(kpis.delivered, deliveredTotal, 0)) {
      findings.push(`KPIs finales inconsistentes: delivered=${kpis.delivered} vs delivered-total=${deliveredTotal}`);
    }
  }

  if (findings.length === 0) {
    evidence.push('El estado final backend/frontend quedó consistente en vuelos, envíos, capacidades, rutas y UX visible');
  }
  if (airports.length > 0 && completedFlights.length > 0) {
    evidence.push(`Validación final ampliada sobre ${airports.length} aeropuertos y ${completedFlights.length} vuelos completados del día operativo final`);
  }
  if (data.shipmentDetails.length > 0) {
    evidence.push(`Se validaron detalles completos de ${data.shipmentDetails.length} envíos representativos del cierre`);
  }
  if (data.flightDetails.length > 0) {
    evidence.push(`Se validaron detalles completos de ${data.flightDetails.length} vuelos representativos del cierre`);
  }

  return { findings, evidence };
}

async function main() {
  await ensureDir(ARTIFACTS_DIR);
  const browser = await chromium.launch({ headless: true, executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe' });
  const page = await browser.newPage({ viewport: { width: 1600, height: 1000 } });
  const samples = [];
  const issues = [];
  const successSignals = [];
  const visualTracker = {
    startedAt: new Date().toISOString(),
    references: [],
    flightStates: new Map(),
    evidence: [],
  };
  let startInfo = null;
  const startedAuditAt = Date.now();

  try {
    startInfo = await configureAndStart();
    await writeJsonArtifact('start.json', startInfo);

    let stagnantTickCount = 0;
    let stagnantSimulatedTimeCount = 0;
    let backendFailureCount = 0;
    let uiFailureCount = 0;
    let lastTickAt = null;
    let lastSimulatedNow = null;

    while (true) {
      const timestamp = new Date().toISOString();
      const backend = await sampleBackend();
      let ui = { runtime: [], cards: {}, body: '', error: null };
      let visual = { error: null, uiFlights: [], backendProjectedFlights: [], mapReady: false, viewport: null };
      try {
        ui = await captureUiWithRetry(page);
        visual = await captureFlightVisualState(page, backend.mapFlights.body ?? []);
        uiFailureCount = 0;
      } catch (error) {
        uiFailureCount += 1;
        ui = {
          runtime: [],
          cards: {},
          body: '',
          error: error instanceof Error ? (error.stack ?? error.message) : String(error),
        };
        visual = {
          error: ui.error,
          uiFlights: [],
          backendProjectedFlights: [],
          mapReady: false,
          viewport: null,
        };
      }

      if (hasBackendFailure(backend)) {
        backendFailureCount += 1;
      } else {
        backendFailureCount = 0;
      }

      const uiIssues = compareBackendUi(backend, ui);
      const businessIssues = analyzeBusinessRules(backend);
      const visualAnalysis = analyzeFlightVisuals(
        visualTracker,
        timestamp,
        backend.state?.body?.simulatedNow ?? null,
        backend.mapFlights.body ?? [],
        visual,
      );
      visualTracker.evidence.push(...visualAnalysis.evidence);
      const tickAt = backend.state?.body?.lastTickAt ?? null;
      const simulatedNow = backend.state?.body?.simulatedNow ?? null;
      if (tickAt && tickAt === lastTickAt && backend.state?.body?.running === true) {
        stagnantTickCount += 1;
      } else {
        stagnantTickCount = 0;
      }
      if (simulatedNow && simulatedNow === lastSimulatedNow && backend.state?.body?.running === true) {
        stagnantSimulatedTimeCount += 1;
      } else {
        stagnantSimulatedTimeCount = 0;
      }
      lastTickAt = tickAt;
      lastSimulatedNow = simulatedNow;

      if (stagnantTickCount >= MAX_STAGNANT_SAMPLES) {
        businessIssues.push(`lastTickAt no cambió en ${stagnantTickCount + 1} muestras consecutivas`);
      }
      if (stagnantSimulatedTimeCount >= MAX_STAGNANT_SAMPLES) {
        businessIssues.push(`simulatedNow no cambió en ${stagnantSimulatedTimeCount + 1} muestras consecutivas`);
      }
      if (backendFailureCount > 0) {
        businessIssues.push(`Fallos consecutivos de backend: ${backendFailureCount}`);
        businessIssues.push(...listBackendFailures(backend));
      }
      if (ui.error) {
        businessIssues.push(`Fallo capturando UI: ${ui.error}`);
      }
      if (uiFailureCount > 0) {
        businessIssues.push(`Fallos consecutivos de UI: ${uiFailureCount}`);
      }

      const sample = {
        timestamp,
        backend: {
          state: backend.state.body,
          overview: backend.overview.body,
          system: backend.system.body,
          alerts: backend.alerts.body,
          mapFlights: backend.mapFlights.body,
          mapShipments: backend.mapShipments.body,
          alertsCount: safeCount(backend.alerts.body),
          mapFlightsCount: safeCount(backend.mapFlights.body),
          mapShipmentsCount: safeCount(backend.mapShipments.body),
          collapseRisk: backend.collapseRisk.body,
          routesCount: safeCount(backend.routes.body),
          nodeOpkc: backend.nodeOpkc.body,
          shipmentsPage: backend.shipments.body,
          errors: {
            state: backend.state.error ?? null,
            overview: backend.overview.error ?? null,
            system: backend.system.error ?? null,
            alerts: backend.alerts.error ?? null,
            mapFlights: backend.mapFlights.error ?? null,
            mapShipments: backend.mapShipments.error ?? null,
            collapseRisk: backend.collapseRisk.error ?? null,
            routes: backend.routes.error ?? null,
            nodeOpkc: backend.nodeOpkc.error ?? null,
            shipments: backend.shipments.error ?? null,
          },
        },
        ui: {
          runtime: ui.runtime,
          cards: ui.cards,
          flightMarkerButtons: ui.flightMarkerButtons,
          error: ui.error,
          visual,
        },
        issues: [...uiIssues, ...businessIssues, ...visualAnalysis.issues],
      };
      samples.push(sample);
      if (sample.issues.length > 0) {
        issues.push({ timestamp, issues: sample.issues });
      }

      await writeJsonArtifact('samples.json', samples);
      await writeJsonArtifact('issues.json', issues);
      await writeJsonArtifact('visual-flight-evidence.json', visualTracker.evidence);

      const elapsedMinutes = (Date.now() - startedAuditAt) / 60_000;
      const hardFailure = backendFailureCount >= MAX_API_FAILURES
        || uiFailureCount >= MAX_UI_FAILURES
        || stagnantTickCount >= MAX_STAGNANT_SAMPLES
        || stagnantSimulatedTimeCount >= MAX_STAGNANT_SAMPLES
        || elapsedMinutes >= MAX_AUDIT_MINUTES;
      if (hardFailure) {
        const positiveSignals = evaluatePositiveSignals(samples);
        successSignals.splice(0, successSignals.length, ...positiveSignals.evidence);
        const visualOutcome = evaluateVisualOutcome(visualTracker);
        successSignals.push(...visualOutcome.evidence);
        if (positiveSignals.findings.length > 0) {
          issues.push({ timestamp, issues: positiveSignals.findings });
          await writeJsonArtifact('issues.json', issues);
        }
        if (visualOutcome.findings.length > 0) {
          issues.push({ timestamp, issues: visualOutcome.findings });
          await writeJsonArtifact('issues.json', issues);
        }
        await writeJsonArtifact('success-signals.json', successSignals);
        await writeJsonArtifact('visual-flight-summary.json', summarizeVisualTracker(visualTracker));
        const watchdog = {
          timestamp,
          backendFailureCount,
          uiFailureCount,
          stagnantTickCount,
          stagnantSimulatedTimeCount,
          elapsedMinutes,
          state: backend.state?.body ?? null,
          backendFailures: listBackendFailures(backend),
          uiError: ui.error,
          successSignals,
        };
        await writeJsonArtifact('watchdog.json', watchdog);
        await captureBackendLogs();
        await captureDockerStats();
        throw new Error(`Auditoría abortada por watchdog: ${JSON.stringify(watchdog)}`);
      }

      const state = backend.state.body;
      if (MAX_SAMPLES > 0 && samples.length >= MAX_SAMPLES) {
        break;
      }
      if (state && state.running === false && state.paused === false) {
        break;
      }
      await sleep(SAMPLE_MS);
    }

    const positiveSignals = evaluatePositiveSignals(samples);
    successSignals.push(...positiveSignals.evidence);
    if (positiveSignals.findings.length > 0) {
      issues.push({ timestamp: new Date().toISOString(), issues: positiveSignals.findings });
      await writeJsonArtifact('issues.json', issues);
    }
    const visualOutcome = evaluateVisualOutcome(visualTracker);
    successSignals.push(...visualOutcome.evidence);
    if (visualOutcome.findings.length > 0) {
      issues.push({ timestamp: new Date().toISOString(), issues: visualOutcome.findings });
      await writeJsonArtifact('issues.json', issues);
    }

    const finalState = samples[samples.length - 1]?.backend?.state ?? null;
    if (finalState?.running === false && finalState?.paused === false) {
      const finalData = await fetchFinalConsistencyData(finalState, samples);
      await writeJsonArtifact('final-consistency-data.json', {
        effectiveDate: finalData.effectiveDate,
        state: finalData.state.body,
        overview: finalData.overview.body,
        system: finalData.system.body,
        alertsCount: safeCount(finalData.alerts.body),
        mapFlightsCount: safeCount(finalData.mapFlights.body),
        mapShipmentsCount: safeCount(finalData.mapShipments.body),
        routesCount: safeCount(finalData.routes.body),
        airportsCount: safeCount(finalData.airports.body),
        bottlenecksCount: safeCount(finalData.bottlenecks.body),
        results: finalData.results.body,
        flightsScheduled: finalData.flightsScheduled.body?.totalElements ?? safeCount(finalData.flightsScheduled.body?.content),
        flightsInFlight: finalData.flightsInFlight.body?.totalElements ?? safeCount(finalData.flightsInFlight.body?.content),
        flightsCompleted: finalData.flightsCompleted.body?.totalElements ?? safeCount(finalData.flightsCompleted.body?.content),
        shipmentsPending: finalData.shipmentsPending.body?.totalElements ?? safeCount(finalData.shipmentsPending.body?.content),
        shipmentsInRoute: finalData.shipmentsInRoute.body?.totalElements ?? safeCount(finalData.shipmentsInRoute.body?.content),
        shipmentsDelivered: finalData.shipmentsDelivered.body?.totalElements ?? safeCount(finalData.shipmentsDelivered.body?.content),
        shipmentsCritical: safeCount(finalData.shipmentsCritical.body),
        sampledShipmentIds: finalData.shipmentDetails.map((entry) => entry.id),
        sampledFlightIds: finalData.flightDetails.map((entry) => entry.id),
      });
      const finalUi = await captureUiWithRetry(page);
      await writeJsonArtifact('final-ui.json', finalUi);
      const finalConsistency = evaluateFinalConsistency(finalData, finalUi);
      await writeJsonArtifact('final-consistency.json', finalConsistency);
      await writeJsonArtifact('visual-flight-summary.json', summarizeVisualTracker(visualTracker));
      successSignals.push(...finalConsistency.evidence);
      if (finalConsistency.findings.length > 0) {
        issues.push({ timestamp: new Date().toISOString(), issues: finalConsistency.findings });
        await writeJsonArtifact('issues.json', issues);
      }
    }

    await writeJsonArtifact('success-signals.json', successSignals);
    await writeJsonArtifact('visual-flight-summary.json', summarizeVisualTracker(visualTracker));

    const finalReport = {
      startedAt: startInfo?.state?.startedAt ?? null,
      finishedAt: new Date().toISOString(),
      samples: samples.length,
      issuesFound: issues.length,
      distinctIssues: [...new Set(issues.flatMap((entry) => entry.issues))],
      successSignals,
      artifactsDir: ARTIFACTS_DIR,
    };
    await writeJsonArtifact('final-report.json', finalReport);
    process.stdout.write(JSON.stringify(finalReport, null, 2));
  } finally {
    await browser.close();
  }
}

main().catch(async (error) => {
  await ensureDir(ARTIFACTS_DIR);
  await fs.writeFile(path.join(ARTIFACTS_DIR, 'fatal-error.txt'), error instanceof Error ? (error.stack ?? error.message) : String(error));
  console.error(error);
  process.exit(1);
});
