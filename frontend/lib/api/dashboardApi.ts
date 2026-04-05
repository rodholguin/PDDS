import type {
  DashboardKpis,
  DashboardOverview,
  NodeDetail,
  RouteNetworkEdge,
  ShipmentSearchResult,
  ShipmentStatus,
  ShipmentSummary,
  SystemStatus,
} from '@/lib/types';
import { api } from './client';

export interface ShipmentDashboardFilter {
  airline?: string;
  origin?: string;
  destination?: string;
  status?: ShipmentStatus;
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
    const suffix = query.toString() ? `?${query.toString()}` : '';
    return api<ShipmentSummary[]>(`/api/dashboard/shipments${suffix}`);
  },

  searchShipmentByCode: (code: string) =>
    api<ShipmentSearchResult>(`/api/dashboard/shipments/search?code=${encodeURIComponent(code)}`),

  getNodeDetail: (icao: string, date?: string) =>
    api<NodeDetail>(`/api/dashboard/nodes/${encodeURIComponent(icao)}${date ? `?date=${encodeURIComponent(date)}` : ''}`),

  getRoutesNetwork: () => api<RouteNetworkEdge[]>('/api/dashboard/routes-network'),
};
