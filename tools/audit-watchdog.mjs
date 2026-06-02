import fs from 'node:fs/promises';
import path from 'node:path';

const ROOT = process.cwd();
const API_URL = 'http://127.0.0.1:8080';
const AUDIT_DIR = process.argv[2];
const SAMPLE_MS = Number(process.env.WATCHDOG_SAMPLE_MS ?? '30000');

if (!AUDIT_DIR) {
  throw new Error('Uso: node tools/audit-watchdog.mjs <artifact-dir>');
}

const LOG_FILE = path.join(AUDIT_DIR, 'watchdog-monitor.log');
const STATE_FILE = path.join(AUDIT_DIR, 'watchdog-monitor-state.json');

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function exists(filePath) {
  try {
    await fs.access(filePath);
    return true;
  } catch {
    return false;
  }
}

async function readJson(filePath) {
  try {
    return JSON.parse(await fs.readFile(filePath, 'utf8'));
  } catch {
    return null;
  }
}

async function statOrNull(filePath) {
  try {
    return await fs.stat(filePath);
  } catch {
    return null;
  }
}

async function appendLog(payload) {
  await fs.appendFile(LOG_FILE, `${JSON.stringify(payload)}\n`);
}

async function fetchJson(endpoint, timeoutMs = 15000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const res = await fetch(`${API_URL}${endpoint}`, { signal: controller.signal });
    const text = await res.text();
    return {
      ok: res.ok,
      status: res.status,
      body: text ? JSON.parse(text) : null,
    };
  } catch (error) {
    return {
      ok: false,
      status: 0,
      body: null,
      error: error instanceof Error ? error.message : String(error),
    };
  } finally {
    clearTimeout(timer);
  }
}

async function auditProcessRunning() {
  const cmd = "@(Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'node.exe' -and $_.CommandLine -like '*period-simulation-audit.mjs*' }).Count";
  const { spawn } = await import('node:child_process');
  return new Promise((resolve) => {
    const child = spawn('powershell', ['-NoProfile', '-Command', cmd], {
      cwd: ROOT,
      windowsHide: true,
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    let stdout = '';
    child.stdout.on('data', (chunk) => { stdout += chunk.toString(); });
    child.on('close', () => {
      resolve(Number(stdout.trim() || '0') > 0);
    });
  });
}

async function main() {
  const samplesPath = path.join(AUDIT_DIR, 'samples.json');
  const issuesPath = path.join(AUDIT_DIR, 'issues.json');
  const finalReportPath = path.join(AUDIT_DIR, 'final-report.json');
  const watchdogPath = path.join(AUDIT_DIR, 'watchdog.json');
  const fatalPath = path.join(AUDIT_DIR, 'fatal-error.txt');

  let lastSamplesSize = null;
  let lastIssuesSize = null;
  let lastTickAt = null;
  let lastSimulatedNow = null;

  await appendLog({ timestamp: new Date().toISOString(), status: 'watchdog-started', auditDir: AUDIT_DIR });

  while (true) {
    const timestamp = new Date().toISOString();
    const [processAlive, state, samplesStat, issuesStat, finalExists, watchdogExists, fatalExists] = await Promise.all([
      auditProcessRunning(),
      fetchJson('/api/simulation/state'),
      statOrNull(samplesPath),
      statOrNull(issuesPath),
      exists(finalReportPath),
      exists(watchdogPath),
      exists(fatalPath),
    ]);

    const currentTickAt = state.body?.lastTickAt ?? null;
    const currentSimulatedNow = state.body?.simulatedNow ?? null;
    const samplesGrowing = samplesStat ? (lastSamplesSize == null || samplesStat.size > lastSamplesSize) : false;
    const issuesGrowing = issuesStat ? (lastIssuesSize == null || issuesStat.size > lastIssuesSize) : false;
    const issues = await readJson(issuesPath);

    const snapshot = {
      timestamp,
      processAlive,
      stateOk: state.ok,
      running: state.body?.running ?? null,
      paused: state.body?.paused ?? null,
      simulatedNow: currentSimulatedNow,
      lastTickAt: currentTickAt,
      lastTickAdvanced: currentTickAt != null && currentTickAt !== lastTickAt,
      simulatedNowAdvanced: currentSimulatedNow != null && currentSimulatedNow !== lastSimulatedNow,
      samplesSize: samplesStat?.size ?? null,
      samplesGrowing,
      issuesSize: issuesStat?.size ?? null,
      issuesGrowing,
      issuesCount: Array.isArray(issues) ? issues.length : null,
      finalExists,
      watchdogExists,
      fatalExists,
      stateError: state.error ?? null,
    };

    await fs.writeFile(STATE_FILE, JSON.stringify(snapshot, null, 2));
    await appendLog(snapshot);

    lastSamplesSize = samplesStat?.size ?? lastSamplesSize;
    lastIssuesSize = issuesStat?.size ?? lastIssuesSize;
    lastTickAt = currentTickAt ?? lastTickAt;
    lastSimulatedNow = currentSimulatedNow ?? lastSimulatedNow;

    if (finalExists || watchdogExists || fatalExists) {
      await appendLog({ timestamp: new Date().toISOString(), status: 'watchdog-finished', finalExists, watchdogExists, fatalExists });
      break;
    }

    if (!processAlive && !state.body?.running) {
      await appendLog({ timestamp: new Date().toISOString(), status: 'watchdog-finished-no-process-no-sim' });
      break;
    }

    await sleep(SAMPLE_MS);
  }
}

main().catch(async (error) => {
  await appendLog({ timestamp: new Date().toISOString(), status: 'watchdog-error', error: error instanceof Error ? (error.stack ?? error.message) : String(error) });
  process.exit(1);
});
