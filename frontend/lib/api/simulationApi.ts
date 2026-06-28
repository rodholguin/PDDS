import type {
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
  scenarioStartDate?: string;
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
  getState: (mode?: 'live' | 'sim') =>
    api<SimulationState>(`/api/simulation/state${mode === 'sim' ? '?mode=sim' : ''}`),

  /** Estado liviano de la SIMULACIÓN (id=2), que corre en paralelo a la operación viva. */
  simState: () =>
    api<{
      exists: boolean;
      scenario?: SimScenario;
      running?: boolean;
      simulatedNow?: string;
      scenarioStartAt?: string;
      simulationDays?: number;
      collapseDetectedAtSim?: string;
      collapseShipmentCode?: string;
    }>('/api/simulation/sim-state'),

  configure: (body: SimConfigUpdate) =>
    api<SimulationState>('/api/simulation/configure', {
      method: 'POST',
      body: JSON.stringify(body),
    }),

  start: () => api<{ message: string; state: SimulationState }>('/api/simulation/start', { method: 'POST' }),

  stop: () => api<{ message: string; state: SimulationState }>('/api/simulation/stop', { method: 'POST' }),

  resetDemand: () => api<{ message: string; state: SimulationState }>('/api/simulation/reset-demand', { method: 'POST' }),

  resetToInitial: () => api<{ message: string; state: SimulationState }>('/api/simulation/reset-to-initial', { method: 'POST' }),

  pause: () => api<{ message: string; state: SimulationState }>('/api/simulation/pause', { method: 'POST' }),

  resume: () => api<{ message: string; state: SimulationState }>('/api/simulation/resume', { method: 'POST' }),

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

  exportResults: (format: 'csv' | 'pdf') =>
    download(`/api/simulation/results/export?format=${encodeURIComponent(format)}`, `simulation-results.${format}`),
};
