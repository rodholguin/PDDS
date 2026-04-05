'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { flightsApi } from '@/lib/api/flightsApi';
import type { Flight, FlightStatus } from '@/lib/types';

const PAGE_SIZE = 50;

function statusLabel(status: FlightStatus): string {
  if (status === 'SCHEDULED') return 'Programado';
  if (status === 'IN_FLIGHT') return 'En vuelo';
  if (status === 'COMPLETED') return 'Completado';
  return 'Cancelado';
}

export default function FlightsPage() {
  const [rows, setRows] = useState<Flight[]>([]);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [status, setStatus] = useState<FlightStatus>('SCHEDULED');
  const [date, setDate] = useState('');
  const [code, setCode] = useState('');
  const [origin, setOrigin] = useState('');
  const [destination, setDestination] = useState('');

  const load = useCallback(async (targetPage: number) => {
    setLoading(true);
    try {
      const result = await flightsApi.search({
        status,
        date: date || undefined,
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

  const pageLabel = useMemo(() => `${Math.min(page + 1, Math.max(totalPages, 1))} / ${Math.max(totalPages, 1)}`, [page, totalPages]);

  return (
    <div className="app-page">
      <header className="page-head">
        <div>
          <h1 className="page-head-title">Vuelos Programados</h1>
          <p className="page-head-subtitle">Listado masivo paginado de vuelos operativos</p>
        </div>
      </header>

      <section className="surface-panel" style={{ padding: 16 }}>
        <div className="shipments-toolbar" style={{ marginBottom: 14, flexWrap: 'wrap' }}>
          <select value={status} onChange={(e) => setStatus(e.target.value as FlightStatus)} className="shipments-search" style={{ width: 170 }}>
            <option value="SCHEDULED">Programados</option>
            <option value="IN_FLIGHT">En vuelo</option>
            <option value="COMPLETED">Completados</option>
            <option value="CANCELLED">Cancelados</option>
          </select>
          <input type="date" value={date} onChange={(e) => setDate(e.target.value)} className="shipments-search" style={{ width: 170 }} />
          <input value={code} onChange={(e) => setCode(e.target.value.toUpperCase())} placeholder="Codigo" className="shipments-search" style={{ width: 140 }} />
          <input value={origin} onChange={(e) => setOrigin(e.target.value.toUpperCase())} placeholder="Origen ICAO" className="shipments-search" style={{ width: 130 }} />
          <input value={destination} onChange={(e) => setDestination(e.target.value.toUpperCase())} placeholder="Destino ICAO" className="shipments-search" style={{ width: 130 }} />
          <button className="chip is-active" onClick={() => void load(0)}>Buscar</button>
        </div>

        <div style={{ overflowX: 'auto' }}>
          <table className="data-table">
            <thead>
              <tr>
                <th>Codigo</th>
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
              ) : rows.length === 0 ? (
                <tr><td colSpan={8}>No hay vuelos para los filtros seleccionados.</td></tr>
              ) : rows.map((flight) => (
                <tr key={flight.id}>
                  <td>{flight.flightCode}</td>
                  <td>{flight.originAirport.icaoCode}</td>
                  <td>{flight.destinationAirport.icaoCode}</td>
                  <td>{new Date(flight.scheduledDeparture).toLocaleString('es-PE', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' })}</td>
                  <td>{new Date(flight.scheduledArrival).toLocaleString('es-PE', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' })}</td>
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
            {totalElements.toLocaleString('es-PE')} vuelos encontrados
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
    </div>
  );
}
