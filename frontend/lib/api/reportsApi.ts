import type { AlgorithmRaceReport, SlaReport } from '@/lib/types';
import { api } from './client';

export const reportsApi = {
  slaCompliance: (query?: { from?: string; to?: string }) => {
    const params = new URLSearchParams();
    if (query?.from) params.set('from', query.from);
    if (query?.to) params.set('to', query.to);
    const suffix = params.toString();
    return api<SlaReport>(`/api/reports/sla-compliance${suffix ? `?${suffix}` : ''}`);
  },

  algorithmRace: (query?: { from?: string; to?: string; scenario?: string }) => {
    const params = new URLSearchParams();
    if (query?.from) params.set('from', query.from);
    if (query?.to) params.set('to', query.to);
    if (query?.scenario) params.set('scenario', query.scenario);
    const suffix = params.toString();
    return api<AlgorithmRaceReport>(`/api/simulation/race-report${suffix ? `?${suffix}` : ''}`);
  },
};
