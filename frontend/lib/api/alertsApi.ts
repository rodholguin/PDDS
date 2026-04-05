import type { OperationalAlert, ResolveAlertRequest } from '@/lib/types';
import { api } from './client';

export const alertsApi = {
  list: () => api<OperationalAlert[]>('/api/alerts'),

  create: (payload: { shipmentId: number; type: string; note: string }) =>
    api<OperationalAlert>(
      `/api/alerts/create?shipmentId=${encodeURIComponent(String(payload.shipmentId))}&type=${encodeURIComponent(payload.type)}&note=${encodeURIComponent(payload.note)}`,
      { method: 'POST' }
    ),

  resolve: (id: number, body: ResolveAlertRequest) =>
    api<OperationalAlert>(`/api/alerts/${id}/resolve`, {
      method: 'POST',
      body: JSON.stringify(body),
    }),
};
