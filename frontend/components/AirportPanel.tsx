'use client';

import { useCallback, useEffect, useState, type CSSProperties } from 'react';
import { dashboardApi } from '@/lib/api/dashboardApi';
import type { Airport, AirportStatus, NodeDetail, FlightScheduleEntry } from '@/lib/types';

function colorSemaforo(status: AirportStatus): string {
  if (status === 'SIN_USO') return '#64748b';
  if (status === 'NORMAL') return '#22c55e';
  if (status === 'ALERTA') return '#f59e0b';
  return '#ef4444';
}

function fmtPct(value: number): string {
  return `${value.toFixed(1)}%`;
}

type SortDir = 'asc' | 'desc';

export function AirportPanel({ airports, onFocusAirport, externalSelectedIcao, liveOnly = false }: { airports: Airport[]; onFocusAirport?: (icao: string) => void; externalSelectedIcao?: string | null; liveOnly?: boolean }) {
  const [sortDir, setSortDir] = useState<SortDir>('desc');
  const [selectedIcao, setSelectedIcao] = useState<string | null>(null);
  const [nodeDetail, setNodeDetail] = useState<NodeDetail | null>(null);
  const [loading, setLoading] = useState(false);

  const sorted = [...airports].sort((a, b) =>
    sortDir === 'desc'
      ? b.occupancyPct - a.occupancyPct
      : a.occupancyPct - b.occupancyPct
  );

  const toggleSort = () => setSortDir((d) => (d === 'desc' ? 'asc' : 'desc'));

  const handleSelect = useCallback(async (icao: string) => {
    if (selectedIcao === icao) {
      setSelectedIcao(null);
      setNodeDetail(null);
      return;
    }
    setSelectedIcao(icao);
    setLoading(true);
    try {
      const detail = await dashboardApi.getNodeDetail(icao);
      setNodeDetail(detail);
    } catch {
      setNodeDetail(null);
    } finally {
      setLoading(false);
    }
  }, [selectedIcao]);

  useEffect(() => {
    if (externalSelectedIcao && externalSelectedIcao !== selectedIcao) {
      setSelectedIcao(externalSelectedIcao);
      setLoading(true);
      dashboardApi.getNodeDetail(externalSelectedIcao).then(setNodeDetail).catch(() => setNodeDetail(null)).finally(() => setLoading(false));
    }
  }, [externalSelectedIcao]);

  useEffect(() => {
    if (externalSelectedIcao === null) {
      setSelectedIcao(null);
      setNodeDetail(null);
    }
  }, [externalSelectedIcao]);

  useEffect(() => {
    if (!airports.find((a) => a.icaoCode === selectedIcao)) {
      setSelectedIcao(null);
      setNodeDetail(null);
    }
  }, [airports, selectedIcao]);

  return (
    <section style={{ marginTop: 16 }}>
      <p style={{ margin: 0, fontWeight: 700, color: '#eaf0ff' }}>Lista de aeropuertos</p>
      <div style={{ marginTop: 8, display: 'grid', gap: 4 }}>
        <div style={{
          display: 'grid',
          gridTemplateColumns: 'auto 1fr auto',
          gap: 6,
          padding: '4px 8px',
          fontSize: 11,
          color: '#6b7392',
          fontWeight: 600,
          textTransform: 'uppercase',
          letterSpacing: 0.3,
        }}>
          <span />
          <span>ICAO · Ciudad</span>
          <button
            onClick={toggleSort}
            title={sortDir === 'desc' ? 'Orden: mayor ocupación primero' : 'Orden: menor ocupación primero'}
            style={{ background: 'none', border: 'none', color: '#9ca7c8', cursor: 'pointer', fontSize: 11, fontWeight: 600, padding: 0, textAlign: 'right' }}
          >
            Ocup. {sortDir === 'desc' ? '↓' : '↑'}
          </button>
        </div>
        {sorted.map((a) => {
          const isSelected = selectedIcao === a.icaoCode;
          return (
            <div key={a.id}>
              <button
                onClick={() => void handleSelect(a.icaoCode)}
                onDoubleClick={() => onFocusAirport?.(a.icaoCode)}
                style={{
                  width: '100%',
                  display: 'grid',
                  gridTemplateColumns: 'auto 1fr auto',
                  gap: 6,
                  alignItems: 'center',
                  padding: '6px 8px',
                  borderRadius: 6,
                  border: `1px solid ${isSelected ? '#5f82ff' : 'transparent'}`,
                  background: isSelected ? 'rgba(95,130,255,0.1)' : 'transparent',
                  color: '#dce4ff',
                  cursor: 'pointer',
                  fontSize: 12,
                  textAlign: 'left',
                }}
              >
                <span style={{
                  width: 8,
                  height: 8,
                  borderRadius: 99,
                  background: colorSemaforo(a.status),
                  flexShrink: 0,
                }} />
                <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  <strong style={{ fontFamily: 'monospace' }}>{a.icaoCode}</strong>
                  <span style={{ color: '#93a0bf' }}> · {a.city}</span>
                </span>
                <span style={{ fontVariantNumeric: 'tabular-nums', color: colorSemaforo(a.status) }}>
                  {fmtPct(a.occupancyPct)}
                </span>
              </button>
              {isSelected && (
                <div style={{ margin: '4px 0 4px 16px', padding: '8px 10px', borderRadius: 6, background: '#171a29', border: '1px solid #262940', fontSize: 12, color: '#9ca3bf' }}>
                  {loading ? (
                    <span>Cargando...</span>
                  ) : nodeDetail ? (
                    <AirportDetail detail={nodeDetail} onFocus={onFocusAirport} airport={liveOnly ? a : undefined} liveOnly={liveOnly} />
                  ) : (
                    <span>Sin datos</span>
                  )}
                </div>
              )}
            </div>
          );
        })}
      </div>
    </section>
  );
}

function AirportDetail({ detail, onFocus, airport, liveOnly = false }: { detail: NodeDetail; onFocus?: (icao: string) => void; airport?: Airport; liveOnly?: boolean }) {
  const currentStorageLoad = liveOnly ? (airport?.currentStorageLoad ?? 0) : detail.currentStorageLoad;
  const occupancyPct = liveOnly ? (airport?.occupancyPct ?? 0) : detail.occupancyPct;
  const status = liveOnly ? (airport?.status ?? 'SIN_USO') : detail.status;
  const storedShipments = liveOnly ? 0 : detail.storedShipments;
  const inboundShipments = liveOnly ? 0 : detail.inboundShipments;
  const outboundShipments = liveOnly ? 0 : detail.outboundShipments;
  return (
    <div style={{ display: 'grid', gap: 8 }}>
      <div>
        <p style={{ margin: 0, fontWeight: 600, color: '#eaf0ff' }}>{detail.icaoCode} · {detail.city}</p>
        <p style={{ margin: '2px 0 0', fontSize: 11, color: '#6b7392' }}>{detail.country} · {detail.continent}</p>
        {onFocus ? <button onClick={() => onFocus(detail.icaoCode)} style={{ marginTop: 4, background: 'none', border: 'none', color: '#93c5fd', cursor: 'pointer', fontSize: 11, padding: 0, textDecoration: 'underline' }}>Ver en mapa</button> : null}
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 4, fontSize: 11 }}>
        <span>Almacenados: <strong style={{ color: '#dce4ff' }}>{storedShipments}</strong></span>
        <span>Entrantes: <strong style={{ color: '#dce4ff' }}>{inboundShipments}</strong></span>
        <span>Salientes: <strong style={{ color: '#dce4ff' }}>{outboundShipments}</strong></span>
        <span>Ocupación: <strong style={{ color: colorSemaforo(status) }}>{fmtPct(occupancyPct)}</strong></span>
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 4, fontSize: 11 }}>
        <span>Cap. usada: <strong style={{ color: '#dce4ff' }}>{currentStorageLoad}/{detail.maxStorageCapacity}</strong></span>
        <span>Vuelos prog.: <strong style={{ color: '#dce4ff' }}>{detail.scheduledFlights}</strong></span>
      </div>
      <div>
        <p style={{ margin: '6px 0 4px', fontWeight: 600, color: '#eaf0ff', fontSize: 11 }}>Próximos vuelos</p>
        {detail.nextFlights.length === 0 ? (
          <p style={{ margin: 0, fontSize: 11, color: '#6b7392' }}>Sin vuelos programados</p>
        ) : (
          <div style={{ display: 'grid', gap: 3 }}>
            {detail.nextFlights.slice(0, 6).map((f, i) => (
              <FlightRow key={i} flight={f} />
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

function FlightRow({ flight }: { flight: FlightScheduleEntry }) {
  const dep = flight.departure ? flight.departure.substring(11, 16) : '--:--';
  const arr = flight.arrival ? flight.arrival.substring(11, 16) : '--:--';
  return (
    <div style={{
      display: 'grid',
      gridTemplateColumns: '1fr auto',
      gap: 4,
      padding: '3px 6px',
      borderRadius: 4,
      background: '#1e2137',
      fontSize: 11,
    }}>
      <span>
        <strong style={{ fontFamily: 'monospace', color: '#dce4ff' }}>{flight.flightCode}</strong>
        <span style={{ color: '#93a0bf' }}> {flight.originIcao}→{flight.destinationIcao}</span>
      </span>
      <span style={{ color: '#6b7392', fontVariantNumeric: 'tabular-nums' }}>
        {dep}–{arr}
      </span>
    </div>
  );
}

const _cell: CSSProperties = { padding: '2px 0', fontSize: 11, color: '#9ca3bf' };
