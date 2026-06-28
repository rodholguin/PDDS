'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import type { DataImportLog, ImportStatus } from '@/lib/types';
import { importApi, type ImportType } from '@/lib/api/importApi';

interface TabState {
  file: File | null;
  loading: boolean;
  result: DataImportLog | null;
  error: string | null;
  dragOver: boolean;
  showErrors: boolean;
}

const TABS: { key: ImportType; label: string }[] = [
  { key: 'shipments', label: 'Envíos históricos' },
  { key: 'airports', label: 'Aeropuertos' },
  { key: 'flights', label: 'Vuelos' },
];

const INITIAL_TAB: TabState = {
  file: null,
  loading: false,
  result: null,
  error: null,
  dragOver: false,
  showErrors: false,
};

const OK = '#43d29d';
const WARN = '#f0c13a';
const FAIL = '#ef4444';
const ACCENT = '#5f82ff';

function statusBadge(status: ImportStatus) {
  const cfg: Record<ImportStatus, { color: string; label: string }> = {
    SUCCESS: { color: OK, label: 'Exitoso' },
    PARTIAL: { color: WARN, label: 'Parcial' },
    FAILED: { color: FAIL, label: 'Fallido' },
  };
  const c = cfg[status];
  return (
    <span style={{ fontSize: 11, fontWeight: 600, padding: '3px 10px', borderRadius: 99, color: c.color, background: `${c.color}1e`, border: `1px solid ${c.color}40` }}>
      {c.label}
    </span>
  );
}

function formatDate(iso: string) {
  return new Date(iso).toLocaleString('es-PE', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

function guessType(fileName: string): string {
  const f = fileName.toLowerCase();
  if (f.includes('envio') || f.includes('shipment')) return 'Envíos';
  if (f.includes('airport') || f.includes('aeropuerto')) return 'Aeropuertos';
  if (f.includes('flight') || f.includes('vuelo')) return 'Vuelos';
  return 'Datos';
}

function Spinner() {
  return (
    <svg className="animate-spin" width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <circle cx="12" cy="12" r="10" stroke="#32364f" strokeWidth="3" />
      <path d="M12 2a10 10 0 0 1 10 10" stroke={ACCENT} strokeWidth="3" strokeLinecap="round" />
    </svg>
  );
}

function UploadIcon({ color }: { color: string }) {
  return (
    <svg width="30" height="30" viewBox="0 0 24 24" fill="none" stroke={color} strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
      <polyline points="17 8 12 3 7 8" />
      <line x1="12" y1="3" x2="12" y2="15" />
    </svg>
  );
}

function DownloadIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke={ACCENT} strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
      <polyline points="7 10 12 15 17 10" />
      <line x1="12" y1="15" x2="12" y2="3" />
    </svg>
  );
}

export default function ImportPage() {
  const [activeTab, setActiveTab] = useState<ImportType>('shipments');
  const [tabs, setTabs] = useState<Record<ImportType, TabState>>({
    shipments: { ...INITIAL_TAB },
    airports: { ...INITIAL_TAB },
    flights: { ...INITIAL_TAB },
  });
  const [logs, setLogs] = useState<DataImportLog[]>([]);
  const [dlLoading, setDlLoading] = useState<ImportType | null>(null);
  const [datasetSummary, setDatasetSummary] = useState<{ total: number } | null>(null);
  const [refreshingSummary, setRefreshingSummary] = useState(false);
  const [enviosBusy, setEnviosBusy] = useState(false);
  const [enviosMsg, setEnviosMsg] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const cur = tabs[activeTab];

  const setTab = (tab: ImportType, patch: Partial<TabState>) => {
    setTabs((prev) => ({ ...prev, [tab]: { ...prev[tab], ...patch } }));
  };

  const loadLogs = useCallback(async () => {
    try {
      setLogs(await importApi.getLogs());
    } catch {
      setLogs([]);
    }
  }, []);

  const loadSummary = useCallback(async () => {
    setRefreshingSummary(true);
    try {
      const status = await importApi.getDatasetStatus();
      setDatasetSummary({ total: status.totalShipments ?? 0 });
    } catch {
      setDatasetSummary(null);
    } finally {
      setRefreshingSummary(false);
    }
  }, []);

  useEffect(() => {
    void loadLogs();
    void loadSummary();
  }, [loadLogs, loadSummary]);

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

  function onDragLeave() {
    setTab(activeTab, { dragOver: false });
  }

  function onDrop(e: React.DragEvent) {
    e.preventDefault();
    setTab(activeTab, { dragOver: false });
    acceptFile(activeTab, e.dataTransfer.files[0] ?? null);
  }

  function onFileChange(e: React.ChangeEvent<HTMLInputElement>) {
    acceptFile(activeTab, e.target.files?.[0] ?? null);
    if (e.target) e.target.value = '';
  }

  async function handleImport() {
    const file = cur.file;
    if (!file) return;
    setTab(activeTab, { loading: true, error: null, result: null });
    try {
      const log = await importApi.importFile(file, activeTab);
      setTab(activeTab, { loading: false, result: log, file: null });
      await loadLogs();
      await loadSummary();
    } catch (err) {
      setTab(activeTab, {
        loading: false,
        error: err instanceof Error ? err.message : 'Error al importar',
      });
    }
  }

  async function handleDownload(type: ImportType) {
    setDlLoading(type);
    try {
      await importApi.downloadTemplate(type);
    } finally {
      setDlLoading(null);
    }
  }

  async function importEnvios(full: boolean) {
    setEnviosBusy(true);
    setEnviosMsg(full ? 'Iniciando importación del dataset completo…' : 'Importando muestra de envíos…');
    try {
      if (full) {
        const { jobId } = await importApi.startEnviosDatasetFullJob();
        let polls = 0;
        let done = false;
        while (!done && polls < 240) {
          polls += 1;
          await new Promise((resolve) => setTimeout(resolve, 2500));
          const state = await importApi.getEnviosDatasetFullJobStatus(jobId);
          setEnviosMsg(state.message || `Estado: ${state.status}`);
          done = state.status === 'DONE' || state.status === 'FAILED' || state.status === 'IDLE';
        }
      } else {
        const res = await importApi.importEnviosDataset({});
        setEnviosMsg(
          `Importados ${res.result.importedRows.toLocaleString('es-PE')} envíos ` +
          `(${res.result.processedFiles}/${res.result.totalFiles} archivos).`,
        );
      }
      await loadSummary();
      await loadLogs();
    } catch (e) {
      setEnviosMsg(e instanceof Error ? e.message : 'Error al importar envíos.');
    } finally {
      setEnviosBusy(false);
    }
  }

  return (
    <div className="app-page" style={{ paddingTop: 10 }}>
      <header className="page-head">
        <div>
          <h1 className="page-head-title">Importación de datos base</h1>
          <p className="page-head-subtitle">Aeropuertos, vuelos y envíos históricos. La simulación se opera desde Inicio.</p>
        </div>
      </header>

      <div style={{ padding: '0 14px 18px', display: 'flex', flexDirection: 'column', gap: 12 }}>
        <article className="surface-panel" style={{ padding: 16 }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 12, flexWrap: 'wrap' }}>
            <div>
              <p style={{ margin: 0, fontSize: 14, fontWeight: 600, color: '#eaf0ff' }}>Dataset de envíos (datos/envios)</p>
              <p style={{ margin: '6px 0 0', fontSize: 12, color: '#9ca7c8' }}>
                {datasetSummary == null ? 'No se pudo leer el estado actual.' : `Envíos registrados: ${datasetSummary.total.toLocaleString('es-PE')}`}
              </p>
              <p style={{ margin: '4px 0 0', fontSize: 12, color: '#7c879f' }}>
                La simulación trabaja únicamente con estos envíos importados. No se genera demanda futura.
              </p>
            </div>
            <button className="chip" onClick={() => void loadSummary()} disabled={refreshingSummary}>
              {refreshingSummary ? 'Actualizando…' : 'Actualizar estado'}
            </button>
          </div>
          <div style={{ marginTop: 14, display: 'flex', gap: 10, flexWrap: 'wrap', alignItems: 'center' }}>
            <button className="btn btn-primary" onClick={() => void importEnvios(false)} disabled={enviosBusy}>
              {enviosBusy ? 'Importando…' : 'Importar envíos (muestra)'}
            </button>
            <button className="chip" onClick={() => void importEnvios(true)} disabled={enviosBusy}>
              {enviosBusy ? 'Importando…' : 'Importar dataset completo'}
            </button>
            {enviosMsg ? <p style={{ margin: 0, fontSize: 12, color: '#9ca7c8' }}>{enviosMsg}</p> : null}
          </div>
        </article>

        <article className="surface-panel" style={{ padding: 0, overflow: 'hidden' }}>
          <div style={{ display: 'flex', borderBottom: '1px solid #2a2e44' }}>
            {TABS.map(({ key, label }) => {
              const active = activeTab === key;
              return (
                <button
                  key={key}
                  onClick={() => setActiveTab(key)}
                  style={{
                    padding: '13px 20px',
                    fontSize: 13,
                    fontWeight: 500,
                    cursor: 'pointer',
                    background: 'transparent',
                    border: 'none',
                    color: active ? '#eaf0ff' : '#8a93b2',
                    borderBottom: active ? `2px solid ${ACCENT}` : '2px solid transparent',
                  }}
                >
                  {label}
                </button>
              );
            })}
          </div>

          <div style={{ padding: 18 }}>
            <div
              onDragOver={onDragOver}
              onDragLeave={onDragLeave}
              onDrop={onDrop}
              onClick={() => fileInputRef.current?.click()}
              style={{
                height: 170,
                borderRadius: 12,
                border: `2px dashed ${cur.dragOver ? ACCENT : '#32364f'}`,
                background: cur.dragOver ? 'rgba(95,130,255,0.06)' : '#171a29',
                display: 'flex',
                flexDirection: 'column',
                alignItems: 'center',
                justifyContent: 'center',
                gap: 10,
                cursor: 'pointer',
                transition: '160ms ease',
              }}
            >
              <UploadIcon color={cur.dragOver ? ACCENT : '#6f7693'} />
              <p style={{ margin: 0, fontSize: 14, fontWeight: 500, color: cur.dragOver ? '#aebeff' : '#9ca7c8' }}>
                {cur.dragOver ? 'Soltá el archivo aquí' : 'Arrastrá tu archivo CSV o Excel aquí'}
              </p>
              <p style={{ margin: 0, fontSize: 12, color: '#6f7693' }}>.csv · .xlsx · máx. 50 MB</p>
            </div>

            <input ref={fileInputRef} type="file" accept=".csv,.xlsx,.xls" style={{ display: 'none' }} onChange={onFileChange} />

            {cur.file && !cur.loading ? (
              <div style={{ marginTop: 14, display: 'flex', alignItems: 'center', gap: 12, padding: '12px 14px', borderRadius: 12, background: '#171a29', border: '1px solid #32364f' }}>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <p style={{ margin: 0, fontSize: 13, fontWeight: 500, color: '#eaf0ff', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{cur.file.name}</p>
                  <p style={{ margin: '2px 0 0', fontSize: 12, color: '#8a93b2' }}>{(cur.file.size / 1024).toFixed(1)} KB</p>
                </div>
                <button className="chip" onClick={() => setTab(activeTab, { file: null })} aria-label="Quitar archivo">Quitar</button>
                <button className="btn btn-primary" onClick={handleImport}>Importar</button>
              </div>
            ) : null}

            {cur.loading ? (
              <div style={{ marginTop: 14, display: 'flex', alignItems: 'center', gap: 12, padding: '12px 14px', borderRadius: 12, background: 'rgba(95,130,255,0.08)', border: '1px solid rgba(95,130,255,0.3)' }}>
                <Spinner />
                <p style={{ margin: 0, fontSize: 13, color: '#aebeff' }}>Procesando archivo…</p>
              </div>
            ) : null}

            {cur.error ? (
              <div style={{ marginTop: 14, padding: '12px 14px', borderRadius: 12, background: `${FAIL}18`, border: `1px solid ${FAIL}40` }}>
                <p style={{ margin: 0, fontSize: 13, color: FAIL }}>{cur.error}</p>
              </div>
            ) : null}

            {cur.result ? (
              <div style={{ marginTop: 14, padding: 16, borderRadius: 12, background: `${OK}12`, border: `1px solid ${OK}33` }}>
                <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 12, marginBottom: 12 }}>
                  <div>
                    <p style={{ margin: 0, fontSize: 13, fontWeight: 500, color: '#eaf0ff' }}>{cur.result.fileName}</p>
                    <p style={{ margin: '2px 0 0', fontSize: 11, color: '#7c879f' }}>{formatDate(cur.result.importedAt)}</p>
                  </div>
                  {statusBadge(cur.result.status)}
                </div>
                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 10 }}>
                  <div style={{ textAlign: 'center', padding: 10, borderRadius: 10, background: '#171a2980' }}>
                    <p style={{ margin: 0, fontSize: 20, fontWeight: 600, color: '#c2cced' }}>{cur.result.totalRows.toLocaleString('es-PE')}</p>
                    <p style={{ margin: '2px 0 0', fontSize: 11, color: '#8a93b2' }}>Total filas</p>
                  </div>
                  <div style={{ textAlign: 'center', padding: 10, borderRadius: 10, background: '#171a2980' }}>
                    <p style={{ margin: 0, fontSize: 20, fontWeight: 600, color: OK }}>{cur.result.successRows.toLocaleString('es-PE')}</p>
                    <p style={{ margin: '2px 0 0', fontSize: 11, color: '#8a93b2' }}>Exitosas</p>
                  </div>
                  <div style={{ textAlign: 'center', padding: 10, borderRadius: 10, background: '#171a2980' }}>
                    <p style={{ margin: 0, fontSize: 20, fontWeight: 600, color: cur.result.errorRows > 0 ? WARN : '#7c879f' }}>{cur.result.errorRows.toLocaleString('es-PE')}</p>
                    <p style={{ margin: '2px 0 0', fontSize: 11, color: '#8a93b2' }}>Errores</p>
                  </div>
                </div>
                {cur.result.errorRows > 0 && cur.result.errorDetails ? (
                  <div style={{ marginTop: 10 }}>
                    <button onClick={() => setTab(activeTab, { showErrors: !cur.showErrors })} style={{ background: 'transparent', border: 'none', cursor: 'pointer', fontSize: 12, color: WARN, padding: 0 }}>
                      {cur.showErrors ? 'Ocultar detalles' : 'Ver detalles de errores'}
                    </button>
                    {cur.showErrors ? (
                      <pre style={{ marginTop: 8, padding: 12, borderRadius: 10, fontSize: 11, lineHeight: 1.6, overflow: 'auto', maxHeight: 160, background: '#0f1118', color: WARN }}>
                        {cur.result.errorDetails}
                      </pre>
                    ) : null}
                  </div>
                ) : null}
              </div>
            ) : null}
          </div>
        </article>

        <article className="surface-panel" style={{ padding: 16 }}>
          <p style={{ margin: 0, fontSize: 14, fontWeight: 600, color: '#eaf0ff' }}>Plantillas</p>
          <p style={{ margin: '4px 0 14px', fontSize: 12, color: '#9ca7c8' }}>Descargá el formato correcto antes de preparar el archivo.</p>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))', gap: 10 }}>
            {TABS.map(({ key, label }) => (
              <button
                key={key}
                onClick={() => handleDownload(key)}
                disabled={dlLoading === key}
                style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8, padding: '14px 12px', borderRadius: 12, background: '#171a29', border: '1px solid #32364f', color: '#dbe3ff', fontSize: 13, fontWeight: 500, cursor: 'pointer' }}
              >
                {dlLoading === key ? <Spinner /> : <DownloadIcon />}
                {label}
              </button>
            ))}
          </div>
        </article>

        <article className="surface-panel" style={{ padding: 0, overflow: 'hidden' }}>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '14px 16px', borderBottom: '1px solid #2a2e44' }}>
            <p style={{ margin: 0, fontSize: 14, fontWeight: 600, color: '#eaf0ff' }}>Historial de importaciones</p>
            <button className="chip" onClick={() => void loadLogs()}>Actualizar</button>
          </div>
          {logs.length === 0 ? (
            <div style={{ padding: '40px 16px', textAlign: 'center' }}>
              <p style={{ margin: 0, fontSize: 13, color: '#6f7693' }}>Sin importaciones registradas.</p>
            </div>
          ) : (
            <div style={{ overflowX: 'auto' }}>
              <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
                <thead>
                  <tr>
                    {['Archivo', 'Fecha', 'Tipo', 'Exitosas', 'Errores', 'Estado'].map((h) => (
                      <th key={h} style={{ padding: '10px 16px', textAlign: 'left', fontSize: 11, fontWeight: 600, letterSpacing: '0.04em', color: '#8a93b2', borderBottom: '1px solid #2a2e44' }}>{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {logs.map((log) => (
                    <tr key={log.id} style={{ borderBottom: '1px solid #232842' }}>
                      <td style={{ padding: '10px 16px', color: '#dbe3ff' }}>{log.fileName}</td>
                      <td style={{ padding: '10px 16px', color: '#8a93b2' }}>{formatDate(log.importedAt)}</td>
                      <td style={{ padding: '10px 16px', color: '#8a93b2' }}>{guessType(log.fileName)}</td>
                      <td style={{ padding: '10px 16px', color: OK }}>{log.successRows.toLocaleString('es-PE')}</td>
                      <td style={{ padding: '10px 16px', color: log.errorRows > 0 ? WARN : '#6f7693' }}>{log.errorRows.toLocaleString('es-PE')}</td>
                      <td style={{ padding: '10px 16px' }}>{statusBadge(log.status)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </article>
      </div>
    </div>
  );
}
