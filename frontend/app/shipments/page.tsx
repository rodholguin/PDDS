'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { shipmentsApi, type ShipmentCreate } from '@/lib/api/shipmentsApi';
import { flightsApi } from '@/lib/api/flightsApi';
import type { Shipment, ShipmentDetail, ShipmentPlanningEvent, ShipmentStatus } from '@/lib/types';
import type { CSSProperties } from 'react';

type FilterTab = 'ALL' | 'IN_ROUTE' | 'PENDING' | 'ALERTS';

function filterToStatus(tab: FilterTab): ShipmentStatus | undefined {
  if (tab === 'IN_ROUTE') return 'IN_ROUTE';
  if (tab === 'PENDING') return 'PENDING';
  return undefined;
}

function statusLabel(status: ShipmentStatus): string {
  if (status === 'IN_ROUTE') return 'Normal';
  if (status === 'PENDING') return 'Alerta';
  if (status === 'CRITICAL' || status === 'DELAYED') return 'Critico';
  if (status === 'DELIVERED') return 'Entregado';
  return status;
}

function statusClass(status: ShipmentStatus): string {
  if (status === 'CRITICAL' || status === 'DELAYED') return 'status-badge status-critico';
  if (status === 'PENDING') return 'status-badge status-alerta';
  return 'status-badge status-normal';
}

function dateShort(iso: string): string {
  return new Date(iso).toLocaleDateString('es-PE');
}

function dateLong(iso: string): string {
  return new Date(iso).toLocaleString('es-PE', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

export default function ShipmentsPage() {
  const [filter, setFilter] = useState<FilterTab>('ALL');
  const [search, setSearch] = useState('');
  const [shipments, setShipments] = useState<Shipment[]>([]);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [detail, setDetail] = useState<ShipmentDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [creating, setCreating] = useState(false);

  const [detailRequested, setDetailRequested] = useState(false);
  const [feasibilityMessage, setFeasibilityMessage] = useState<string | null>(null);
  const [planningEvents, setPlanningEvents] = useState<ShipmentPlanningEvent[]>([]);
  const [flightCapacity, setFlightCapacity] = useState<Array<{ flightCode: string; routeType: string; availableCapacity: number }>>([]);

  const [form, setForm] = useState<ShipmentCreate>({
    airlineName: 'AeroSur',
    originIcao: 'LIM',
    destinationIcao: 'MAD',
    luggageCount: 5,
    algorithmName: 'Genetic Algorithm',
  });

  const loadShipments = useCallback(async () => {
    try {
      const status = filterToStatus(filter);
      const response = await shipmentsApi.list({
        status,
        code: search || undefined,
        size: 60,
      });

      let content = response.content;
      if (filter === 'ALERTS') {
        content = content.filter((shipment) => shipment.status === 'CRITICAL' || shipment.status === 'DELAYED');
      }

      setShipments(content);
      if (!selectedId && content.length) {
        setSelectedId(content[0].id);
      }
      setError(null);
    } catch {
      setError('No se pudo cargar la lista de envios.');
    } finally {
      setLoading(false);
    }
  }, [filter, search, selectedId]);

  const loadShipmentDetail = useCallback(async (id: number) => {
    setDetailRequested(true);
    try {
      const value = await shipmentsApi.getById(id);
      setDetail(value);
    } catch {
      setDetail(null);
    } finally {
      setDetailRequested(false);
    }
  }, []);

  useEffect(() => {
    const initial = setTimeout(() => {
      void loadShipments();
    }, 0);
    const interval = setInterval(() => {
      void loadShipments();
    }, 20_000);
    return () => {
      clearTimeout(initial);
      clearInterval(interval);
    };
  }, [loadShipments]);

  async function applyFilters(): Promise<void> {
    await loadShipments();
  }

  async function loadFlightCapacity(): Promise<void> {
    const rows = await flightsApi.capacityView().catch(() => []);
    setFlightCapacity(rows.map((row) => ({
      flightCode: row.flightCode,
      routeType: row.routeType,
      availableCapacity: row.availableCapacity,
    })));
  }

  async function selectShipment(id: number): Promise<void> {
    setSelectedId(id);
    await loadShipmentDetail(id);
    const events = await shipmentsApi.planningHistory(id).catch(() => []);
    setPlanningEvents(events);
  }

  useEffect(() => {
    void loadShipments();
    void loadFlightCapacity();
  }, [loadShipments]);

  const rows = useMemo(() => {
    return shipments.map((shipment) => ({
      ...shipment,
      progress: Math.round(shipment.progressPercentage),
    }));
  }, [shipments]);

  async function createShipment(): Promise<void> {
    setCreating(true);
    try {
      const feasibility = await shipmentsApi.checkFeasibility(form);
      if (!feasibility.feasible) {
        setError(feasibility.message);
        return;
      }
      setFeasibilityMessage(`${feasibility.message} (${feasibility.algorithm})`);

      const created = await shipmentsApi.create(form);
      await loadShipments();
      await selectShipment(created.id);
      setError(null);
    } catch {
      setError('No fue posible crear el nuevo envio.');
    } finally {
      setCreating(false);
    }
  }

  async function replan(): Promise<void> {
    if (!selectedId) return;
    try {
      await shipmentsApi.replan(selectedId);
      await loadShipments();
      await loadShipmentDetail(selectedId);
      setError(null);
    } catch {
      setError('No se pudo replanificar la ruta del envio.');
    }
  }

  async function markDelivered(): Promise<void> {
    if (!selectedId) return;
    try {
      await shipmentsApi.markDelivered(selectedId);
      await loadShipments();
      await loadShipmentDetail(selectedId);
      setError(null);
    } catch {
      setError('No se pudo confirmar la entrega.');
    }
  }

  return (
    <div className="app-page">
      <header className="page-head">
        <div>
          <h1 className="page-head-title">Gestion de Envios</h1>
          <p className="page-head-subtitle">Registro, planificacion y monitoreo de maletas</p>
        </div>

        <button className="btn btn-primary" onClick={createShipment} disabled={creating}>
          {creating ? 'Creando...' : 'Nuevo Envio'}
        </button>
      </header>

      <div className="shipments-layout">
        <section className="surface-panel" style={{ overflow: 'hidden' }}>
          <div className="shipments-toolbar">
            <input
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="Buscar por codigo, origen, destino..."
              className="shipments-search"
            />
            <button className="chip is-active" onClick={applyFilters}>Aplicar</button>
            {([
              ['ALL', 'Todos'],
              ['IN_ROUTE', 'En ruta'],
              ['ALERTS', 'Alertas'],
            ] as const).map(([value, label]) => (
              <button
                key={value}
                className={`chip${filter === value ? ' is-active' : ''}`}
                onClick={() => setFilter(value)}
              >
                {label}
              </button>
            ))}
          </div>

          <div style={{ overflowX: 'auto' }}>
            <table className="data-table">
              <thead>
                <tr>
                  <th>Codigo</th>
                  <th>Origen - Destino</th>
                  <th>Maletas</th>
                  <th>Fecha</th>
                  <th>Plazo</th>
                  <th>Estado</th>
                  <th>Progreso</th>
                </tr>
              </thead>
              <tbody>
                {loading ? (
                  <tr>
                    <td colSpan={7}>
                      <div className="state-panel" style={{ margin: '6px 0' }}>
                        <p className="state-panel-title">Cargando envios</p>
                        <p className="state-panel-copy">Sincronizando registros y estado de rutas.</p>
                      </div>
                    </td>
                  </tr>
                ) : rows.length === 0 ? (
                  <tr>
                    <td colSpan={7}>
                      <div className="state-panel" style={{ margin: '6px 0' }}>
                        <p className="state-panel-title">No hay coincidencias</p>
                        <p className="state-panel-copy">Prueba otro codigo, origen, destino o pestaña de estado.</p>
                      </div>
                    </td>
                  </tr>
                ) : (
                  rows.map((shipment) => (
                    <tr
                      key={shipment.id}
                      className={selectedId === shipment.id ? 'is-selected' : ''}
                      onClick={() => void selectShipment(shipment.id)}
                    >
                      <td style={{ fontWeight: 700, color: '#e8edff' }}>{shipment.shipmentCode}</td>
                      <td>{shipment.originAirport.icaoCode} - {shipment.destinationAirport.icaoCode}</td>
                      <td>{shipment.luggageCount}</td>
                      <td>{dateShort(shipment.registrationDate)}</td>
                      <td>{dateShort(shipment.deadline)}</td>
                      <td>
                        <span className={statusClass(shipment.status)}>{statusLabel(shipment.status)}</span>
                      </td>
                      <td>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                          <div className="progress-track">
                            <div
                              className="progress-fill"
                              style={{
                                width: `${Math.max(0, Math.min(100, shipment.progress))}%`,
                                background:
                                  shipment.status === 'CRITICAL' || shipment.status === 'DELAYED'
                                    ? '#ff5a64'
                                    : shipment.status === 'PENDING'
                                      ? '#f0c13a'
                                      : '#43d29d',
                              }}
                            />
                          </div>
                          <span style={{ color: '#b2bad4', minWidth: 34 }}>{shipment.progress}%</span>
                        </div>
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        </section>

        <aside className="surface-panel" style={{ padding: 16 }}>
          {detail ? (
            <>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 10 }}>
                <p className="detail-title">{detail.shipmentCode}</p>
                <span className={statusClass(detail.status)}>{statusLabel(detail.status)}</span>
              </div>

              <div style={{ marginTop: 12, display: 'grid', rowGap: 8 }}>
                <InfoRow label="Ruta" value={`${detail.originIcaoCode} - ${detail.destinationIcaoCode}`} />
                <InfoRow label="Maletas" value={`${detail.luggageCount} unidades`} />
                <InfoRow label="Aerolínea" value={detail.airlineName} />
                <InfoRow label="Fecha registro" value={dateLong(detail.registrationDate)} />
                <InfoRow label="Plazo maximo" value={dateLong(detail.deadline)} />
                <InfoRow label="Continente" value={detail.isInterContinental ? 'Intercontinental' : 'Intra-continental'} />
              </div>

              <div style={{ marginTop: 16, borderTop: '1px solid #2f334d', paddingTop: 14 }}>
                <p className="detail-section-title">Plan de Viaje</p>
                <div style={{ marginTop: 10, display: 'grid', gap: 10 }}>
                  {detail.stops.map((stop) => (
                    <div key={stop.id} style={{ display: 'grid', gridTemplateColumns: '14px 1fr', columnGap: 10 }}>
                      <span
                        style={{
                          width: 10,
                          height: 10,
                          borderRadius: 999,
                          marginTop: 7,
                          background:
                            stop.stopStatus === 'COMPLETED'
                              ? '#43d29d'
                              : stop.stopStatus === 'IN_TRANSIT'
                                ? '#f0c13a'
                                : '#596082',
                        }}
                      />
                      <div>
                        <p className="detail-stop-name">
                          {stop.airportCity} ({stop.airportIcaoCode})
                        </p>
                        <p className="detail-stop-meta">
                          {stop.scheduledArrival ? dateLong(stop.scheduledArrival) : 'Sin ETA'} - {stop.stopStatus}
                        </p>
                      </div>
                    </div>
                  ))}
                </div>
              </div>

              <div style={{ marginTop: 16, borderTop: '1px solid #2f334d', paddingTop: 14 }}>
                <p className="detail-section-title">Auditoria</p>
                <div style={{ marginTop: 10, display: 'grid', gap: 8, maxHeight: 220, overflowY: 'auto' }}>
                  {(detail.audit ?? []).length === 0 ? (
                    <p style={{ margin: 0, color: '#9ca3bf', fontSize: 13 }}>Sin eventos registrados.</p>
                  ) : (
                    detail.audit.map((entry) => (
                      <div key={entry.id} className="surface-panel" style={{ padding: 10 }}>
                        <p style={{ margin: 0, fontSize: 13, color: '#e7ecff' }}>{entry.message}</p>
                        <p style={{ margin: '3px 0 0', fontSize: 12, color: '#9ca3bf' }}>
                          {new Date(entry.eventAt).toLocaleString('es-PE')} · {entry.eventType}
                          {entry.airportIcao ? ` · ${entry.airportIcao}` : ''}
                          {entry.flightCode ? ` · ${entry.flightCode}` : ''}
                        </p>
                      </div>
                    ))
                  )}
                </div>
              </div>

              <div style={{ marginTop: 16, borderTop: '1px solid #2f334d', paddingTop: 14 }}>
                <p className="detail-section-title">Planificaciones</p>
                <div style={{ marginTop: 10, display: 'grid', gap: 8, maxHeight: 220, overflowY: 'auto' }}>
                  {planningEvents.length === 0 ? (
                    <p style={{ margin: 0, color: '#9ca3bf', fontSize: 13 }}>Sin planificaciones registradas.</p>
                  ) : (
                    planningEvents.map((event, idx) => (
                      <div key={`${event.eventType}-${event.eventAt}-${idx}`} className="surface-panel" style={{ padding: 10 }}>
                        <p style={{ margin: 0, fontSize: 13, color: '#e7ecff' }}>{event.reason} · {event.algorithm}</p>
                        <p style={{ margin: '3px 0 0', fontSize: 12, color: '#9ca3bf' }}>{new Date(event.eventAt).toLocaleString('es-PE')}</p>
                      </div>
                    ))
                  )}
                </div>
              </div>

              <div style={{ marginTop: 16, borderTop: '1px solid #2f334d', paddingTop: 14 }}>
                <p className="detail-section-title">Capacidad de vuelos (intra/inter)</p>
                <div style={{ marginTop: 10, display: 'grid', gap: 8, maxHeight: 200, overflowY: 'auto' }}>
                  {flightCapacity.slice(0, 8).map((row) => (
                    <div key={`${row.flightCode}-${row.routeType}`} className="surface-panel" style={{ padding: 10, display: 'flex', justifyContent: 'space-between' }}>
                      <span style={{ color: '#e7ecff', fontSize: 13 }}>{row.flightCode} · {row.routeType}</span>
                      <span style={{ color: '#9ca3bf', fontSize: 12 }}>Disp. {row.availableCapacity}</span>
                    </div>
                  ))}
                </div>
              </div>

              <div style={{ marginTop: 16, display: 'flex', gap: 10, flexWrap: 'wrap' }}>
                <button className="btn btn-primary" onClick={replan}>Replanificar Ruta</button>
                <button className="btn btn-neutral" onClick={markDelivered}>Marcar Entregado</button>
                <button className="chip" onClick={() => selectedId && void shipmentsApi.downloadReceipt(selectedId)}>Descargar comprobante</button>
              </div>
            </>
          ) : (
            <div className="state-panel">
              <p className="state-panel-title">
                {detailRequested ? 'Cargando detalle del envio' : 'Selecciona un envio'}
              </p>
              <p className="state-panel-copy">
                {detailRequested
                  ? 'Obteniendo plan de viaje, hitos y estado operacional.'
                  : 'El panel mostrara ruta, plazos, hitos y acciones de replanificacion.'}
              </p>
            </div>
          )}

          <div style={{ marginTop: 22, borderTop: '1px solid #2f334d', paddingTop: 12 }}>
            <p style={{ margin: 0, fontSize: 14, color: '#9ca3bf' }}>Registro rapido</p>
            <div style={{ marginTop: 10, display: 'grid', gap: 8 }}>
              <input
                value={form.airlineName}
                onChange={(e) => setForm((old) => ({ ...old, airlineName: e.target.value }))}
                placeholder="Aerolínea"
                style={miniFieldStyle}
              />
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
                <input
                  value={form.originIcao}
                  onChange={(e) => setForm((old) => ({ ...old, originIcao: e.target.value.toUpperCase() }))}
                  placeholder="Origen ICAO"
                  style={miniFieldStyle}
                />
                <input
                  value={form.destinationIcao}
                  onChange={(e) => setForm((old) => ({ ...old, destinationIcao: e.target.value.toUpperCase() }))}
                  placeholder="Destino ICAO"
                  style={miniFieldStyle}
                />
              </div>
              <input
                type="number"
                min={1}
                value={form.luggageCount}
                onChange={(e) => setForm((old) => ({ ...old, luggageCount: Number(e.target.value) }))}
                placeholder="Cantidad maletas"
                style={miniFieldStyle}
              />
            </div>
          </div>
        </aside>

        {error && (
          <div className="state-panel is-error" style={{ gridColumn: '1 / -1' }}>
            <p className="state-panel-title">Error en gestion de envios</p>
            <p className="state-panel-copy">{error}</p>
          </div>
        )}

        {feasibilityMessage && (
          <div className="state-panel" style={{ gridColumn: '1 / -1' }}>
            <p className="state-panel-title">Validacion previa de plazo</p>
            <p className="state-panel-copy">{feasibilityMessage}</p>
          </div>
        )}
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

const miniFieldStyle: CSSProperties = {
  width: '100%',
  height: 40,
  borderRadius: 10,
  border: '1px solid #32364f',
  background: '#171a29',
  color: '#dce4ff',
  padding: '0 10px',
  fontSize: 13,
  outline: 'none',
};
