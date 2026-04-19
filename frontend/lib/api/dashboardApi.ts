import type {
  DashboardKpis,
  DashboardOverview,
  MapLiveFlight,
  NodeDetail,
  RouteNetworkEdge,
  ShipmentSearchResult,
  MapLiveShipment,
  ShipmentStatus,
  ShipmentSummary,
  SystemStatus,
  Page,
} from '@/lib/types';
import { api } from './client';

export interface ShipmentDashboardFilter {
  airline?: string;
  origin?: string;
  destination?: string;
  status?: ShipmentStatus;
  date?: string;
  page?: number;
  size?: number;
}

export const dashboardApi = {
  getKpis: () => api<DashboardKpis>('/api/dashboard/kpis'),

  getSystemStatus: () => api<SystemStatus>('/api/dashboard/system-status'),

  getOverview: () => api<DashboardOverview>('/api/dashboard/overview'),

  getShipmentSummaries: (filter?: ShipmentDashboardFilter) => {
    const query = new URLSearchParams();
    if (filter?.airline) query.set('airline', filter.airline);
    if (filter?.origin) query.set('origin', filter.origin);
    if (filter?.destination) query.set('destination', filter.destination);
    if (filter?.status) query.set('status', filter.status);
    if (filter?.date) query.set('date', filter.date);
    if (typeof filter?.page === 'number') query.set('page', String(filter.page));
    if (typeof filter?.size === 'number') query.set('size', String(filter.size));
    const suffix = query.toString() ? `?${query.toString()}` : '';
    return api<Page<ShipmentSummary>>(`/api/dashboard/shipments${suffix}`);
  },

  searchShipmentByCode: (code: string) =>
    api<ShipmentSearchResult>(`/api/dashboard/shipments/search?code=${encodeURIComponent(code)}`),

  getNodeDetail: (icao: string, date?: string) =>
    api<NodeDetail>(`/api/dashboard/nodes/${encodeURIComponent(icao)}${date ? `?date=${encodeURIComponent(date)}` : ''}`),

  getRoutesNetwork: () => api<RouteNetworkEdge[]>('/api/dashboard/routes-network'),

  getMapLive: (limit?: number) =>
    api<MapLiveShipment[]>(`/api/dashboard/map-live${typeof limit === 'number' ? `?limit=${encodeURIComponent(String(limit))}` : ''}`),

  getMapLiveFlights: (limit?: number) =>
    api<MapLiveFlight[]>(`/api/dashboard/map-live-flights${typeof limit === 'number' ? `?limit=${encodeURIComponent(String(limit))}` : ''}`),
};
