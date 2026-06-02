'use client';

import { memo, useEffect, useMemo } from 'react';
import { Layer, Source, type LayerProps, type MapRef } from 'react-map-gl/maplibre';
import type { FeatureCollection, Point } from 'geojson';
import type { Map as MapLibreMap } from 'maplibre-gl';
import type { MapLiveFlight } from '@/lib/types';

export const FLIGHT_SOURCE_ID = 'flights-source';
export const FLIGHT_LAYER_ID = 'flights-layer';
export const FLIGHT_HIT_LAYER_ID = 'flights-hit-layer';
const PLANE_DEFAULT_IMAGE = 'plane-default';
const PLANE_SELECTED_IMAGE = 'plane-selected';

type FlightFeatureProperties = {
  flightId: number;
  flightCode: string;
  originIcao: string;
  destinationIcao: string;
  loadPct: number;
  rotation: number;
  selected: boolean;
};

type FlightsFeatureCollection = FeatureCollection<Point, FlightFeatureProperties>;

const EMPTY_FEATURE_COLLECTION: FlightsFeatureCollection = {
  type: 'FeatureCollection',
  features: [],
};

const FLIGHT_LAYER_STYLE: LayerProps = {
  id: FLIGHT_LAYER_ID,
  type: 'symbol',
  source: FLIGHT_SOURCE_ID,
  layout: {
    'icon-image': ['case', ['boolean', ['get', 'selected'], false], PLANE_SELECTED_IMAGE, PLANE_DEFAULT_IMAGE],
    'icon-size': 0.8,
    'icon-rotate': ['coalesce', ['get', 'rotation'], 0],
    'icon-rotation-alignment': 'map',
    'icon-allow-overlap': true,
    'icon-ignore-placement': true,
    'icon-anchor': 'center',
  },
};

const FLIGHT_HIT_LAYER_STYLE: LayerProps = {
  id: FLIGHT_HIT_LAYER_ID,
  type: 'circle',
  source: FLIGHT_SOURCE_ID,
  paint: {
    'circle-radius': 14,
    'circle-color': '#ffffff',
    'circle-opacity': 0.001,
  },
};

function computeBearing(fromLat: number, fromLon: number, toLat: number, toLon: number): number {
  const toRad = (value: number) => (value * Math.PI) / 180;
  const toDeg = (value: number) => (value * 180) / Math.PI;
  const startLat = toRad(fromLat);
  const startLon = toRad(fromLon);
  const endLat = toRad(toLat);
  const endLon = toRad(toLon);
  const deltaLon = endLon - startLon;

  const y = Math.sin(deltaLon) * Math.cos(endLat);
  const x = Math.cos(startLat) * Math.sin(endLat)
    - Math.sin(startLat) * Math.cos(endLat) * Math.cos(deltaLon);

  return (toDeg(Math.atan2(y, x)) + 360) % 360;
}

function planeIconDataUrl(fill: string, stroke: string): string {
  const svg = `
    <svg xmlns="http://www.w3.org/2000/svg" width="48" height="48" viewBox="0 0 24 24">
      <path d="M21 16.5V14l-8-5V3.5a1.5 1.5 0 0 0-3 0V9l-8 5v2.5l8-2.5V20l-2 1.5V23l4-1 4 1v-1.5L14 20v-6l8 2.5Z" fill="${fill}" stroke="${stroke}" stroke-width="1.35" stroke-linejoin="round" stroke-linecap="round"/>
    </svg>`;
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`;
}

async function ensureFlightImages(map: MapLibreMap): Promise<void> {
  const register = async (name: string, url: string) => {
    if (map.hasImage(name)) {
      return;
    }
    const image = await map.loadImage(url);
    if (!map.hasImage(name)) {
      map.addImage(name, image.data, { pixelRatio: 2 });
    }
  };

  await Promise.all([
    register(PLANE_DEFAULT_IMAGE, planeIconDataUrl('#0f172a', '#f8fafc')),
    register(PLANE_SELECTED_IMAGE, planeIconDataUrl('#f97316', '#fff7ed')),
  ]);
}

function buildFeatureCollection(flights: MapLiveFlight[], selectedFlightId: number | null): FlightsFeatureCollection {
  return {
    type: 'FeatureCollection',
    features: flights.map((flight) => ({
      type: 'Feature',
      geometry: {
        type: 'Point',
        coordinates: [flight.currentLongitude, flight.currentLatitude],
      },
      properties: {
        flightId: flight.flightId,
        flightCode: flight.flightCode,
        originIcao: flight.originIcao,
        destinationIcao: flight.destinationIcao,
        loadPct: flight.loadPct,
        rotation: computeBearing(
          flight.currentLatitude,
          flight.currentLongitude,
          flight.destinationLatitude,
          flight.destinationLongitude,
        ),
        selected: selectedFlightId === flight.flightId,
      },
    })),
  };
}

type FlightMapLayerProps = {
  mapRef: React.RefObject<MapRef | null>;
  mapReady: boolean;
  flights: MapLiveFlight[];
  selectedFlightId: number | null;
  onSelectedFlightVisualChange: (flight: MapLiveFlight | null) => void;
};

function FlightMapLayerComponent({ mapRef, mapReady, flights, selectedFlightId, onSelectedFlightVisualChange }: FlightMapLayerProps) {
  const data = useMemo(
    () => (flights.length === 0 ? EMPTY_FEATURE_COLLECTION : buildFeatureCollection(flights, selectedFlightId)),
    [flights, selectedFlightId],
  );

  useEffect(() => {
    if (!mapReady) return;
    const map = mapRef.current?.getMap();
    if (!map) return;

    let cancelled = false;
    const registerImages = async () => {
      try {
        await ensureFlightImages(map);
      } catch {
        if (!cancelled) {
          // Retry on subsequent style changes.
        }
      }
    };

    void registerImages();
    map.on('styledata', registerImages);
    return () => {
      cancelled = true;
      map.off('styledata', registerImages);
    };
  }, [mapReady, mapRef]);

  useEffect(() => {
    const selected = selectedFlightId == null
      ? null
      : flights.find((flight) => flight.flightId === selectedFlightId) ?? null;
    onSelectedFlightVisualChange(selected);
  }, [flights, onSelectedFlightVisualChange, selectedFlightId]);

  useEffect(() => {
    if (typeof window === 'undefined') return;
    const auditWindow = window as Window & {
      __PDDS_ENABLE_AUDIT__?: boolean;
      __PDDS_AUDIT__?: {
        captureFlightVisualState: (backendFlights?: Array<{
          flightId?: number | null;
          flightCode?: string | null;
          currentLatitude?: number | null;
          currentLongitude?: number | null;
        }>) => unknown;
      };
    };

    if (!auditWindow.__PDDS_ENABLE_AUDIT__) {
      return;
    }

    auditWindow.__PDDS_AUDIT__ = {
      captureFlightVisualState: (backendFlights = []) => {
        const map = mapRef.current?.getMap();
        const project = (longitude: number, latitude: number) => {
          if (!map || !Number.isFinite(longitude) || !Number.isFinite(latitude)) {
            return null;
          }
          const point = map.project([longitude, latitude]);
          return { x: point.x, y: point.y };
        };

        const uiFlights = flights.map((row) => {
          const projected = project(row.currentLongitude, row.currentLatitude);
          return {
            flightId: row.flightId,
            flightCode: row.flightCode,
            originLatitude: row.originLatitude,
            originLongitude: row.originLongitude,
            currentLatitude: row.currentLatitude,
            currentLongitude: row.currentLongitude,
            destinationLatitude: row.destinationLatitude,
            destinationLongitude: row.destinationLongitude,
            rotationDeg: computeBearing(
              row.currentLatitude,
              row.currentLongitude,
              row.destinationLatitude,
              row.destinationLongitude,
            ),
            projectedX: projected?.x ?? null,
            projectedY: projected?.y ?? null,
            buttonCenterX: null,
            buttonCenterY: null,
            visible: projected != null,
          };
        });

        return {
          mapReady: Boolean(map),
          viewport: map
            ? {
                zoom: map.getZoom(),
                centerLongitude: map.getCenter().lng,
                centerLatitude: map.getCenter().lat,
              }
            : null,
          uiFlights,
          backendProjectedFlights: Array.isArray(backendFlights)
            ? backendFlights.map((flight) => {
                const projected = project(Number(flight.currentLongitude), Number(flight.currentLatitude));
                return {
                  flightId: typeof flight.flightId === 'number' ? flight.flightId : null,
                  flightCode: typeof flight.flightCode === 'string' ? flight.flightCode : null,
                  projectedX: projected?.x ?? null,
                  projectedY: projected?.y ?? null,
                };
              })
            : [],
        };
      },
    };

    return () => {
      delete auditWindow.__PDDS_AUDIT__;
    };
  }, [flights, mapRef]);

  return (
    <Source id={FLIGHT_SOURCE_ID} type="geojson" data={data}>
      <Layer {...FLIGHT_LAYER_STYLE} />
      <Layer {...FLIGHT_HIT_LAYER_STYLE} />
    </Source>
  );
}

export const FlightMapLayer = memo(FlightMapLayerComponent);
