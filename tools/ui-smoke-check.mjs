import { chromium } from 'playwright';

const BASE_URL = 'http://127.0.0.1:3000';

async function main() {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const logs = [];

  page.on('console', (msg) => logs.push(`[console:${msg.type()}] ${msg.text()}`));
  page.on('pageerror', (err) => logs.push(`[pageerror] ${err.message}`));
  page.on('requestfailed', (req) => logs.push(`[requestfailed] ${req.url()} ${req.failure()?.errorText ?? ''}`));

  const results = {};
  try {
    await page.goto(BASE_URL, { waitUntil: 'networkidle', timeout: 120000 });
    await page.waitForTimeout(4000);
    results.home = {
      title: await page.locator('h1').first().textContent().catch(() => null),
      hasClock: await page.locator('text=Reloj simulado').count(),
      hasFlightsVisible: await page.locator('text=Vuelos visibles').count(),
      hasShipmentsVisible: await page.locator('text=Envíos visibles').count(),
    };

    await page.goto(`${BASE_URL}/flights`, { waitUntil: 'networkidle', timeout: 120000 });
    await page.waitForTimeout(3000);
    results.flights = {
      title: await page.locator('h1').first().textContent().catch(() => null),
      hasProgramados: await page.locator('text=Programados').count(),
      bodySnippet: (await page.locator('body').textContent().catch(() => '')).slice(0, 400),
    };

    await page.goto(`${BASE_URL}/shipments`, { waitUntil: 'networkidle', timeout: 120000 });
    await page.waitForTimeout(3000);
    results.shipments = {
      title: await page.locator('h1').first().textContent().catch(() => null),
      hasCreate: await page.locator('text=Crear envío').count(),
      hasSelection: await page.locator('text=Selecciona un envío').count(),
      bodySnippet: (await page.locator('body').textContent().catch(() => '')).slice(0, 400),
    };
  } finally {
    await browser.close();
  }

  process.stdout.write(JSON.stringify({ results, logs }, null, 2));
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
