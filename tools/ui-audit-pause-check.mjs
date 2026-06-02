import { chromium } from 'playwright';

const BASE_URL = 'http://127.0.0.1:3000';
const API_URL = 'http://127.0.0.1:8080';

const scenarios = [
  {
    key: 'PERIOD_SIMULATION',
    speed: '20x',
    runMs: 25000,
  },
  {
    key: 'COLLAPSE_TEST',
    speed: '20x',
    runMs: 25000,
    extraConfig: {
      cancellationRatePct: 20,
      intraNodeCapacity: 200,
      interNodeCapacity: 200,
    },
  },
];

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function api(path, init) {
  const response = await fetch(`${API_URL}${path}`, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...(init?.headers ?? {}),
    },
  });
  const text = await response.text();
  return text ? JSON.parse(text) : null;
}

async function stopSimulation() {
  try {
    await api('/api/simulation/stop', { method: 'POST' });
  } catch {}
}

async function configureScenario(scenario) {
  await api('/api/simulation/configure', {
    method: 'POST',
    body: JSON.stringify({
      scenario: scenario.key,
      simulationDays: 5,
      scenarioStartDate: '2028-04-15',
      normalThresholdPct: 65,
      warningThresholdPct: 85,
      ...scenario.extraConfig,
    }),
  });
}

async function text(page, selector) {
  const value = await page.locator(selector).first().textContent({ timeout: 10000 }).catch(() => null);
  return value?.trim() ?? null;
}

async function collect(page) {
  return {
    runtimeStatus: await text(page, '.state-panel-copy'),
    visibleFlights: await text(page, 'text=Vuelos visibles >> xpath=.. >> strong'),
    visibleShipments: await text(page, 'text=Envíos visibles >> xpath=.. >> strong'),
    simulatedClock: await text(page, 'text=Reloj simulado >> xpath=.. >> strong'),
  };
}

async function waitForEnabled(page, name, timeoutMs = 15000) {
  const locator = page.getByRole('button', { name });
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    if (await locator.isEnabled().catch(() => false)) return true;
    await page.waitForTimeout(500);
  }
  return false;
}

async function runScenario(browser, scenario) {
  const context = await browser.newContext({ viewport: { width: 1600, height: 1000 } });
  const page = await context.newPage();
  try {
    await stopSimulation();
    await configureScenario(scenario);
    await page.goto(BASE_URL, { waitUntil: 'networkidle' });
    await page.getByRole('button', { name: 'Iniciar' }).click();
    await page.waitForTimeout(3000);
    if (await waitForEnabled(page, scenario.speed)) {
      await page.getByRole('button', { name: scenario.speed }).click();
    }
    await page.waitForTimeout(scenario.runMs);
    const beforePause = await collect(page);
    await page.getByRole('button', { name: 'Pausar' }).click();
    await page.waitForTimeout(1000);
    const paused = await collect(page);
    await page.waitForTimeout(3000);
    const pausedAfterWait = await collect(page);
    await page.getByRole('button', { name: 'Detener' }).click();
    await page.waitForTimeout(1000);
    return {
      scenario: scenario.key,
      beforePause,
      paused,
      pausedAfterWait,
      pauseFrozen: paused.simulatedClock === pausedAfterWait.simulatedClock,
    };
  } finally {
    await stopSimulation();
    await context.close();
  }
}

async function main() {
  const browser = await chromium.launch({ headless: true });
  try {
    const results = [];
    for (const scenario of scenarios) {
      results.push(await runScenario(browser, scenario));
    }
    process.stdout.write(JSON.stringify(results, null, 2));
  } finally {
    await browser.close();
  }
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
