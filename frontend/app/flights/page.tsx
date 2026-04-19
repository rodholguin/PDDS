'use client';

import { Suspense, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import Link from 'next/link';
import { useSearchParams } from 'next/navigation';
import { flightsApi } from '@/lib/api/flightsApi';
import { useSimulation } from '@/lib/SimulationContext';
import type { Flight, FlightDetailResponse, FlightStatus } from '@/lib/types';

const PAGE_SIZE = 50;

function statusLabel(status: FlightStatus): string {
  if (status === 'SCHEDULED') return 'Programado';
  if (status === 'IN_FLIGHT') return 'En vuelo';
  if (status === 'COMPLETED') return 'Completado';
  return 'Cancelado';
}

function dateShort(iso: string): string {
  return new Date(iso).toLocaleString('es-PE', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' });
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

export default function FlightsPage() {
  return (
    <Suspense fallback={<div className="app-page"><section className="surface-panel" style={{ padding: 16 }}>Cargando vuelos...</section></div>}>
      <FlightsPageContent />
    </Suspense>
  );
}

function FlightsPageContent() {
  const searchParams = useSearchParams();
  const { sim, simulatedNowMs, upcomingFlights } = useSimulation();

  const [rows, setRows] = useState<Flight[]>([]);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [status, setStatus] = useState<FlightStatus>('IN_FLIGHT');
  const [date, setDate] = useState('');
  const [code, setCode] = useState('');
  const [origin, setOrigin] = useState('');
  const [destination, setDestination] = useState('');
  const [upcomingOnly, setUpcomingOnly] = useState(true);

  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [detail, setDetail] = useState<FlightDetailResponse | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [cancelLoading, setCancelLoading] = useState(false);
  const userTouchedDateRef = useRef(false);

  // Default date = sim's current day until the user edits it explicitly.
  useEffect(() => {
    const next = toDateInput(sim?.simulatedNow ?? sim?.effectiveScenarioStartAt);
    if (next && !userTouchedDateRef.current) setDate(next);
  }, [sim?.simulatedNow, sim?.effectiveScenarioStartAt]);

  useEffect(() => {
    const raw = searchParams.get('selected');
    if (!raw) return;
    const id = Number(raw);
    if (Number.isFinite(id) && id > 0) setSelectedId(id);
  }, [searchParams]);

  useEffect(() => {
    const selectedStatus = searchParams.get('status');
    if (!selectedStatus) return;
    const candidate = selectedStatus.toUpperCase();
    if (candidate === 'SCHEDULED' || candidate === 'IN_FLIGHT' || candidate === 'COMPLETED' || candidate === 'CANCELLED') {
      setStatus(candidate);
      setUpcomingOnly(false);
    }
  }, [searchParams]);

  const load = useCallback(async (targetPage: number) => {
    if (!date) return; // do not fire empty-date queries on mount
    setLoading(true);
    try {
      const result = await flightsApi.search({
        status,
        date,
        code: code || undefined,
        origin: origin || undefined,
        destination: destination || undefined,
        page: targetPage,
        size: PAGE_SIZE,
        sort: 'scheduledDeparture',
        direction: 'asc',
      });
      setRows(result.content);
      setPage(result.page);
      setTotalPages(result.totalPages);
      setTotalElements(result.totalElements);
      setError(null);
    } catch {
      setError('No se pudo cargar la lista de vuelos.');
    } finally {
      setLoading(false);
    }
  }, [code, date, destination, origin, status]);

  useEffect(() => {
    void load(0);
  }, [load]);

  // Client-side "upcoming only" filter — hides flights whose scheduled departure already passed
  // in simulated time. Avoids extra server round-trip while giving the user an instant view.
  const visibleRows = useMemo(() => {
    if (!upcomingOnly || status !== 'SCHEDULED' || !simulatedNowMs) return rows;
    return rows.filter((flight) => {
      const dep = new Date(flight.scheduledDeparture).getTime();
      return Number.isFinite(dep) && dep >= simulatedNowMs - 60_000;
    });
  }, [rows, upcomingOnly, status, simulatedNowMs]);

  useEffect(() => {
    if (visibleRows.length === 0) {
      if (!selectedId || !searchParams.get('selected')) {
        setSelectedId(null);
      }
      return;
    }
    if (searchParams.get('selected')) return;
    if (!selectedId || visibleRows.some((row) => row.id === selectedId)) return;
    setSelectedId(visibleRows[0].id);
  }, [visibleRows, selectedId, searchParams]);

  useEffect(() => {
    if (!selectedId) {
      setDetail(null);
      return;
    }
    setDetailLoading(true);
    flightsApi.getById(selectedId)
      .then(setDetail)
      .catch(() => setDetail(null))
      .finally(() => setDetailLoading(false));
  }, [selectedId]);

  useEffect(() => {
    if (!sim?.running) return;
    const timer = setInterval(() => {
      void load(page);
      if (selectedId) {
        setDetailLoading(true);
        flightsApi.getById(selectedId)
          .then(setDetail)
          .catch(() => setDetail(null))
          .finally(() => setDetailLoading(false));
      }
    }, 5000);
    return () => clearInterval(timer);
  }, [load, page, selectedId, sim?.running]);

  const pageLabel = useMemo(() => `${Math.min(page + 1, Math.max(totalPages, 1))} / ${Math.max(totalPages, 1)}`, [page, totalPages]);
  const simActive = Boolean(sim?.running || sim?.paused);

  async function onCancelFlight(): Promise<void> {
    if (!detail) return;
    try {
      setCancelLoading(true);
      await flightsApi.cancel(detail.flight.id);
      await load(page);
      if (selectedId) {
        setDetail(await flightsApi.getById(selectedId));
      }
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'No se pudo cancelar el vuelo.');
    } finally {
      setCancelLoading(false);
    }
  }

  return (
    <div className="app-page">
      <header className="page-head">
        <div>
          <h1 className="page-head-title">Vuelos Operativos</h1>
          <p className="page-head-subtitle">
            {simActive ? `Agenda sobre tiempo simulado (${date || 'sin fecha'})` : 'Listado operativo de vuelos y capacidad'}
          </p>
        </div>
      </header>

      <div className="shipments-layout">
        <section className="surface-panel" style={{ padding: 16 }}>
          <div className="shipments-toolbar" style={{ marginBottom: 14, flexWrap: 'wrap' }}>
            <select value={status} onChange={(e) => setStatus(e.target.value as FlightStatus)} className="shipments-search" style={{ width: 170 }}>
              <option value="SCHEDULED">Programados</option>
              <option value="IN_FLIGHT">En vuelo</option>
              <option value="COMPLETED">Completados</option>
              <option value="CANCELLED">Cancelados</option>
            </select>
            <input type="date" value={date} onChange={(e) => { userTouchedDateRef.current = true; setDate(e.target.value); }} className="shipments-search" style={{ width: 170 }} />
            <input value={code} onChange={(e) => setCode(e.target.value.toUpperCase())} placeholder="Código" className="shipments-search" style={{ width: 140 }} />
            <input value={origin} onChange={(e) => setOrigin(e.target.value.toUpperCase())} placeholder="Origen ICAO" className="shipments-search" style={{ width: 130 }} />
            <input value={destination} onChange={(e) => setDestination(e.target.value.toUpperCase())} placeholder="Destino ICAO" className="shipments-search" style={{ width: 130 }} />
            {status === 'SCHEDULED' && simActive ? (
              <button
                className={`chip${upcomingOnly ? ' is-active' : ''}`}
                onClick={() => setUpcomingOnly((v) => !v)}
                title="Mostrar solo vuelos cuyo despegue aún no ha ocurrido en el reloj simulado"
              >
                {upcomingOnly ? 'Solo próximos' : 'Todos del día'}
              </button>
            ) : null}
            <button className="chip is-active" onClick={() => void load(0)}>Buscar</button>
          </div>

          {simActive && upcomingFlights.length > 0 ? (
            <div style={{ marginBottom: 14, padding: 10, borderRadius: 10, background: '#10233c', border: '1px solid #2563eb' }}>
              <p style={{ margin: 0, color: '#cfe1ff', fontSize: 12, fontWeight: 600 }}>
                Próximos despegues ({upcomingFlights.length})
              </p>
              <div style={{ marginTop: 6, display: 'flex', gap: 6, overflowX: 'auto' }}>
                {upcomingFlights.slice(0, 8).map((flight) => (
                  <button
                    key={flight.id}
                    className={`chip${selectedId === flight.id ? ' is-active' : ''}`}
                    onClick={() => setSelectedId(flight.id)}
                    style={{ whiteSpace: 'nowrap' }}
                  >
                    {flight.flightCode} · {flight.originAirport.icaoCode}→{flight.destinationAirport.icaoCode} · {dateShort(flight.scheduledDeparture)}
                  </button>
                ))}
              </div>
            </div>
          ) : null}

          <div style={{ overflowX: 'auto' }}>
            <table className="data-table">
              <thead>
                <tr>
                  <th>Código</th>
                  <th>Origen</th>
                  <th>Destino</th>
                  <th>Salida</th>
                  <th>Llegada</th>
                  <th>Capacidad</th>
                  <th>Disponible</th>
                  <th>Estado</th>
                </tr>
              </thead>
              <tbody>
                {loading ? (
                  <tr><td colSpan={8}>Cargando vuelos...</td></tr>
                ) : visibleRows.length === 0 ? (
                  <tr><td colSpan={8}>
                    {upcomingOnly && status === 'SCHEDULED' && rows.length > 0
                      ? 'No hay despegues pendientes en el día simulado. Usa "Todos del día" para ver los que ya salieron.'
                      : 'No hay vuelos para los filtros seleccionados.'}
                  </td></tr>
                ) : visibleRows.map((flight) => (
                  <tr key={flight.id} className={selectedId === flight.id ? 'is-selected' : ''} onClick={() => setSelectedId(flight.id)}>
                    <td>{flight.flightCode}</td>
                    <td>{flight.originAirport.icaoCode}</td>
                    <td>{flight.destinationAirport.icaoCode}</td>
                    <td>{dateShort(flight.scheduledDeparture)}</td>
                    <td>{dateShort(flight.scheduledArrival)}</td>
                    <td>{flight.maxCapacity}</td>
                    <td>{flight.availableCapacity}</td>
                    <td>{statusLabel(flight.status)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: 14 }}>
            <p style={{ margin: 0, color: '#9ca3bf', fontSize: 13 }}>
              {visibleRows.length !== rows.length
                ? `${visibleRows.length.toLocaleString('es-PE')} mostrados · ${totalElements.toLocaleString('es-PE')} totales`
                : `${totalElements.toLocaleString('es-PE')} vuelos encontrados`}
            </p>
            <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
              <button className="chip" disabled={page <= 0} onClick={() => void load(Math.max(0, page - 1))}>Anterior</button>
              <span className="chip is-active">{pageLabel}</span>
              <button className="chip" disabled={page + 1 >= totalPages} onClick={() => void load(page + 1)}>Siguiente</button>
            </div>
          </div>

          {error ? (
            <div className="state-panel is-error" style={{ marginTop: 12 }}>
              <p className="state-panel-title">Error</p>
              <p className="state-panel-copy">{error}</p>
            </div>
          ) : null}
        </section>

        <aside className="surface-panel" style={{ padding: 16 }}>
          {detailLoading ? (
            <div className="state-panel"><p className="state-panel-title">Cargando detalle del vuelo</p></div>
          ) : detail ? (
            <>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 10 }}>
                <p className="detail-title">{detail.flight.flightCode}</p>
                <span className="chip is-active">{statusLabel(detail.flight.status)}</span>
              </div>
              <div style={{ marginTop: 12, display: 'grid', rowGap: 8 }}>
                <InfoRow label="Ruta" value={`${detail.flight.originIcao} -> ${detail.flight.destinationIcao}`} />
                <InfoRow label="Salida" value={dateShort(detail.flight.scheduledDeparture)} />
                <InfoRow label="Llegada" value={dateShort(detail.flight.scheduledArrival)} />
                <InfoRow label="Capacidad" value={`${detail.flight.currentLoad}/${detail.flight.maxCapacity}`} />
                <InfoRow label="Disponible" value={`${detail.availableCapacity}`} />
                <InfoRow label="Ocupación" value={`${detail.loadPct.toFixed(1)}%`} />
              </div>

              <div style={{ marginTop: 12, display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                <button
                  className="chip"
                  disabled={cancelLoading || detail.flight.status === 'COMPLETED' || detail.flight.status === 'CANCELLED'}
                  onClick={() => void onCancelFlight()}
                >
                  {cancelLoading ? 'Cancelando...' : 'Cancelar vuelo'}
                </button>
              </div>

              <div style={{ marginTop: 16 }}>
                <p style={{ margin: 0, fontWeight: 600, color: '#c9d4f0', fontSize: 13 }}>Envíos asignados</p>
                {detail.assignedShipments.length === 0 ? (
                  <div className="state-panel" style={{ marginTop: 8 }}><p className="state-panel-copy">No hay envíos asociados a este vuelo.</p></div>
                ) : (
                  <div style={{ marginTop: 8, display: 'grid', gap: 8 }}>
                    {detail.assignedShipments.map((shipment) => (
                      <div key={shipment.shipmentId} style={{ padding: '10px 12px', borderRadius: 10, background: '#171a29', border: '1px solid #262940' }}>
                        <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, alignItems: 'center' }}>
                          <Link href={`/shipments?selected=${shipment.shipmentId}`} style={{ color: '#dce4ff', fontWeight: 600, textDecoration: 'none' }}>
                            {shipment.shipmentCode}
                          </Link>
                          <span className="chip">{shipment.status}</span>
                        </div>
                        <p style={{ margin: '4px 0 0', color: '#9ca3bf', fontSize: 12 }}>{shipment.airlineName} · {shipment.luggageCount} maletas · tramo #{shipment.stopOrder}</p>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </>
          ) : (
            <div className="state-panel"><p className="state-panel-title">Selecciona un vuelo</p></div>
          )}
        </aside>
      </div>
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
