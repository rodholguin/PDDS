'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import type { BenchmarkJobState, DataImportLog, DatasetImportResult, ImportStatus } from '@/lib/types';
import { importApi, type ImportType } from '@/lib/api/importApi';

// ── Types ─────────────────────────────────────────────────────────────────

interface TabState {
  file:       File | null;
  loading:    boolean;
  result:     DataImportLog | null;
  error:      string | null;
  dragOver:   boolean;
  showErrors: boolean;
}

const TABS: { key: ImportType; label: string; emoji: string }[] = [
  { key: 'shipments', label: 'Envíos',       emoji: '🧳' },
  { key: 'airports',  label: 'Aeropuertos',  emoji: '✈' },
  { key: 'flights',   label: 'Vuelos',       emoji: '🛫' },
];

const INITIAL_TAB: TabState = {
  file: null, loading: false, result: null, error: null, dragOver: false, showErrors: false,
};

// ── Helpers ───────────────────────────────────────────────────────────────

function statusBadge(s: ImportStatus) {
  const cfg: Record<ImportStatus, { bg: string; color: string; label: string }> = {
    SUCCESS: { bg: '#22c55e18', color: '#22c55e', label: 'EXITOSO'  },
    PARTIAL: { bg: '#f59e0b18', color: '#f59e0b', label: 'PARCIAL'  },
    FAILED:  { bg: '#ef444418', color: '#ef4444', label: 'FALLIDO'  },
  };
  const c = cfg[s];
  return (
    <span className="text-[10px] font-semibold px-2 py-0.5 rounded-full"
          style={{ background: c.bg, color: c.color }}>
      {c.label}
    </span>
  );
}

function formatDate(iso: string) {
  return new Date(iso).toLocaleString('es-PE', {
    day: '2-digit', month: '2-digit', year: 'numeric',
    hour: '2-digit', minute: '2-digit',
  });
}

function guessType(fileName: string): string {
  const f = fileName.toLowerCase();
  if (f.includes('envio') || f.includes('shipment')) return 'Envíos';
  if (f.includes('airport') || f.includes('aeropuerto')) return 'Aeropuertos';
  if (f.includes('flight') || f.includes('vuelo')) return 'Vuelos';
  return 'Datos';
}

// ── Sub-components ────────────────────────────────────────────────────────

function Spinner() {
  return (
    <svg className="animate-spin" width="16" height="16" viewBox="0 0 24 24" fill="none">
      <circle cx="12" cy="12" r="10" stroke="#2d2d40" strokeWidth="3"/>
      <path d="M12 2a10 10 0 0 1 10 10" stroke="#6685ff" strokeWidth="3" strokeLinecap="round"/>
    </svg>
  );
}

function UploadIcon() {
  return (
    <svg width="32" height="32" viewBox="0 0 24 24" fill="none"
         stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
      <path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4"/>
      <polyline points="17 8 12 3 7 8"/>
      <line x1="12" y1="3" x2="12" y2="15"/>
    </svg>
  );
}

function DownloadIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none"
         stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4"/>
      <polyline points="7 10 12 15 17 10"/>
      <line x1="12" y1="15" x2="12" y2="3"/>
    </svg>
  );
}

// ── Page ──────────────────────────────────────────────────────────────────

export default function ImportPage() {
  const [activeTab, setActiveTab] = useState<ImportType>('shipments');
  const [tabs, setTabs] = useState<Record<ImportType, TabState>>({
    shipments: { ...INITIAL_TAB },
    airports:  { ...INITIAL_TAB },
    flights:   { ...INITIAL_TAB },
  });
  const [logs, setLogs] = useState<DataImportLog[]>([]);
  const [dlLoading, setDlLoading] = useState<ImportType | null>(null);
  const [datasetLoading, setDatasetLoading] = useState(false);
  const [datasetResult, setDatasetResult] = useState<DatasetImportResult | null>(null);
  const [datasetError, setDatasetError] = useState<string | null>(null);
  const [benchmarkLoading, setBenchmarkLoading] = useState(false);
  const [benchmarkJob, setBenchmarkJob] = useState<BenchmarkJobState | null>(null);
  const [benchmarkError, setBenchmarkError] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  // Load import history
  const loadLogs = useCallback(async () => {
    try { setLogs(await importApi.getLogs()); } catch { /* silently ignore */ }
  }, []);

  useEffect(() => { loadLogs(); }, [loadLogs]);

  // ── Tab state helpers ───────────────────────────────────────────────────

  const setTab = (tab: ImportType, patch: Partial<TabState>) =>
    setTabs(prev => ({ ...prev, [tab]: { ...prev[tab], ...patch } }));

  const cur = tabs[activeTab];

  // ── File selection ──────────────────────────────────────────────────────

  function acceptFile(tab: ImportType, file: File | null) {
    if (!file) return;
    const ext = file.name.split('.').pop()?.toLowerCase();
    if (!['csv', 'xlsx', 'xls'].includes(ext ?? '')) {
      setTab(tab, { error: 'Solo se aceptan archivos .csv y .xlsx', file: null });
      return;
    }
    setTab(tab, { file, error: null, result: null });
  }

  function onDragOver(e: React.DragEvent) {
    e.preventDefault();
    setTab(activeTab, { dragOver: true });
  }
  function onDragLeave() { setTab(activeTab, { dragOver: false }); }
  function onDrop(e: React.DragEvent) {
    e.preventDefault();
    setTab(activeTab, { dragOver: false });
    acceptFile(activeTab, e.dataTransfer.files[0] ?? null);
  }
  function onFileChange(e: React.ChangeEvent<HTMLInputElement>) {
    acceptFile(activeTab, e.target.files?.[0] ?? null);
    if (e.target) e.target.value = '';
  }

  // ── Import ──────────────────────────────────────────────────────────────

  async function handleImport() {
    const file = cur.file;
    if (!file) return;
    setTab(activeTab, { loading: true, error: null, result: null });
    try {
      const log = await importApi.importFile(file, activeTab);
      setTab(activeTab, { loading: false, result: log, file: null });
      loadLogs();
    } catch (err) {
      setTab(activeTab, {
        loading: false,
        error: err instanceof Error ? err.message : 'Error al importar',
      });
    }
  }

  // ── Template download ───────────────────────────────────────────────────

  async function handleDownload(type: ImportType) {
    setDlLoading(type);
    try { await importApi.downloadTemplate(type); }
    catch { alert('Error descargando la plantilla'); }
    finally { setDlLoading(null); }
  }

  async function handleImportDefaultDataset() {
    setDatasetLoading(true);
    setDatasetError(null);
    try {
      const result = await importApi.importDefaultDataset();
      setDatasetResult(result);
      await loadLogs();
    } catch (err) {
      setDatasetResult(null);
      setDatasetError(err instanceof Error ? err.message : 'No se pudo importar el dataset real.');
    } finally {
      setDatasetLoading(false);
    }
  }

  async function handleDownloadScenarioDemand() {
    try {
      await importApi.downloadScenarioDemandTemplate();
    } catch {
      setBenchmarkError('No se pudo descargar la demanda de escenarios.');
    }
  }

  async function handleStartBenchmark() {
    setBenchmarkLoading(true);
    setBenchmarkError(null);
    try {
      const started = await importApi.startBenchmarkJob();
      let attempts = 0;
      let current: BenchmarkJobState | null = null;
      while (attempts < 120) {
        await new Promise((resolve) => setTimeout(resolve, 2000));
        current = await importApi.getBenchmarkJobStatus(started.jobId);
        setBenchmarkJob(current);
        if (current.status === 'DONE' || current.status === 'FAILED') {
          break;
        }
        attempts++;
      }
      if (!current || (current.status !== 'DONE' && current.status !== 'FAILED')) {
        setBenchmarkError('Benchmark sigue ejecutandose. Recarga para consultar estado.');
      }
    } catch (err) {
      setBenchmarkError(err instanceof Error ? err.message : 'No se pudo ejecutar benchmark.');
    } finally {
      setBenchmarkLoading(false);
    }
  }

  // ── Render ──────────────────────────────────────────────────────────────

  return (
    <div className="max-w-4xl mx-auto">

      {/* Page header */}
      <div className="mb-8">
        <h1 className="text-2xl font-bold" style={{ color: '#f0f0f8' }}>Importar Datos</h1>
        <p className="text-sm mt-1" style={{ color: '#8484a0' }}>
          Carga masiva de envíos, aeropuertos y vuelos desde archivos CSV o Excel
        </p>
      </div>

      {/* ── Notice ── */}
      <div className="flex items-start gap-3 px-4 py-3 rounded-xl mb-6"
           style={{ background: '#f59e0b10', border: '1px solid #f59e0b30' }}>
        <span className="text-lg flex-shrink-0 mt-0.5">⚠️</span>
        <p className="text-sm" style={{ color: '#f0f0f8' }}>
          El formato de columnas puede ajustarse según los datos reales.
          {' '}<strong>Descarga la plantilla</strong> para ver el formato exacto esperado
          por el sistema.
        </p>
      </div>

      <div className="rounded-xl mb-6 p-5" style={{ background: '#1c1c24', border: '1px solid #2d2d40' }}>
        <div className="flex items-center justify-between gap-3 flex-wrap">
          <div>
            <p className="text-sm font-semibold" style={{ color: '#f0f0f8' }}>Carga inicial con dataset real</p>
            <p className="text-xs mt-1" style={{ color: '#8484a0' }}>
              Importa aeropuertos y planes de vuelo desde `/datos` en un solo paso.
            </p>
          </div>
          <button
            onClick={handleImportDefaultDataset}
            disabled={datasetLoading}
            className="text-sm font-semibold px-4 py-2 rounded-lg cursor-pointer transition-colors"
            style={{ background: '#6685ff', color: '#fff', opacity: datasetLoading ? 0.7 : 1 }}
          >
            {datasetLoading ? 'Importando...' : 'Importar dataset /datos'}
          </button>
        </div>

        {datasetError && (
          <div className="mt-3 px-3 py-2 rounded-lg" style={{ background: '#ef444418', border: '1px solid #ef444440', color: '#ef4444' }}>
            <p className="text-xs">⚠ {datasetError}</p>
          </div>
        )}

        {datasetResult && (
          <div className="mt-3 grid grid-cols-2 gap-3">
            <div className="rounded-lg p-3" style={{ background: '#232330', border: '1px solid #2d2d40' }}>
              <div className="flex items-center justify-between">
                <p className="text-xs font-semibold" style={{ color: '#f0f0f8' }}>Aeropuertos</p>
                {statusBadge(datasetResult.airports.status)}
              </div>
              <p className="text-xs mt-2" style={{ color: '#8484a0' }}>
                {datasetResult.airports.successRows}/{datasetResult.airports.totalRows} filas exitosas
              </p>
            </div>
            <div className="rounded-lg p-3" style={{ background: '#232330', border: '1px solid #2d2d40' }}>
              <div className="flex items-center justify-between">
                <p className="text-xs font-semibold" style={{ color: '#f0f0f8' }}>Vuelos</p>
                {statusBadge(datasetResult.flights.status)}
              </div>
              <p className="text-xs mt-2" style={{ color: '#8484a0' }}>
                {datasetResult.flights.successRows}/{datasetResult.flights.totalRows} filas exitosas
              </p>
            </div>
          </div>
        )}
      </div>

      <div className="rounded-xl mb-6 p-5" style={{ background: '#1c1c24', border: '1px solid #2d2d40' }}>
        <div className="flex items-center justify-between gap-3 flex-wrap">
          <div>
            <p className="text-sm font-semibold" style={{ color: '#f0f0f8' }}>Benchmark y tuning asíncrono</p>
            <p className="text-xs mt-1" style={{ color: '#8484a0' }}>
              Descarga demanda de escenarios y ejecuta benchmark sin bloquear la interfaz.
            </p>
          </div>
          <div className="flex items-center gap-2">
            <button
              onClick={handleDownloadScenarioDemand}
              className="text-sm font-semibold px-4 py-2 rounded-lg cursor-pointer transition-colors"
              style={{ background: '#2f3b5c', color: '#fff' }}
            >
              Descargar demanda
            </button>
            <button
              onClick={handleStartBenchmark}
              disabled={benchmarkLoading}
              className="text-sm font-semibold px-4 py-2 rounded-lg cursor-pointer transition-colors"
              style={{ background: '#6685ff', color: '#fff', opacity: benchmarkLoading ? 0.7 : 1 }}
            >
              {benchmarkLoading ? 'Ejecutando...' : 'Ejecutar benchmark'}
            </button>
          </div>
        </div>

        {benchmarkError && (
          <div className="mt-3 px-3 py-2 rounded-lg" style={{ background: '#ef444418', border: '1px solid #ef444440', color: '#ef4444' }}>
            <p className="text-xs">⚠ {benchmarkError}</p>
          </div>
        )}

        {benchmarkJob && (
          <div className="mt-3 rounded-lg p-3" style={{ background: '#232330', border: '1px solid #2d2d40' }}>
            <p className="text-xs font-semibold" style={{ color: '#f0f0f8' }}>
              Estado: {benchmarkJob.status} · {benchmarkJob.message}
            </p>
            {benchmarkJob.result && (
              <>
                <p className="text-xs mt-2" style={{ color: '#8484a0' }}>
                  Winner global: {benchmarkJob.result.winner} · muestra {benchmarkJob.result.sampleSize} · envíos {benchmarkJob.result.createdShipments}/{benchmarkJob.result.generatedRows}
                </p>
                <div className="mt-2 grid grid-cols-2 gap-2">
                  {benchmarkJob.result.scenarios.map((scenario) => (
                    <div key={scenario.scenario} className="rounded p-2" style={{ background: '#1b1e2d', border: '1px solid #2d2d40' }}>
                      <p className="text-[11px] font-semibold" style={{ color: '#f0f0f8' }}>{scenario.scenario}</p>
                      <p className="text-[11px]" style={{ color: '#9aa3be' }}>
                        winner {scenario.winner} · envíos {scenario.createdShipments}/{scenario.sampleSize}
                      </p>
                      <p className="text-[11px]" style={{ color: '#9aa3be' }}>
                        cancelados {scenario.cancelledFlights} · replans {scenario.replannings}
                      </p>
                    </div>
                  ))}
                </div>
              </>
            )}
          </div>
        )}
      </div>

      {/* ── Import section ── */}
      <div className="rounded-xl mb-6" style={{ background: '#1c1c24', border: '1px solid #2d2d40' }}>

        {/* Tabs */}
        <div className="flex" style={{ borderBottom: '1px solid #2d2d40' }}>
          {TABS.map(({ key, label, emoji }) => {
            const active = activeTab === key;
            return (
              <button key={key}
                      onClick={() => setActiveTab(key)}
                      className="flex items-center gap-2 px-5 py-3.5 text-sm font-medium transition-colors cursor-pointer"
                      style={{
                        color:        active ? '#6685ff' : '#8484a0',
                        borderBottom: active ? '2px solid #6685ff' : '2px solid transparent',
                        background:   'transparent',
                      }}>
                <span>{emoji}</span>
                {label}
                {tabs[key].result && (
                  <span className="text-[10px] px-1.5 py-0.5 rounded-full ml-1"
                        style={{
                          background: tabs[key].result!.status === 'FAILED' ? '#ef444420' : '#22c55e20',
                          color:      tabs[key].result!.status === 'FAILED' ? '#ef4444'   : '#22c55e',
                        }}>
                    {tabs[key].result!.successRows}
                  </span>
                )}
              </button>
            );
          })}
        </div>

        <div className="p-6">

          {/* Drop zone */}
          <div onDragOver={onDragOver} onDragLeave={onDragLeave} onDrop={onDrop}
               onClick={() => fileInputRef.current?.click()}
               className="rounded-xl flex flex-col items-center justify-center gap-3 cursor-pointer transition-all mb-4"
               style={{
                 height: 180,
                 border: `2px dashed ${cur.dragOver ? '#6685ff' : '#2d2d40'}`,
                 background: cur.dragOver ? '#6685ff0a' : '#232330',
               }}>
            <span style={{ color: cur.dragOver ? '#6685ff' : '#4a4a60' }}>
              <UploadIcon />
            </span>
            <div className="text-center">
              <p className="text-sm font-medium" style={{ color: cur.dragOver ? '#6685ff' : '#8484a0' }}>
                {cur.dragOver
                  ? 'Suelta el archivo aquí'
                  : 'Arrastra tu archivo CSV o Excel aquí'}
              </p>
              <p className="text-xs mt-1" style={{ color: '#4a4a60' }}>
                .csv · .xlsx · máx. 50 MB
              </p>
            </div>
            <span className="text-xs px-4 py-1.5 rounded-lg pointer-events-none"
                  style={{ background: '#2d2d40', color: '#8484a0' }}>
              Seleccionar archivo
            </span>
          </div>

          <input ref={fileInputRef} type="file" accept=".csv,.xlsx,.xls"
                 className="hidden" onChange={onFileChange} />

          {/* Selected file + import button */}
          {cur.file && !cur.loading && (
            <div className="flex items-center gap-3 px-4 py-3 rounded-xl mb-4"
                 style={{ background: '#232330', border: '1px solid #2d2d40' }}>
              <span className="text-xl">📄</span>
              <div className="flex-1 min-w-0">
                <p className="text-sm font-medium truncate" style={{ color: '#f0f0f8' }}>
                  {cur.file.name}
                </p>
                <p className="text-xs" style={{ color: '#8484a0' }}>
                  {(cur.file.size / 1024).toFixed(1)} KB
                </p>
              </div>
              <button onClick={() => setTab(activeTab, { file: null })}
                      className="text-xs px-2 py-1 rounded cursor-pointer"
                      style={{ color: '#8484a0' }}>✕</button>
              <button onClick={handleImport}
                      className="text-sm font-semibold px-5 py-2 rounded-lg cursor-pointer transition-colors"
                      style={{ background: '#6685ff', color: '#fff' }}>
                Importar
              </button>
            </div>
          )}

          {/* Loading state */}
          {cur.loading && (
            <div className="flex items-center gap-3 px-4 py-3 rounded-xl mb-4"
                 style={{ background: '#6685ff10', border: '1px solid #6685ff30' }}>
              <Spinner />
              <p className="text-sm" style={{ color: '#8ba3ff' }}>Procesando archivo…</p>
            </div>
          )}

          {/* Error */}
          {cur.error && (
            <div className="px-4 py-3 rounded-xl mb-4"
                 style={{ background: '#ef444418', border: '1px solid #ef444440', color: '#ef4444' }}>
              <p className="text-sm">⚠ {cur.error}</p>
            </div>
          )}

          {/* Result card */}
          {cur.result && (
            <div className="rounded-xl p-4"
                 style={{
                   background: cur.result.status === 'FAILED' ? '#ef444410' : '#22c55e10',
                   border: `1px solid ${cur.result.status === 'FAILED' ? '#ef444430' : '#22c55e30'}`,
                 }}>
              <div className="flex items-start justify-between mb-3">
                <div>
                  <p className="text-xs font-medium truncate max-w-xs" style={{ color: '#8484a0' }}>
                    {cur.result.fileName}
                  </p>
                  <p className="text-[10px] mt-0.5" style={{ color: '#4a4a60' }}>
                    {formatDate(cur.result.importedAt)}
                  </p>
                </div>
                {statusBadge(cur.result.status)}
              </div>

              <div className="grid grid-cols-3 gap-3 mb-3">
                {[
                  { label: 'Total filas', val: cur.result.totalRows,   c: '#8484a0' },
                  { label: '✅ Exitosas', val: cur.result.successRows, c: '#22c55e' },
                  { label: '⚠ Errores',  val: cur.result.errorRows,   c: '#f59e0b' },
                ].map(({ label, val, c }) => (
                  <div key={label} className="text-center rounded-lg p-2"
                       style={{ background: '#1c1c2480' }}>
                    <p className="text-xl font-bold" style={{ color: c }}>{val}</p>
                    <p className="text-[10px]" style={{ color: '#8484a0' }}>{label}</p>
                  </div>
                ))}
              </div>

              {cur.result.errorRows > 0 && cur.result.errorDetails && (
                <div>
                  <button onClick={() => setTab(activeTab, { showErrors: !cur.showErrors })}
                          className="text-xs cursor-pointer"
                          style={{ color: '#f59e0b' }}>
                    {cur.showErrors ? '▲ Ocultar detalles' : '▼ Ver detalles de errores'}
                  </button>
                  {cur.showErrors && (
                    <pre className="mt-2 p-3 rounded-lg text-[11px] overflow-auto max-h-40"
                         style={{
                           background: '#121217', color: '#f59e0b',
                           fontFamily: 'var(--font-geist-mono)', lineHeight: 1.6,
                         }}>
                      {cur.result.errorDetails}
                    </pre>
                  )}
                </div>
              )}
            </div>
          )}

        </div>
      </div>

      {/* ── Templates section ── */}
      <div className="rounded-xl p-6 mb-6" style={{ background: '#1c1c24', border: '1px solid #2d2d40' }}>
        <h2 className="text-sm font-semibold mb-1" style={{ color: '#f0f0f8' }}>
          Descargar Plantillas Excel
        </h2>
        <p className="text-xs mb-5" style={{ color: '#8484a0' }}>
          Descarga el formato correcto antes de preparar tu archivo de importación.
        </p>

        <div className="grid grid-cols-3 gap-4">
          {TABS.map(({ key, label, emoji }) => (
            <button key={key}
                    onClick={() => handleDownload(key)}
                    disabled={dlLoading === key}
                    className="flex flex-col items-center gap-2 p-4 rounded-xl cursor-pointer transition-all"
                    style={{
                      background: '#232330',
                      border: '1px solid #2d2d40',
                      opacity: dlLoading && dlLoading !== key ? 0.5 : 1,
                    }}>
              <div className="w-9 h-9 rounded-lg flex items-center justify-center"
                   style={{ background: '#6685ff18' }}>
                {dlLoading === key
                  ? <Spinner />
                  : <span style={{ color: '#6685ff' }}><DownloadIcon /></span>}
              </div>
              <div className="text-center">
                <p className="text-sm font-medium" style={{ color: '#f0f0f8' }}>
                  {emoji} {label}
                </p>
                <p className="text-[10px] mt-0.5" style={{ color: '#8484a0' }}>
                  Descarga el formato Excel esperado
                </p>
              </div>
            </button>
          ))}
        </div>
      </div>

      {/* ── History section ── */}
      <div className="rounded-xl" style={{ background: '#1c1c24', border: '1px solid #2d2d40' }}>
        <div className="flex items-center justify-between px-6 py-4"
             style={{ borderBottom: '1px solid #2d2d40' }}>
          <h2 className="text-sm font-semibold" style={{ color: '#f0f0f8' }}>
            Historial de Importaciones
          </h2>
          <button onClick={loadLogs}
                  className="text-xs cursor-pointer"
                  style={{ color: '#8484a0' }}>
            ↻ Actualizar
          </button>
        </div>

        {logs.length === 0 ? (
          <div className="px-6 py-12 text-center">
            <p className="text-sm" style={{ color: '#4a4a60' }}>
              Sin importaciones registradas
            </p>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr style={{ borderBottom: '1px solid #2d2d40' }}>
                  {['Archivo', 'Fecha', 'Tipo', 'Exitosas', 'Errores', 'Estado'].map(h => (
                    <th key={h} className="px-5 py-3 text-left text-[10px] font-semibold uppercase tracking-wider"
                        style={{ color: '#8484a0' }}>
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {logs.map(log => (
                  <tr key={log.id}
                      style={{ borderBottom: '1px solid #2d2d4040' }}
                      onMouseEnter={e => (e.currentTarget.style.background = '#232330')}
                      onMouseLeave={e => (e.currentTarget.style.background = 'transparent')}>
                    <td className="px-5 py-3">
                      <p className="truncate max-w-[200px] font-medium"
                         style={{ color: '#f0f0f8' }} title={log.fileName}>
                        {log.fileName}
                      </p>
                    </td>
                    <td className="px-5 py-3 whitespace-nowrap"
                        style={{ color: '#8484a0' }}>
                      {formatDate(log.importedAt)}
                    </td>
                    <td className="px-5 py-3"
                        style={{ color: '#8484a0' }}>
                      {guessType(log.fileName)}
                    </td>
                    <td className="px-5 py-3">
                      <span className="font-semibold" style={{ color: '#22c55e' }}>
                        {log.successRows}
                      </span>
                    </td>
                    <td className="px-5 py-3">
                      <span className="font-semibold"
                            style={{ color: log.errorRows > 0 ? '#f59e0b' : '#4a4a60' }}>
                        {log.errorRows}
                      </span>
                    </td>
                    <td className="px-5 py-3">
                      {statusBadge(log.status)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

    </div>
  );
}
