import type { SlaReport } from '@/lib/types';
import { api } from './client';

export const reportsApi = {
  slaCompliance: (query?: { from?: string; to?: string }) => {
    const params = new URLSearchParams();
    if (query?.from) params.set('from', query.from);
    if (query?.to) params.set('to', query.to);
    const suffix = params.toString();
    return api<SlaReport>(`/api/reports/sla-compliance${suffix ? `?${suffix}` : ''}`);
  },
};
