import type { Flight, FlightCapacityView, FlightDetailResponse, FlightSearchPage, FlightStatus } from '@/lib/types';
import { api } from './client';

export const flightsApi = {
  list:   (status?: FlightStatus, date?: string) => {
    const q = new URLSearchParams();
    if (status) q.set('status', status);
    if (date)   q.set('date', date);
    return api<Flight[]>(`/api/flights?${q}`);
  },
  getById: (id: number) =>
    api<FlightDetailResponse>(`/api/flights/${id}`),
  cancel:  (id: number) =>
    api<unknown>(`/api/flights/${id}/cancel`, { method: 'PUT' }),

  capacityView: (date?: string) =>
    api<FlightCapacityView[]>(`/api/flights/capacity-view${date ? `?date=${encodeURIComponent(date)}` : ''}`),

  search: (params: {
    status?: FlightStatus;
    date?: string;
    code?: string;
    origin?: string;
    destination?: string;
    page?: number;
    size?: number;
    sort?: string;
    direction?: 'asc' | 'desc';
  }) => {
    const q = new URLSearchParams();
    if (params.status) q.set('status', params.status);
    if (params.date) q.set('date', params.date);
    if (params.code) q.set('code', params.code);
    if (params.origin) q.set('origin', params.origin);
    if (params.destination) q.set('destination', params.destination);
    q.set('page', String(params.page ?? 0));
    q.set('size', String(params.size ?? 50));
    q.set('sort', params.sort ?? 'scheduledDeparture');
    q.set('direction', params.direction ?? 'asc');
    return api<FlightSearchPage>(`/api/flights/search?${q.toString()}`);
  },
};
