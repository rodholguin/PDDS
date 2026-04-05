import type {
  AlgorithmRaceReport,
  CollapseRisk,
  SimScenario,
  SimulationResults,
  SimulationState,
  AlgorithmType,
} from '@/lib/types';
import { api, download } from './client';

export interface SimConfigUpdate {
  scenario?: SimScenario;
  simulationDays?: number;
  executionMinutes?: number;
  initialVolumeAvg?: number;
  initialVolumeVariance?: number;
  flightFrequencyMultiplier?: number;
  cancellationRatePct?: number;
  intraNodeCapacity?: number;
  interNodeCapacity?: number;
  normalThresholdPct?: number;
  warningThresholdPct?: number;
  primaryAlgorithm?: AlgorithmType;
  secondaryAlgorithm?: AlgorithmType;
}

export interface SimulationEventPayload {
  type: 'CANCEL_FLIGHT' | 'INCREASE_VOLUME' | 'FLAG_SHIPMENT_CRITICAL' | string;
  flightId?: number;
  shipmentId?: number;
  eventValue?: number;
  note?: string;
}

export const simulationApi = {
  getState: () => api<SimulationState>('/api/simulation/state'),

  configure: (body: SimConfigUpdate) =>
    api<SimulationState>('/api/simulation/configure', {
      method: 'POST',
      body: JSON.stringify(body),
    }),

  start: () => api<{ message: string; state: SimulationState }>('/api/simulation/start', { method: 'POST' }),

  stop: () => api<{ message: string; state: SimulationState }>('/api/simulation/stop', { method: 'POST' }),

  pause: () => api<{ message: string; state: SimulationState }>('/api/simulation/pause', { method: 'POST' }),

  setSpeed: (speed: number) =>
    api<{ message: string; speed: number; state: SimulationState }>('/api/simulation/speed', {
      method: 'POST',
      body: JSON.stringify({ speed }),
    }),

  injectEvent: (payload: SimulationEventPayload) =>
    api<{ message: string; state: SimulationState }>('/api/simulation/events', {
      method: 'POST',
      body: JSON.stringify(payload),
    }),

  deliverShipment: (shipmentId: number) =>
    api<unknown>(`/api/simulation/deliver/${shipmentId}`, { method: 'POST' }),

  getCollapseRisk: () => api<CollapseRisk>('/api/simulation/collapse-risk'),

  getResults: () => api<SimulationResults>('/api/simulation/results'),

  getRaceReport: (query?: { from?: string; to?: string; scenario?: string }) => {
    const params = new URLSearchParams();
    if (query?.from) params.set('from', query.from);
    if (query?.to) params.set('to', query.to);
    if (query?.scenario) params.set('scenario', query.scenario);
    const suffix = params.toString();
    return api<AlgorithmRaceReport>(`/api/simulation/race-report${suffix ? `?${suffix}` : ''}`);
  },

  seedStatistical: (avg: number, variance: number) =>
    api<{ message: string; avg: number; variance: number; created: number; state: SimulationState }>(
      `/api/simulation/seed-statistical?avg=${encodeURIComponent(String(avg))}&variance=${encodeURIComponent(String(variance))}`,
      { method: 'POST' }
    ),

  exportResults: (format: 'csv' | 'pdf') =>
    download(`/api/simulation/results/export?format=${encodeURIComponent(format)}`, `simulation-results.${format}`),
};
