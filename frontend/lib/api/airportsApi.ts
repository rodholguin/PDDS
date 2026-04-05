import type { Airport } from '@/lib/types';
import { api } from './client';

export const airportsApi = {
  getAll:         () => api<Airport[]>('/api/airports'),
  getBottlenecks: () => api<Airport[]>('/api/airports/bottlenecks'),
  getByIcao:      (icao: string) =>
                    api<{ airport: Airport; activeFlights: unknown[] }>(
                      `/api/airports/${icao}`
                    ),
};
