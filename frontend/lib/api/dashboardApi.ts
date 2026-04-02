import type { DashboardKpis, SystemStatus } from '@/lib/types';
import { api } from './client';

export const dashboardApi = {
  getKpis:        () => api<DashboardKpis>('/api/dashboard/kpis'),
  getSystemStatus: () => api<SystemStatus>('/api/dashboard/system-status'),
};
