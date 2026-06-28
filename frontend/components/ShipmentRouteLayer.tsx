'use client';

import { memo, useEffect, useMemo, useState } from 'react';
import { Layer, Source } from 'react-map-gl/maplibre';
import type { FeatureCollection, LineString } from 'geojson';
import { shipmentsApi } from '@/lib/api/shipmentsApi';
import type { ShipmentDetail, ShipmentStatus } from '@/lib/types';

const ROUTE_SOURCE = 'shipment-route';

const STATUS_COLORS: Record<ShipmentStatus, string> = {
  DELIVERED: '#22c55e',
  IN_ROUTE: '#3b82f6',
  CRITICAL: '#ef4444',
  DELAYED: '#ef4444',
  PENDING: '#94a3b8',
};

type Props = {
  shipmentId: number | null;
};

function ShipmentRouteLayerComponent({ shipmentId }: Props) {
  const [detail, setDetail] = useState<ShipmentDetail | null>(null);

  useEffect(() => {
    if (shipmentId == null) {
      setDetail(null);
      return;
    }
    let cancelled = false;
    shipmentsApi.getById(shipmentId).then((data) => {
      if (!cancelled) setDetail(data);
    });
    return () => { cancelled = true; };
  }, [shipmentId]);

  const fc = useMemo((): FeatureCollection<LineString> => {
    if (!detail || !detail.stops || detail.stops.length < 2) {
      return { type: 'FeatureCollection', features: [] };
    }
    return {
      type: 'FeatureCollection',
      features: [{
        type: 'Feature',
        geometry: {
          type: 'LineString',
          coordinates: detail.stops.map((s) => [s.airportLongitude, s.airportLatitude]),
        },
        properties: {},
      }],
    };
  }, [detail]);

  if (!detail || fc.features.length === 0) return null;

  const status = detail.status;
  const isDashed = status === 'PENDING';

  return (
    <Source id={ROUTE_SOURCE} type="geojson" data={fc}>
      <Layer
        id="shipment-route-line"
        type="line"
        source={ROUTE_SOURCE}
        layout={{ 'line-cap': 'round', 'line-join': 'round' }}
        paint={{
          'line-color': STATUS_COLORS[status],
          'line-width': 2,
          'line-opacity': 0.7,
          ...(isDashed ? { 'line-dasharray': [4, 4] } : {}),
        }}
      />
    </Source>
  );
}

export const ShipmentRouteLayer = memo(ShipmentRouteLayerComponent);
