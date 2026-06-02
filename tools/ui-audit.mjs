import { chromium } from 'playwright';
import fs from 'node:fs/promises';

const BASE_URL = 'http://127.0.0.1:3000';
const API_URL = 'http://127.0.0.1:8080';

const scenarios = [
  {
    key: 'DAY_TO_DAY',
    speed: null,
    runMs: 15000,
  },
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
  } catch {
    // ignore cleanup failures
  }
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

async function textContent(page, selector) {
  const value = await page.locator(selector).first().textContent({ timeout: 10000 }).catch(() => null);
  return value?.trim() ?? null;
}

async function screenshot(page, name) {
  await page.screenshot({ path: `artifacts/${name}.png`, fullPage: true });
}

async function waitForEnabled(page, roleName, timeoutMs = 15000) {
  const locator = page.getByRole('button', { name: roleName });
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    if (await locator.isEnabled().catch(() => false)) {
      return true;
    }
    await page.waitForTimeout(500);
  }
  return false;
}

async function collectHome(page) {
  const startEnabled = await page.getByRole('button', { name: 'Iniciar' }).isEnabled().catch(() => false);
  const resumeEnabled = await page.getByRole('button', { name: 'Reanudar' }).isEnabled().catch(() => false);
  const pauseEnabled = await page.getByRole('button', { name: 'Pausar' }).isEnabled().catch(() => false);
  const stopEnabled = await page.getByRole('button', { name: 'Detener' }).isEnabled().catch(() => false);
  return {
    runtimeStatus: await textContent(page, '.state-panel-copy'),
    visibleFlights: await textContent(page, 'text=Vuelos visibles >> xpath=.. >> strong'),
    visibleShipments: await textContent(page, 'text=Envíos visibles >> xpath=.. >> strong'),
    alerts: await textContent(page, 'text=Alertas operativas >> xpath=.. >> strong'),
    simulatedClock: await textContent(page, 'text=Reloj simulado >> xpath=.. >> strong'),
    controls: { startEnabled, resumeEnabled, pauseEnabled, stopEnabled },
  };
}

async function runScenario(browser, scenario) {
  const context = await browser.newContext({ viewport: { width: 1600, height: 1000 } });
  const page = await context.newPage();
  const result = { scenario: scenario.key };

  try {
    await stopSimulation();
    await configureScenario(scenario);

    await page.goto(BASE_URL, { waitUntil: 'networkidle' });
    result.beforeStart = await collectHome(page);
    await screenshot(page, `${scenario.key.toLowerCase()}-home-before-start`);

    await page.getByRole('button', { name: 'Iniciar' }).click();
    await page.waitForTimeout(3000);

    if (scenario.speed) {
      const speedEnabled = await waitForEnabled(page, scenario.speed);
      result.speedButtonEnabled = speedEnabled;
      if (speedEnabled) {
        await page.getByRole('button', { name: scenario.speed }).click();
        await page.waitForTimeout(1500);
      }
    }

    await page.waitForTimeout(scenario.runMs);
    result.homeRunning = await collectHome(page);
    await screenshot(page, `${scenario.key.toLowerCase()}-home-running`);

    await page.goto(`${BASE_URL}/flights`, { waitUntil: 'networkidle' });
    await page.waitForTimeout(1500);
    result.flightsTitle = await textContent(page, 'h1');
    result.flightsSummary = await textContent(page, 'tbody');
    await screenshot(page, `${scenario.key.toLowerCase()}-flights`);

    await page.goto(`${BASE_URL}/shipments`, { waitUntil: 'networkidle' });
    await page.waitForTimeout(1500);
    result.shipmentsTitle = await textContent(page, 'h1');
    result.shipmentsSummary = await textContent(page, 'tbody');
    await screenshot(page, `${scenario.key.toLowerCase()}-shipments`);

    await page.goto(BASE_URL, { waitUntil: 'networkidle' });
    await page.waitForTimeout(1000);
    result.beforePause = await collectHome(page);
    if (result.beforePause.controls.pauseEnabled) {
      await page.getByRole('button', { name: 'Pausar' }).click();
      await page.waitForTimeout(1000);
      result.paused = await collectHome(page);
      const pausedClock = result.paused.simulatedClock;
      await page.waitForTimeout(3000);
      result.pausedAfterWait = await collectHome(page);
      result.pauseFrozen = pausedClock === result.pausedAfterWait.simulatedClock;

      if (result.paused.controls.resumeEnabled) {
        await page.getByRole('button', { name: 'Reanudar' }).click();
        await page.waitForTimeout(5000);
        result.resumed = await collectHome(page);
      }
    } else {
      result.paused = null;
      result.pausedAfterWait = null;
      result.pauseFrozen = null;
      result.resumed = null;
    }

    const stopState = await collectHome(page);
    if (stopState.controls.stopEnabled) {
      await page.getByRole('button', { name: 'Detener' }).click();
      await page.waitForTimeout(1500);
    }
    result.afterStop = await collectHome(page);
    await screenshot(page, `${scenario.key.toLowerCase()}-home-stopped`);

    result.apiState = await api('/api/simulation/state');
    result.apiOverview = await api('/api/dashboard/overview');
    result.apiMapFlights = await api('/api/dashboard/map-live-flights');
    result.apiMapShipments = await api('/api/dashboard/map-live');
  } finally {
    await stopSimulation();
    await context.close();
  }

  return result;
}

async function main() {
  await fs.mkdir('artifacts', { recursive: true });
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
