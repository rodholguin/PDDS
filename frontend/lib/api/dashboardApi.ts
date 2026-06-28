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

  getSystemStatus: (mode?: 'live' | 'sim') =>
    api<SystemStatus>(`/api/dashboard/system-status${mode === 'sim' ? '?mode=sim' : ''}`),

  getOverview: (mode?: 'live' | 'sim') =>
    api<DashboardOverview>(`/api/dashboard/overview${mode === 'sim' ? '?mode=sim' : ''}`),

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

  getMapLive: (limit?: number, mode?: 'live' | 'sim') => {
    const p = new URLSearchParams();
    if (typeof limit === 'number') p.set('limit', String(limit));
    if (mode) p.set('mode', mode);
    const q = p.toString();
    return api<MapLiveShipment[]>(`/api/dashboard/map-live${q ? `?${q}` : ''}`);
  },

  getMapLiveFlights: (limit?: number, mode?: 'live' | 'sim') => {
    const p = new URLSearchParams();
    if (typeof limit === 'number') p.set('limit', String(limit));
    if (mode) p.set('mode', mode);
    const q = p.toString();
    return api<MapLiveFlight[]>(`/api/dashboard/map-live-flights${q ? `?${q}` : ''}`);
  },
};
