'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import Link from 'next/link';
import { alertsApi } from '@/lib/api/alertsApi';
import { shipmentsApi, type ShipmentCreate } from '@/lib/api/shipmentsApi';
import { useSimulation } from '@/lib/SimulationContext';
import type {
  Shipment,
  ShipmentAuditLog,
  ShipmentDetail,
  ShipmentFeasibility,
  ShipmentLeg,
  ShipmentPlanningEvent,
  ShipmentStatus,
  ShipmentUpcoming,
  SimulationState,
} from '@/lib/types';

const PAGE_SIZE = 50;
const ALGORITHMS: ShipmentCreate['algorithmName'][] = ['Genetic Algorithm', 'Ant Colony Optimization', 'Simulated Annealing'];

function statusLabel(status: ShipmentStatus): string {
  if (status === 'IN_ROUTE') return 'En ruta';
  if (status === 'PENDING') return 'Pendiente';
  if (status === 'CRITICAL') return 'Crítico';
  if (status === 'DELAYED') return 'Atrasado';
  return 'Entregado';
}

function statusClass(status: ShipmentStatus): string {
  if (status === 'CRITICAL' || status === 'DELAYED') return 'status-badge status-critico';
  if (status === 'PENDING') return 'status-badge status-alerta';
  return 'status-badge status-normal';
}

function stopLabel(status: ShipmentLeg['stopStatus']): string {
  if (status === 'IN_TRANSIT') return 'En vuelo';
  if (status === 'COMPLETED') return 'Completado';
  return 'Programado';
}

function dateShort(iso: string | null | undefined): string {
  if (!iso) return '-';
  return new Date(iso).toLocaleString('es-PE', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

function toDateInput(iso: string | null | undefined): string {
  if (!iso) return '';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '';
  const yyyy = date.getFullYear();
  const mm = String(date.getMonth() + 1).padStart(2, '0');
  const dd = String(date.getDate()).padStart(2, '0');
  return `${yyyy}-${mm}-${dd}`;
}

function toDateTimeLocal(iso: string | null | undefined): string {
  if (!iso) return '';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '';
  const yyyy = date.getFullYear();
  const mm = String(date.getMonth() + 1).padStart(2, '0');
  const dd = String(date.getDate()).padStart(2, '0');
  const hh = String(date.getHours()).padStart(2, '0');
  const min = String(date.getMinutes()).padStart(2, '0');
  return `${yyyy}-${mm}-${dd}T${hh}:${min}`;
}

function fromDateTimeLocal(value: string): string | undefined {
  if (!value.trim()) return undefined;
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return undefined;
  return date.toISOString();
}

function todaySimDate(state: SimulationState | null): string {
  const iso = state?.simulatedNow ?? state?.effectiveScenarioStartAt ?? state?.projectedFrom ?? null;
  return toDateInput(iso);
}

function registrationDefault(state: SimulationState | null): string {
  return toDateTimeLocal(state?.simulatedNow ?? state?.effectiveScenarioStartAt ?? new Date().toISOString());
}

type CreateForm = {
  airlineName: string;
  originIcao: string;
  destinationIcao: string;
  luggageCount: string;
  registrationDate: string;
  algorithmName: ShipmentCreate['algorithmName'];
};

const EMPTY_FORM: CreateForm = {
  airlineName: '',
  originIcao: '',
  destinationIcao: '',
  luggageCount: '1',
  registrationDate: '',
  algorithmName: 'Genetic Algorithm',
};

export default function ShipmentsPage() {
  const { sim, loaded: simLoaded } = useSimulation();

  const [rows, setRows] = useState<Shipment[]>([]);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [status, setStatus] = useState<ShipmentStatus | ''>('');
  const [date, setDate] = useState('');
  const [code, setCode] = useState('');
  const [origin, setOrigin] = useState('');
  const [destination, setDestination] = useState('');

  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [detail, setDetail] = useState<ShipmentDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);

  const [createOpen, setCreateOpen] = useState(false);
  const [createForm, setCreateForm] = useState<CreateForm>(EMPTY_FORM);
  const [createLoading, setCreateLoading] = useState(false);
  const [feasibility, setFeasibility] = useState<ShipmentFeasibility | null>(null);
  const [createError, setCreateError] = useState<string | null>(null);
  const [actionLoading, setActionLoading] = useState<string | null>(null);
  const [planningEvents, setPlanningEvents] = useState<ShipmentPlanningEvent[] | null>(null);
  const createDateDirtyRef = useRef(false);
  const dateDirtyRef = useRef(false);

  const dateInitialized = useRef(false);
  const simActive = Boolean(sim?.running || sim?.paused);
  const [upcomingOnly, setUpcomingOnly] = useState(false);
  const visibleRows = rows;

  useEffect(() => {
    if (simLoaded && !dateInitialized.current) {
      dateInitialized.current = true;
      setDate(todaySimDate(sim));
      setCreateForm((prev) => ({ ...prev, registrationDate: registrationDefault(sim) }));
    }
  }, [sim, simLoaded]);

  useEffect(() => {
    if (!simLoaded || !simActive) return;
    const nextDate = todaySimDate(sim);
    if (nextDate && !dateDirtyRef.current) setDate(nextDate);
    if (!createDateDirtyRef.current) {
      setCreateForm((prev) => ({ ...prev, registrationDate: registrationDefault(sim) }));
    }
  }, [sim?.simulatedNow, sim?.effectiveScenarioStartAt, sim?.running, sim?.paused, simLoaded, simActive]);

  const load = useCallback(async (targetPage: number) => {
    setLoading(true);
    try {
      const result = upcomingOnly
        ? await shipmentsApi.upcoming({
            status: status || undefined,
            code: code || undefined,
            origin: origin || undefined,
            destination: destination || undefined,
            date: date || undefined,
            page: targetPage,
            size: PAGE_SIZE,
          })
        : await shipmentsApi.list({
            status: status || undefined,
            code: code || undefined,
            origin: origin || undefined,
            destination: destination || undefined,
            date: date || undefined,
            currentOnly: simActive && status !== 'DELIVERED',
            page: targetPage,
            size: PAGE_SIZE,
            sort: status === 'DELIVERED'
              ? 'deliveredAt,desc'
              : simActive
                ? 'registrationDate,asc'
                : 'registrationDate,desc',
          });
      setRows(result.content);
      setPage(result.number);
      setTotalPages(result.totalPages);
      setTotalElements(result.totalElements);
      setError(null);
    } catch {
      setError('No se pudo cargar el maestro de envíos.');
    } finally {
      setLoading(false);
    }
  }, [code, date, destination, origin, simActive, status, upcomingOnly]);

  useEffect(() => {
    if (!date) return;
    void load(0);
  }, [date, load]);

  useEffect(() => {
    if (rows.length === 0) {
      setSelectedId(null);
      return;
    }
    if (!selectedId || !rows.some((row) => row.id === selectedId)) {
      setSelectedId(rows[0].id);
    }
  }, [rows, selectedId]);

  useEffect(() => {
    if (!selectedId) {
      setDetail(null);
      return;
    }
    setDetailLoading(true);
    shipmentsApi.getById(selectedId)
      .then(setDetail)
      .catch(() => setDetail(null))
      .finally(() => setDetailLoading(false));
  }, [selectedId]);

  useEffect(() => {
    if (!sim?.running || !date) return;
    const timer = setInterval(() => {
      void load(page);
      if (selectedId) {
        setDetailLoading(true);
        shipmentsApi.getById(selectedId)
          .then(setDetail)
          .catch(() => setDetail(null))
          .finally(() => setDetailLoading(false));
      }
    }, 5000);
    return () => clearInterval(timer);
  }, [date, load, page, selectedId, sim?.running]);

  const pageLabel = useMemo(() => `${Math.min(page + 1, Math.max(totalPages, 1))} / ${Math.max(totalPages, 1)}`, [page, totalPages]);

  function patchCreateForm<K extends keyof CreateForm>(field: K, value: CreateForm[K]) {
    if (field === 'registrationDate') createDateDirtyRef.current = true;
    setCreateForm((prev) => ({ ...prev, [field]: value }));
    setFeasibility(null);
    setCreateError(null);
  }

  function openCreateModal() {
    setCreateOpen(true);
    setFeasibility(null);
    setCreateError(null);
    createDateDirtyRef.current = false;
    setCreateForm({ ...EMPTY_FORM, registrationDate: registrationDefault(sim) });
  }

  function createPayload(): ShipmentCreate {
    return {
      airlineName: createForm.airlineName.trim(),
      originIcao: createForm.originIcao.trim().toUpperCase(),
      destinationIcao: createForm.destinationIcao.trim().toUpperCase(),
      luggageCount: Math.max(1, Number(createForm.luggageCount || '1')),
      registrationDate: fromDateTimeLocal(createForm.registrationDate),
      algorithmName: createForm.algorithmName,
    };
  }

  async function onCheckFeasibility() {
    setCreateLoading(true);
    try {
      const result = await shipmentsApi.checkFeasibility(createPayload());
      setFeasibility(result);
      setCreateError(null);
    } catch (e) {
      setCreateError(e instanceof Error ? e.message : 'No se pudo validar la factibilidad.');
    } finally {
      setCreateLoading(false);
    }
  }

  async function onCreateShipment() {
    setCreateLoading(true);
    try {
      const created = await shipmentsApi.create(createPayload());
      setCreateOpen(false);
      setSelectedId(created.id);
      setDetail(created);
      await load(0);
      setCreateError(null);
    } catch (e) {
      setCreateError(e instanceof Error ? e.message : 'No se pudo crear el envío.');
    } finally {
      setCreateLoading(false);
    }
  }

  async function runDetailAction(action: 'replan' | 'deliver' | 'history' | 'receipt' | 'alert') {
    if (!detail) return;
    setActionLoading(action);
    try {
      if (action === 'replan') {
        const updated = await shipmentsApi.replan(detail.id);
        setDetail(updated);
        await load(page);
      } else if (action === 'deliver') {
        const updated = await shipmentsApi.markDelivered(detail.id);
        setDetail(updated);
        await load(page);
      } else if (action === 'history') {
        setPlanningEvents(await shipmentsApi.planningHistory(detail.id));
      } else if (action === 'receipt') {
        await shipmentsApi.downloadReceipt(detail.id);
      } else if (action === 'alert') {
        await alertsApi.create({ shipmentId: detail.id, type: 'MANUAL_REVIEW', note: 'Alerta creada desde detalle de envío' });
      }
      setCreateError(null);
    } catch (e) {
      setCreateError(e instanceof Error ? e.message : 'No se pudo ejecutar la acción operativa.');
    } finally {
      setActionLoading(null);
    }
  }

  return (
    <div className="app-page">
      <header className="page-head">
        <div>
          <h1 className="page-head-title">Maestro de Envíos</h1>
          <p className="page-head-subtitle">
            {simActive
              ? `Seguimiento desde el tiempo simulado (${date})`
              : `Vista histórica por fecha (${date || 'sin fecha'})`}
          </p>
        </div>
        <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
          {sim?.dateAdjusted && sim?.dateAdjustmentReason ? (
            <span className="chip" style={{ background: '#45271a', color: '#ffcc9c', borderColor: '#6e3c1d' }}>
              Inicio ajustado: {sim.effectiveScenarioStartAt ? dateShort(sim.effectiveScenarioStartAt) : '-'}
            </span>
          ) : null}
          <button className="btn btn-primary" onClick={openCreateModal}>Nuevo envío</button>
        </div>
      </header>

      <div className="shipments-layout">
        <section className="surface-panel" style={{ overflow: 'hidden' }}>
          <div className="shipments-toolbar" style={{ marginBottom: 14, flexWrap: 'wrap' }}>
            <select value={status} onChange={(e) => setStatus(e.target.value as ShipmentStatus | '')} className="shipments-search" style={{ width: 170 }}>
              <option value="">Todos</option>
              <option value="PENDING">Pendientes</option>
              <option value="IN_ROUTE">En ruta</option>
              <option value="CRITICAL">Críticos</option>
              <option value="DELAYED">Atrasados</option>
              <option value="DELIVERED">Entregados</option>
            </select>
            <input type="date" value={date} onChange={(e) => { dateDirtyRef.current = true; setDate(e.target.value); }} className="shipments-search" style={{ width: 170 }} />
            <input value={code} onChange={(e) => setCode(e.target.value.toUpperCase())} placeholder="Código" className="shipments-search" style={{ width: 150 }} />
            <input value={origin} onChange={(e) => setOrigin(e.target.value.toUpperCase())} placeholder="Origen ICAO" className="shipments-search" style={{ width: 140 }} />
            <input value={destination} onChange={(e) => setDestination(e.target.value.toUpperCase())} placeholder="Destino ICAO" className="shipments-search" style={{ width: 140 }} />
            <button className="chip is-active" onClick={() => void load(0)}>Buscar</button>
            {simActive ? (
              <button
                className={`chip${upcomingOnly ? ' is-active' : ''}`}
                onClick={() => setUpcomingOnly((v) => !v)}
                title="Mostrar envíos ordenados por salida de su próximo vuelo"
              >
                Próximos (por vuelo)
              </button>
            ) : null}
          </div>

          <div style={{ overflowX: 'auto' }}>
            <table className="data-table">
              <thead>
                <tr>
                  <th>Código</th>
                  <th>Origen</th>
                  <th>Destino</th>
                  {upcomingOnly ? <th>Sale</th> : null}
                  <th>Registro</th>
                  <th>Plazo</th>
                  <th>Maletas</th>
                  <th>Estado</th>
                </tr>
              </thead>
              <tbody>
                {loading ? (
                  <tr><td colSpan={upcomingOnly ? 8 : 7}>Cargando envíos...</td></tr>
                ) : visibleRows.length === 0 ? (
                  <tr><td colSpan={upcomingOnly ? 8 : 7}>No hay envíos para los filtros seleccionados.</td></tr>
                ) : visibleRows.map((shipment) => (
                  <tr key={shipment.id} className={selectedId === shipment.id ? 'is-selected' : ''} onClick={() => setSelectedId(shipment.id)}>
                    <td>{shipment.shipmentCode}</td>
                    <td>{shipment.originAirport.icaoCode}</td>
                    <td>{shipment.destinationAirport.icaoCode}</td>
                    {upcomingOnly ? <td>{dateShort((shipment as ShipmentUpcoming).nextFlightDeparture)}</td> : null}
                    <td>{dateShort(shipment.registrationDate)}</td>
                    <td>{dateShort(shipment.deadline)}</td>
                    <td>{shipment.luggageCount}</td>
                    <td><span className={statusClass(shipment.status)}>{statusLabel(shipment.status)}</span></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: 14 }}>
            <p style={{ margin: 0, color: '#9ca3bf', fontSize: 13 }}>
              {`${totalElements.toLocaleString('es-PE')} envíos encontrados`}
            </p>
            <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
              <button className="chip" disabled={page <= 0} onClick={() => void load(Math.max(0, page - 1))}>Anterior</button>
              <span className="chip is-active">{pageLabel}</span>
              <button className="chip" disabled={page + 1 >= totalPages} onClick={() => void load(page + 1)}>Siguiente</button>
            </div>
          </div>
        </section>

        <aside className="surface-panel" style={{ padding: 16 }}>
          {detailLoading ? (
            <div className="state-panel"><p className="state-panel-title">Cargando detalle del envío</p></div>
          ) : detail ? (
            <ShipmentDetailPanel
              detail={detail}
              planningEvents={planningEvents}
              actionLoading={actionLoading}
              onAction={runDetailAction}
            />
          ) : (
            <div className="state-panel"><p className="state-panel-title">Selecciona un envío</p></div>
          )}
        </aside>

        {error ? (
          <div className="state-panel is-error" style={{ gridColumn: '1 / -1' }}>
            <p className="state-panel-title">Error</p>
            <p className="state-panel-copy">{error}</p>
          </div>
        ) : null}
      </div>

      {createOpen ? (
        <div style={overlayStyle} onClick={() => !createLoading && setCreateOpen(false)}>
          <div style={modalStyle} onClick={(e) => e.stopPropagation()}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 12 }}>
              <div>
                <p className="detail-title" style={{ marginBottom: 4 }}>Nuevo envío</p>
                <p style={{ margin: 0, color: '#9ca3bf', fontSize: 13 }}>
                  {simActive ? 'Se registrará sobre el reloj simulado activo.' : 'Registro manual sobre la red actual.'}
                </p>
              </div>
              <button className="chip" onClick={() => setCreateOpen(false)} disabled={createLoading}>Cerrar</button>
            </div>

            <div style={{ marginTop: 16, display: 'grid', gap: 10 }}>
              <label style={labelStyle}>
                <span>Aerolínea</span>
                <input value={createForm.airlineName} onChange={(e) => patchCreateForm('airlineName', e.target.value)} style={fieldStyle} />
              </label>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
                <label style={labelStyle}>
                  <span>Origen ICAO</span>
                  <input value={createForm.originIcao} onChange={(e) => patchCreateForm('originIcao', e.target.value.toUpperCase())} style={fieldStyle} />
                </label>
                <label style={labelStyle}>
                  <span>Destino ICAO</span>
                  <input value={createForm.destinationIcao} onChange={(e) => patchCreateForm('destinationIcao', e.target.value.toUpperCase())} style={fieldStyle} />
                </label>
              </div>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
                <label style={labelStyle}>
                  <span>Maletas</span>
                  <input type="number" min={1} value={createForm.luggageCount} onChange={(e) => patchCreateForm('luggageCount', e.target.value)} style={fieldStyle} />
                </label>
                <label style={labelStyle}>
                  <span>Algoritmo</span>
                  <select value={createForm.algorithmName} onChange={(e) => patchCreateForm('algorithmName', e.target.value as CreateForm['algorithmName'])} style={fieldStyle}>
                    {ALGORITHMS.map((algorithm) => <option key={algorithm} value={algorithm}>{algorithm}</option>)}
                  </select>
                </label>
              </div>
              <label style={labelStyle}>
                <span>Fecha de registro</span>
                <input type="datetime-local" value={createForm.registrationDate} onChange={(e) => patchCreateForm('registrationDate', e.target.value)} style={fieldStyle} />
              </label>
            </div>

            {feasibility ? (
              <div className={`state-panel${feasibility.feasible ? '' : ' is-error'}`} style={{ marginTop: 14 }}>
                <p className="state-panel-title">Factibilidad</p>
                <p className="state-panel-copy">{feasibility.message}</p>
                <p className="state-panel-copy">Algoritmo: {feasibility.algorithm} · Rutas candidatas: {feasibility.candidateRoutes}</p>
              </div>
            ) : null}

            {createError ? (
              <div className="state-panel is-error" style={{ marginTop: 14 }}>
                <p className="state-panel-title">Error</p>
                <p className="state-panel-copy">{createError}</p>
              </div>
            ) : null}

            <div style={{ marginTop: 16, display: 'flex', justifyContent: 'space-between', gap: 10, flexWrap: 'wrap' }}>
              <button className="btn btn-neutral" disabled={createLoading} onClick={() => void onCheckFeasibility()}>
                {createLoading ? 'Validando...' : 'Validar factibilidad'}
              </button>
              <button className="btn btn-primary" disabled={createLoading} onClick={() => void onCreateShipment()}>
                {createLoading ? 'Guardando...' : 'Crear envío'}
              </button>
            </div>
          </div>
        </div>
      ) : null}
    </div>
  );
}

function ShipmentDetailPanel({
  detail,
  planningEvents,
  actionLoading,
  onAction,
}: {
  detail: ShipmentDetail;
  planningEvents: ShipmentPlanningEvent[] | null;
  actionLoading: string | null;
  onAction: (action: 'replan' | 'deliver' | 'history' | 'receipt' | 'alert') => void;
}) {
  return (
    <>
      <div style={{ display: 'flex', justifyContent: 'space-between', gap: 10, alignItems: 'center' }}>
        <p className="detail-title">{detail.shipmentCode}</p>
        <span className={statusClass(detail.status)}>{statusLabel(detail.status)}</span>
      </div>

      <div style={{ marginTop: 12, display: 'flex', gap: 8, flexWrap: 'wrap' }}>
        <button className="chip" disabled={actionLoading !== null} onClick={() => onAction('replan')}>Replanificar</button>
        <button className="chip" disabled={actionLoading !== null || detail.status === 'DELIVERED'} onClick={() => onAction('deliver')}>Marcar entregado</button>
        <button className="chip" disabled={actionLoading !== null} onClick={() => onAction('receipt')}>Descargar comprobante</button>
        <button className="chip" disabled={actionLoading !== null} onClick={() => onAction('history')}>Ver historial de planificación</button>
        <button className="chip" disabled={actionLoading !== null} onClick={() => onAction('alert')}>Crear alerta</button>
      </div>

      <div style={{ marginTop: 12, display: 'grid', rowGap: 8 }}>
        <InfoRow label="Ruta" value={`${detail.originIcaoCode} -> ${detail.destinationIcaoCode}`} />
        <InfoRow label="Aerolínea" value={detail.airlineName} />
        <InfoRow label="Registro" value={dateShort(detail.registrationDate)} />
        <InfoRow label="Plazo" value={dateShort(detail.deadline)} />
        {detail.deliveredAt ? <InfoRow label="Entregado" value={dateShort(detail.deliveredAt)} /> : null}
        <InfoRow label="Maletas" value={`${detail.luggageCount}`} />
        <InfoRow label="Progreso" value={`${(detail.progressPercentage ?? 0).toFixed(1)}%`} />
        <InfoRow label="Último nodo confirmado" value={detail.lastConfirmedNode} />
        <InfoRow label="ETA destino" value={dateShort(detail.estimatedDestinationArrival)} />
      </div>

      <div style={{ marginTop: 18, display: 'grid', gap: 10 }}>
        <LegHighlight title="Tramo actual" leg={detail.currentLeg} empty="Sin vuelo activo en este momento." />
        <LegHighlight title="Próximo vuelo" leg={detail.nextLeg} empty="No hay otro vuelo programado pendiente." />
      </div>

      <div style={{ marginTop: 16 }}>
        <p style={sectionTitleStyle}>Itinerario completo</p>
        {detail.legs.length === 0 ? (
          <div className="state-panel"><p className="state-panel-copy">Este envío aún no tiene tramos visibles.</p></div>
        ) : (
          <div style={{ marginTop: 8, display: 'grid', gap: 8 }}>
            {detail.legs.map((leg, index) => <LegCard key={`${leg.flightId ?? 'leg'}-${index}`} leg={leg} />)}
          </div>
        )}
      </div>

      <div style={{ marginTop: 16 }}>
        <p style={sectionTitleStyle}>Historial operativo</p>
        <div style={{ marginTop: 8, display: 'grid', gap: 6 }}>
          {detail.audit.length === 0 ? (
            <div className="state-panel"><p className="state-panel-copy">Sin eventos registrados.</p></div>
          ) : detail.audit.map((event) => <AuditRow key={event.id} event={event} />)}
        </div>
      </div>

      {planningEvents ? (
        <div style={{ marginTop: 16 }}>
          <p style={sectionTitleStyle}>Planificaciones</p>
          <div style={{ marginTop: 8, display: 'grid', gap: 6 }}>
            {planningEvents.length === 0 ? (
              <div className="state-panel"><p className="state-panel-copy">Sin planificaciones registradas.</p></div>
            ) : planningEvents.map((event, index) => (
              <div key={`${event.eventAt}-${index}`} style={{ padding: '8px 10px', borderRadius: 8, background: '#171a29', border: '1px solid #262940' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', gap: 10, alignItems: 'center' }}>
                  <span style={{ color: '#dce4ff', fontSize: 13, fontWeight: 500 }}>{event.reason}</span>
                  <span style={{ color: '#8ea0c8', fontSize: 12 }}>{dateShort(event.eventAt)}</span>
                </div>
                <p style={{ margin: '4px 0 0', color: '#9ca3bf', fontSize: 12 }}>{event.algorithm} · {event.route}</p>
              </div>
            ))}
          </div>
        </div>
      ) : null}
    </>
  );
}

function LegHighlight({ title, leg, empty }: { title: string; leg: ShipmentLeg | null; empty: string }) {
  return (
    <div style={{ padding: 12, borderRadius: 10, background: '#171a29', border: '1px solid #262940' }}>
      <p style={sectionTitleStyle}>{title}</p>
      {leg ? <LegSummary leg={leg} /> : <p style={{ margin: '8px 0 0', color: '#9ca3bf', fontSize: 13 }}>{empty}</p>}
    </div>
  );
}

function LegSummary({ leg }: { leg: ShipmentLeg }) {
  return (
    <>
      <p style={{ margin: '8px 0 0', color: '#e7ecff', fontWeight: 600 }}>{leg.fromIcaoCode} {'->'} {leg.toIcaoCode}</p>
      <p style={{ margin: '4px 0 0', color: '#8ea0c8', fontSize: 12 }}>
        {leg.flightId ? <Link href={`/flights?selected=${leg.flightId}`} style={{ color: '#9bc8ff' }}>Vuelo {leg.flightCode}</Link> : 'Tramo sin vuelo asignado'}
      </p>
      <div style={{ marginTop: 6, display: 'flex', gap: 12, flexWrap: 'wrap', color: '#9ca3bf', fontSize: 12 }}>
        <span>Sale: {dateShort(leg.scheduledDeparture)}</span>
        <span>Llega: {dateShort(leg.scheduledArrival)}</span>
        <span>{stopLabel(leg.stopStatus)}</span>
      </div>
    </>
  );
}

function LegCard({ leg }: { leg: ShipmentLeg }) {
  return (
    <div style={{ padding: '10px 12px', borderRadius: 10, background: leg.current ? '#10233c' : '#171a29', border: `1px solid ${leg.current ? '#2563eb' : '#262940'}` }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
        <div>
          <p style={{ margin: 0, color: '#e7ecff', fontWeight: 600 }}>{leg.fromIcaoCode} {'->'} {leg.toIcaoCode}</p>
          <p style={{ margin: '4px 0 0', color: '#90a2c7', fontSize: 12 }}>
            {leg.flightId ? <Link href={`/flights?selected=${leg.flightId}`} style={{ color: '#9bc8ff' }}>Vuelo {leg.flightCode}</Link> : 'Sin vuelo asignado'}
          </p>
        </div>
        <span className={`chip${leg.current || leg.next ? ' is-active' : ''}`}>{stopLabel(leg.stopStatus)}</span>
      </div>
      <div style={{ marginTop: 8, display: 'flex', gap: 16, flexWrap: 'wrap', fontSize: 12, color: '#9ca3bf' }}>
        <span>Sale: {dateShort(leg.scheduledDeparture)}</span>
        <span>Llega: {dateShort(leg.scheduledArrival)}</span>
        {leg.actualArrival ? <span style={{ color: '#4ade80' }}>Real: {dateShort(leg.actualArrival)}</span> : null}
      </div>
    </div>
  );
}

function AuditRow({ event }: { event: ShipmentAuditLog }) {
  return (
    <div style={{ padding: '8px 10px', borderRadius: 8, background: '#171a29', border: '1px solid #262940' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', gap: 10, alignItems: 'center' }}>
        <span style={{ color: '#dce4ff', fontSize: 13, fontWeight: 500 }}>{event.eventType}</span>
        <span style={{ color: '#8ea0c8', fontSize: 12 }}>{dateShort(event.eventAt)}</span>
      </div>
      <p style={{ margin: '4px 0 0', color: '#9ca3bf', fontSize: 12 }}>{event.message}</p>
    </div>
  );
}

function InfoRow({ label, value }: { label: string; value: string }) {
  return (
    <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12 }}>
      <span style={{ color: '#9ca3bf', fontSize: 13 }}>{label}</span>
      <span style={{ color: '#e7ecff', fontSize: 13, textAlign: 'right' }}>{value}</span>
    </div>
  );
}

const sectionTitleStyle = { margin: 0, fontWeight: 600, color: '#c9d4f0', fontSize: 13 } as const;
const overlayStyle = {
  position: 'fixed',
  inset: 0,
  background: 'rgba(6, 9, 17, 0.72)',
  display: 'grid',
  placeItems: 'center',
  zIndex: 100,
  padding: 20,
} as const;
const modalStyle = {
  width: 'min(680px, 100%)',
  borderRadius: 14,
  background: '#10131f',
  border: '1px solid #2b3148',
  padding: 18,
  boxShadow: '0 24px 60px rgba(0,0,0,0.4)',
} as const;
const labelStyle = { display: 'grid', gap: 6, color: '#9ca3bf', fontSize: 13 } as const;
const fieldStyle = {
  width: '100%',
  height: 40,
  borderRadius: 8,
  border: '1px solid #32364f',
  background: '#171a29',
  color: '#dce4ff',
  padding: '0 10px',
  fontSize: 13,
} as const;
