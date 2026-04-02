import type { CollapseRisk, OptimizationResult, SimulationConfig } from '@/lib/types';
import { api } from './client';

export interface SimConfigUpdate {
  scenario?:            string;
  simulationDays?:      number;
  executionMinutes?:    number;
  normalThresholdPct?:  number;
  warningThresholdPct?: number;
  primaryAlgorithm?:    string;
  secondaryAlgorithm?:  string;
}

export const simulationApi = {
  getState:       () => api<SimulationConfig>('/api/simulation/state'),
  configure:      (body: SimConfigUpdate) =>
                    api<SimulationConfig>('/api/simulation/configure', {
                      method: 'POST', body: JSON.stringify(body),
                    }),
  start:          () => api<unknown>('/api/simulation/start',  { method: 'POST' }),
  stop:           () => api<unknown>('/api/simulation/stop',   { method: 'POST' }),
  pause:          () => api<unknown>('/api/simulation/pause',  { method: 'POST' }),
  getCollapseRisk: () => api<CollapseRisk>('/api/simulation/collapse-risk'),
  getResults:     () => api<Record<string, OptimizationResult>>('/api/simulation/results'),
};
