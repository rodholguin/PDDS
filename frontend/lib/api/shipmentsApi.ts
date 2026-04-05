import type { Shipment, ShipmentDetail, ShipmentFeasibility, ShipmentPlanningEvent, ShipmentStatus } from '@/lib/types';
import { api } from './client';

export interface ShipmentCreate {
  airlineName: string;
  originIcao: string;
  destinationIcao: string;
  luggageCount: number;
  registrationDate?: string;
  algorithmName?: 'Genetic Algorithm' | 'Ant Colony Optimization';
}

export interface ShipmentQuery {
  status?: ShipmentStatus;
  airline?: string;
  origin?: string;
  destination?: string;
  code?: string;
  page?: number;
  size?: number;
}

export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

export const shipmentsApi = {
  list: (query: ShipmentQuery = {}) => {
    const params = new URLSearchParams();
    params.set('page', String(query.page ?? 0));
    params.set('size', String(query.size ?? 20));
    if (query.status) params.set('status', query.status);
    if (query.airline) params.set('airline', query.airline);
    if (query.origin) params.set('origin', query.origin);
    if (query.destination) params.set('destination', query.destination);
    if (query.code) params.set('code', query.code);

    return api<Page<Shipment>>(`/api/shipments?${params.toString()}`);
  },

  getById: (id: number) => api<ShipmentDetail>(`/api/shipments/${id}`),

  create: (body: ShipmentCreate) =>
    api<ShipmentDetail>('/api/shipments', {
      method: 'POST',
      body: JSON.stringify(body),
    }),

  checkFeasibility: (body: ShipmentCreate) =>
    api<ShipmentFeasibility>('/api/shipments/check-feasibility', {
      method: 'POST',
      body: JSON.stringify(body),
    }),

  planningHistory: (id: number) => api<ShipmentPlanningEvent[]>(`/api/shipments/${id}/planning-history`),

  downloadReceipt: (id: number) =>
    import('./client').then(({ download }) => download(`/api/shipments/${id}/receipt`, `receipt-${id}.txt`)),

  replan: (id: number) => api<ShipmentDetail>(`/api/shipments/${id}/replan`, { method: 'PUT' }),

  markDelivered: (id: number) =>
    api<ShipmentDetail>(`/api/shipments/${id}/deliver`, {
      method: 'PUT',
    }),

  overdue: () => api<Shipment[]>('/api/shipments/overdue'),
  critical: () => api<Shipment[]>('/api/shipments/critical'),
};
