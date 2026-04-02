import type { Shipment, ShipmentDetail, ShipmentStatus } from '@/lib/types';
import { api } from './client';

export interface ShipmentCreate {
  airlineName:      string;
  originIcao:       string;
  destinationIcao:  string;
  luggageCount:     number;
  registrationDate?: string;
  algorithmName?:   string;
}

export interface Page<T> {
  content:          T[];
  totalElements:    number;
  totalPages:       number;
  number:           number;
  size:             number;
}

export const shipmentsApi = {
  list:     (status?: ShipmentStatus, page = 0, size = 20) => {
    const q = new URLSearchParams({ page: String(page), size: String(size) });
    if (status) q.set('status', status);
    return api<Page<Shipment>>(`/api/shipments?${q}`);
  },
  getById:  (id: number) => api<ShipmentDetail>(`/api/shipments/${id}`),
  create:   (body: ShipmentCreate) =>
              api<ShipmentDetail>('/api/shipments', {
                method: 'POST', body: JSON.stringify(body),
              }),
  replan:   (id: number) =>
              api<ShipmentDetail>(`/api/shipments/${id}/replan`, { method: 'PUT' }),
  overdue:  () => api<Shipment[]>('/api/shipments/overdue'),
  critical: () => api<Shipment[]>('/api/shipments/critical'),
};
