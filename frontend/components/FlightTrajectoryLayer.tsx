'use client';

import { memo, useMemo } from 'react';
import { Layer, Source } from 'react-map-gl/maplibre';
import type { FeatureCollection, LineString } from 'geojson';
import type { MapLiveFlight } from '@/lib/types';

const SELECTED_FLOWN_SOURCE = 'trajectory-selected-flown';
const SELECTED_REMAINING_SOURCE = 'trajectory-selected-remaining';
const AIRPORT_INBOUND_FLOWN_SOURCE = 'trajectory-airport-inbound-flown';
const AIRPORT_INBOUND_REMAINING_SOURCE = 'trajectory-airport-inbound-remaining';
const ALL_REMAINING_SOURCE = 'trajectory-all-remaining';

const EMPTY_FC: FeatureCollection<LineString> = { type: 'FeatureCollection', features: [] };

// C31/C33/C34: tramo origen-destino de CADA vuelo activo. Dibujamos la porción
// restante (posición actual → destino), que se acorta sola y desaparece al llegar
// — equivalente a "la línea se borra luego de ser recorrida".
function buildAllSegmentsFC(flights: MapLiveFlight[]): FeatureCollection<LineString> {
  return {
    type: 'FeatureCollection',
    features: flights.map((f) => ({
      type: 'Feature' as const,
      geometry: {
        type: 'LineString' as const,
        coordinates: [
          [f.currentLongitude, f.currentLatitude],
          [f.destinationLongitude, f.destinationLatitude],
        ],
      },
      properties: {},
    })),
  };
}

// Straight lat/lon segments — matches the linear interpolation the simulation
// uses to move planes, so lines pass exactly through the plane icon.

function buildSelectedFlownFC(flight: MapLiveFlight | null): FeatureCollection<LineString> {
  if (!flight) return EMPTY_FC;
  return {
    type: 'FeatureCollection',
    features: [{
      type: 'Feature',
      geometry: {
        type: 'LineString',
        coordinates: [
          [flight.originLongitude, flight.originLatitude],
          [flight.currentLongitude, flight.currentLatitude],
        ],
      },
      properties: {},
    }],
  };
}

function buildSelectedRemainingFC(flight: MapLiveFlight | null): FeatureCollection<LineString> {
  if (!flight) return EMPTY_FC;
  return {
    type: 'FeatureCollection',
    features: [{
      type: 'Feature',
      geometry: {
        type: 'LineString',
        coordinates: [
          [flight.currentLongitude, flight.currentLatitude],
          [flight.destinationLongitude, flight.destinationLatitude],
        ],
      },
      properties: {},
    }],
  };
}

function buildAirportInboundFlownFC(icao: string | null, flights: MapLiveFlight[]): FeatureCollection<LineString> {
  if (!icao) return EMPTY_FC;
  return {
    type: 'FeatureCollection',
    features: flights
      .filter((f) => f.destinationIcao === icao)
      .map((f) => ({
        type: 'Feature' as const,
        geometry: {
          type: 'LineString' as const,
          coordinates: [
            [f.originLongitude, f.originLatitude],
            [f.currentLongitude, f.currentLatitude],
          ],
        },
        properties: {},
      })),
  };
}

function buildAirportInboundRemainingFC(icao: string | null, flights: MapLiveFlight[]): FeatureCollection<LineString> {
  if (!icao) return EMPTY_FC;
  return {
    type: 'FeatureCollection',
    features: flights
      .filter((f) => f.destinationIcao === icao)
      .map((f) => ({
        type: 'Feature' as const,
        geometry: {
          type: 'LineString' as const,
          coordinates: [
            [f.currentLongitude, f.currentLatitude],
            [f.destinationLongitude, f.destinationLatitude],
          ],
        },
        properties: {},
      })),
  };
}

type Props = {
  selectedFlight: MapLiveFlight | null;
  selectedAirportIcao: string | null;
  flights: MapLiveFlight[];
};

function FlightTrajectoryLayerComponent({ selectedFlight, selectedAirportIcao, flights }: Props) {
  const selectedFlownFC = useMemo(() => buildSelectedFlownFC(selectedFlight), [selectedFlight]);
  const selectedRemainingFC = useMemo(() => buildSelectedRemainingFC(selectedFlight), [selectedFlight]);

  const airportFlownFC = useMemo(
    () => buildAirportInboundFlownFC(selectedAirportIcao, flights),
    [selectedAirportIcao, flights],
  );
  const airportRemainingFC = useMemo(
    () => buildAirportInboundRemainingFC(selectedAirportIcao, flights),
    [selectedAirportIcao, flights],
  );
  const allSegmentsFC = useMemo(() => buildAllSegmentsFC(flights), [flights]);

  return (
    <>
      {/* Tramo pendiente de cada vuelo activo (C34): línea punteada tenue, debajo de todo. */}
      <Source id={ALL_REMAINING_SOURCE} type="geojson" data={allSegmentsFC}>
        <Layer
          id="trajectory-all-remaining-line"
          type="line"
          source={ALL_REMAINING_SOURCE}
          layout={{ 'line-cap': 'round', 'line-join': 'round' }}
          paint={{
            'line-color': '#5b6b8c',
            'line-width': 1.4,
            'line-opacity': 0.35,
            'line-dasharray': [2, 4],
          }}
        />
      </Source>

      {/* Airport selected: flown legs (origin → current), dashed blue */}
      <Source id={AIRPORT_INBOUND_FLOWN_SOURCE} type="geojson" data={airportFlownFC}>
        <Layer
          id="trajectory-airport-inbound-flown"
          type="line"
          source={AIRPORT_INBOUND_FLOWN_SOURCE}
          layout={{ 'line-cap': 'round', 'line-join': 'round' }}
          paint={{
            'line-color': '#38bdf8',
            'line-width': 1.8,
            'line-opacity': 0.75,
            'line-dasharray': [3, 3],
          }}
        />
      </Source>

      {/* Airport selected: remaining legs (current → destination), solid cyan */}
      <Source id={AIRPORT_INBOUND_REMAINING_SOURCE} type="geojson" data={airportRemainingFC}>
        <Layer
          id="trajectory-airport-inbound-remaining"
          type="line"
          source={AIRPORT_INBOUND_REMAINING_SOURCE}
          layout={{ 'line-cap': 'round', 'line-join': 'round' }}
          paint={{
            'line-color': '#22d3ee',
            'line-width': 2.2,
            'line-opacity': 0.9,
          }}
        />
      </Source>

      {/* Selected flight: flown portion — gray dashed (origin → current) */}
      <Source id={SELECTED_FLOWN_SOURCE} type="geojson" data={selectedFlownFC}>
        <Layer
          id="trajectory-flight-flown"
          type="line"
          source={SELECTED_FLOWN_SOURCE}
          layout={{ 'line-cap': 'round', 'line-join': 'round' }}
          paint={{
            'line-color': '#cbd5e1',
            'line-width': 2,
            'line-opacity': 0.7,
            'line-dasharray': [3, 4],
          }}
        />
      </Source>

      {/* Selected flight: remaining portion — orange solid (current → destination) */}
      <Source id={SELECTED_REMAINING_SOURCE} type="geojson" data={selectedRemainingFC}>
        <Layer
          id="trajectory-flight-remaining"
          type="line"
          source={SELECTED_REMAINING_SOURCE}
          layout={{ 'line-cap': 'round', 'line-join': 'round' }}
          paint={{
            'line-color': '#f97316',
            'line-width': 2.5,
            'line-opacity': 0.95,
          }}
        />
      </Source>
    </>
  );
}

export const FlightTrajectoryLayer = memo(FlightTrajectoryLayerComponent);
