import fs from 'node:fs/promises';
import path from 'node:path';
import { spawn, execFile } from 'node:child_process';
import { promisify } from 'node:util';

const ROOT = process.cwd();
const LOG_DIR = path.resolve(ROOT, 'artifacts', 'period-audit-loop');
const LOOP_LOG = path.join(LOG_DIR, 'loop.log');
const LOOP_STATE = path.join(LOG_DIR, 'loop-state.json');
const RETRY_DELAY_MS = Number(process.env.RETRY_DELAY_MS ?? '20000');
const SAMPLE_MS = process.env.SAMPLE_MS ?? '30000';
const MAX_STAGNANT_SAMPLES = process.env.MAX_STAGNANT_SAMPLES ?? '6';
const MAX_API_FAILURES = process.env.MAX_API_FAILURES ?? '3';
const MAX_UI_FAILURES = process.env.MAX_UI_FAILURES ?? '3';
const MAX_AUDIT_MINUTES = process.env.MAX_AUDIT_MINUTES ?? '90';
const execFileAsync = promisify(execFile);

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function ensureDir(dir) {
  await fs.mkdir(dir, { recursive: true });
}

async function appendLog(message) {
  const line = `[${new Date().toISOString()}] ${message}\n`;
  await fs.appendFile(LOOP_LOG, line);
}

async function writeState(payload) {
  await fs.writeFile(LOOP_STATE, JSON.stringify(payload, null, 2));
}

async function fileExists(filePath) {
  try {
    await fs.access(filePath);
    return true;
  } catch {
    return false;
  }
}

async function listAuditDirs() {
  const artifactsRoot = path.resolve(ROOT, 'artifacts');
  const entries = await fs.readdir(artifactsRoot, { withFileTypes: true }).catch(() => []);
  return entries
    .filter((entry) => entry.isDirectory() && entry.name.startsWith('period-audit-'))
    .map((entry) => path.join(artifactsRoot, entry.name))
    .sort();
}

async function latestAuditDir(beforeStartMs) {
  const dirs = await listAuditDirs();
  const withStats = await Promise.all(dirs.map(async (dir) => ({ dir, stat: await fs.stat(dir) })));
  return withStats
    .filter(({ stat }) => stat.mtimeMs >= beforeStartMs - 10_000)
    .sort((a, b) => b.stat.mtimeMs - a.stat.mtimeMs)
    .map(({ dir }) => dir)[0] ?? null;
}

async function readJsonIfExists(filePath) {
  if (!(await fileExists(filePath))) return null;
  try {
    return JSON.parse(await fs.readFile(filePath, 'utf8'));
  } catch {
    return null;
  }
}

async function runPowerShell(command, timeout = 180_000) {
  try {
    const { stdout, stderr } = await execFileAsync('powershell', ['-NoProfile', '-Command', command], {
      cwd: ROOT,
      timeout,
      maxBuffer: 4 * 1024 * 1024,
    });
    return { ok: true, stdout, stderr };
  } catch (error) {
    return {
      ok: false,
      stdout: error.stdout ?? '',
      stderr: error.stderr ?? '',
      error: error instanceof Error ? (error.stack ?? error.message) : String(error),
    };
  }
}

async function postStop() {
  return runPowerShell("try { Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8080/api/simulation/stop' -TimeoutSec 120 | ConvertTo-Json -Compress } catch { $_ | Out-String }");
}

async function configureDefaultSimulation() {
  return runPowerShell("Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8080/api/simulation/configure' -ContentType 'application/json' -Body '{\"scenario\":\"PERIOD_SIMULATION\",\"simulationDays\":5,\"executionMinutes\":30,\"scenarioStartDate\":\"2029-06-16\",\"initialVolumeAvg\":40,\"initialVolumeVariance\":10,\"flightFrequencyMultiplier\":1,\"cancellationRatePct\":20,\"intraNodeCapacity\":200,\"interNodeCapacity\":200,\"normalThresholdPct\":70,\"warningThresholdPct\":90,\"primaryAlgorithm\":\"GENETIC\",\"secondaryAlgorithm\":\"GENETIC\"}' | ConvertTo-Json -Compress");
}

async function recreateBackend() {
  return runPowerShell("docker compose rm -sf backend; docker compose up -d backend", 600_000);
}

async function captureServiceStatus() {
  const [ps, stats] = await Promise.all([
    runPowerShell("docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.RunningFor}}'", 30_000),
    runPowerShell("docker stats --no-stream --format 'table {{.Name}}\t{{.MemUsage}}\t{{.CPUPerc}}'", 30_000),
  ]);
  await fs.writeFile(path.join(LOG_DIR, 'service-status.txt'), `${ps.stdout}\n${stats.stdout}`);
}

function classifyFailure({ exitCode, stderr, fatalErrorText, watchdog, issues }) {
  const haystack = [stderr, fatalErrorText, JSON.stringify(watchdog), JSON.stringify(issues)].filter(Boolean).join('\n');
  if (/outofmemory|heap space|poller|acceptor/i.test(haystack)) return 'backend_memory';
  if (/lastTickAt no cambió|simulatedNow no cambió|watchdog/i.test(haystack)) return 'stalled_simulation';
  if (/Desfase marcadores de aviones|KPI vuelos visibles UI=.*marcadores reales/i.test(haystack)) return 'ui_map_desync';
  if (/state: status=0|overview: status=0|system: status=0|mapFlights: status=0/i.test(haystack)) return 'backend_unresponsive';
  if (exitCode === 0) return 'none';
  return 'unknown';
}

async function applyCorrection(classification, context) {
  const actions = [];

  if (classification === 'backend_memory' || classification === 'backend_unresponsive') {
    actions.push('recreate_backend');
    const recreate = await recreateBackend();
    await appendLog(`Corrective action recreate_backend ok=${recreate.ok}`);
  }

  if (classification === 'stalled_simulation' || classification === 'unknown') {
    actions.push('stop_simulation');
    const stop = await postStop();
    await appendLog(`Corrective action stop_simulation ok=${stop.ok}`);
  }

  if (classification === 'stalled_simulation' || classification === 'backend_memory' || classification === 'backend_unresponsive' || classification === 'unknown') {
    actions.push('restore_default_config');
    const configure = await configureDefaultSimulation();
    await appendLog(`Corrective action restore_default_config ok=${configure.ok}`);
  }

  if (classification === 'ui_map_desync') {
    actions.push('collect_ui_desync_artifacts');
  }

  await captureServiceStatus();
  return { classification, actions, context };
}

async function runAuditAttempt(attempt) {
  const startedAtMs = Date.now();
  await appendLog(`Starting audit attempt ${attempt}`);
  await writeState({ status: 'running', attempt, startedAt: new Date(startedAtMs).toISOString() });

  return new Promise((resolve) => {
    const env = {
      ...process.env,
      SAMPLE_MS,
      MAX_STAGNANT_SAMPLES,
      MAX_API_FAILURES,
      MAX_UI_FAILURES,
      MAX_AUDIT_MINUTES,
    };
    const child = spawn('node', ['tools/period-simulation-audit.mjs'], {
      cwd: ROOT,
      env,
      stdio: ['ignore', 'pipe', 'pipe'],
      windowsHide: true,
    });

    let stdout = '';
    let stderr = '';
    child.stdout.on('data', (chunk) => { stdout += chunk.toString(); });
    child.stderr.on('data', (chunk) => { stderr += chunk.toString(); });
    child.on('close', async (code) => {
      const auditDir = await latestAuditDir(startedAtMs);
      resolve({ code, stdout, stderr, auditDir, startedAtMs });
    });
  });
}

async function readFailureContext(result) {
  if (!result.auditDir) {
    return { fatalErrorText: null, watchdog: null, issues: null };
  }
  const fatalPath = path.join(result.auditDir, 'fatal-error.txt');
  const fatalErrorText = await fs.readFile(fatalPath, 'utf8').catch(() => null);
  const watchdog = await readJsonIfExists(path.join(result.auditDir, 'watchdog.json'));
  const issues = await readJsonIfExists(path.join(result.auditDir, 'issues.json'));
  return { fatalErrorText, watchdog, issues };
}

async function main() {
  await ensureDir(LOG_DIR);
  await appendLog('Audit loop booted');

  let attempt = 0;
  while (true) {
    attempt += 1;
    const result = await runAuditAttempt(attempt);

    if (result.code === 0) {
      await appendLog(`Attempt ${attempt} completed successfully`);
      await writeState({
        status: 'completed',
        attempt,
        finishedAt: new Date().toISOString(),
        stdout: result.stdout.trim(),
        auditDir: result.auditDir,
      });
      return;
    }

    const failureContext = await readFailureContext(result);
    const classification = classifyFailure({
      exitCode: result.code,
      stderr: result.stderr,
      fatalErrorText: failureContext.fatalErrorText,
      watchdog: failureContext.watchdog,
      issues: failureContext.issues,
    });

    await appendLog(`Attempt ${attempt} failed with code ${result.code} classification=${classification}`);
    if (result.auditDir) {
      await appendLog(`Failed audit artifacts: ${result.auditDir}`);
    }
    if (result.stdout.trim()) {
      await appendLog(`stdout: ${result.stdout.trim()}`);
    }
    if (result.stderr.trim()) {
      await appendLog(`stderr: ${result.stderr.trim()}`);
    }
    if (failureContext.fatalErrorText) {
      await appendLog(`fatal-error: ${failureContext.fatalErrorText}`);
    }

    const correction = await applyCorrection(classification, {
      attempt,
      auditDir: result.auditDir,
      watchdog: failureContext.watchdog,
    });
    await appendLog(`Applied correction: ${JSON.stringify(correction)}`);

    await writeState({
      status: 'retrying',
      attempt,
      failedAt: new Date().toISOString(),
      exitCode: result.code,
      retryDelayMs: RETRY_DELAY_MS,
      classification,
      auditDir: result.auditDir,
      correction,
    });
    await sleep(RETRY_DELAY_MS);
  }
}

main().catch(async (error) => {
  await ensureDir(LOG_DIR);
  await appendLog(`Loop crashed: ${error instanceof Error ? (error.stack ?? error.message) : String(error)}`);
  process.exit(1);
});
