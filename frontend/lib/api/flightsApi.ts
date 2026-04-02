import type { Flight, FlightStatus } from '@/lib/types';
import { api } from './client';

export const flightsApi = {
  list:   (status?: FlightStatus, date?: string) => {
    const q = new URLSearchParams();
    if (status) q.set('status', status);
    if (date)   q.set('date', date);
    return api<Flight[]>(`/api/flights?${q}`);
  },
  getById: (id: number) =>
    api<{ flight: Flight; assignedShipments: unknown[]; loadPct: number }>(`/api/flights/${id}`),
  cancel:  (id: number) =>
    api<unknown>(`/api/flights/${id}/cancel`, { method: 'PUT' }),
};
