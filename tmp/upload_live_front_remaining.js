const { chromium } = require('playwright');
const path = require('path');
const fs = require('fs');
(async () => {
  fs.mkdirSync(path.resolve('screenshots'), { recursive: true });
  const browser = await chromium.launch({ headless: true, executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe' });
  const context = await browser.newContext({ viewport: { width: 1440, height: 1200 }, ignoreHTTPSErrors: true });
  const page = await context.newPage();
  const errors = [];
  const responses = [];
  page.on('console', msg => { if (msg.type() === 'error') errors.push(msg.text()); });
  page.on('pageerror', err => errors.push(err.message));
  page.on('response', async res => {
    if (res.url().includes('/api/shipments/upload-live')) {
      let body = '';
      try { body = await res.text(); } catch {}
      responses.push({ status: res.status(), body });
    }
  });
  await page.goto('https://1inf54-984-3c.inf.pucp.edu.pe/registro', { waitUntil: 'networkidle', timeout: 80000 });
  const files = ['SABE.txt','EKCH.txt','VIDP.txt'].map(f => path.resolve('tmp', 'live_uploads', f));
  await page.locator('input[type="file"]').setInputFiles(files);
  await page.waitForFunction(() => document.body.innerText.includes('SABE.txt') && document.body.innerText.includes('EKCH.txt') && document.body.innerText.includes('VIDP.txt'), null, { timeout: 180000 });
  await page.waitForFunction(() => !document.body.innerText.includes('Cargando y planificando'), null, { timeout: 180000 });
  await page.waitForTimeout(1500);
  const text = await page.locator('body').innerText();
  const shot = path.resolve('screenshots', 'vm-registro-live-upload-final.png');
  await page.screenshot({ path: shot, fullPage: true });
  await browser.close();
  console.log(JSON.stringify({ shot, responses: responses.map(r => ({ status: r.status, summary: r.body.slice(0, 220) })), responseCount: responses.length, errors, hasSessionCount18: /Registrados en esta sesión\s+18/i.test(text), sessionMatch: (text.match(/Registrados en esta sesión\s+\d+/i)||[])[0], excerpt: text.slice(-2500) }, null, 2));
})().catch(err => { console.error(err); process.exit(1); });
