import type { BenchmarkJobState, DataImportLog, DatasetImportResult, DatasetStatus, DemandGenerationResult, EnviosImportJobState, EnviosImportResult } from '@/lib/types';
import { download, upload, api } from './client';

export type ImportType = 'shipments' | 'airports' | 'flights';

export const importApi = {
  importFile: (file: File, type: ImportType) => {
    const form = new FormData();
    form.append('file', file);
    return upload<DataImportLog>(`/api/import/${type}`, form);
  },

  downloadTemplate: (type: ImportType) =>
    download(`/api/import/template/${type}`, `template-${type}.xlsx`),

  getLogs: () => api<DataImportLog[]>('/api/import/logs'),

  importDefaultDataset: () =>
    api<DatasetImportResult>('/api/import/dataset/default', { method: 'POST' }),

  getDatasetStatus: () =>
    api<DatasetStatus>('/api/import/dataset-status'),

  downloadScenarioDemandTemplate: () =>
    download('/api/import/template/shipments-scenarios', 'demand-scenarios.csv'),

  startBenchmarkJob: () =>
    api<{ message: string; jobId: string }>('/api/import/benchmark/start', { method: 'POST' }),

  getBenchmarkJobStatus: (jobId: string) =>
    api<BenchmarkJobState>(`/api/import/benchmark/status/${encodeURIComponent(jobId)}`),

  getLatestBenchmarkStatus: () =>
    api<BenchmarkJobState | { status: 'IDLE'; message: string }>('/api/import/benchmark/status'),

  generateDemand: (payload: {
    scenario: string;
    size: number;
    seed: number;
    startHour: number;
    algorithmName?: string;
  }) =>
    api<{ message: string; result: DemandGenerationResult }>('/api/import/demand/generate', {
      method: 'POST',
      body: JSON.stringify(payload),
    }),

  importEnviosDataset: (payload?: {
    seed?: number;
    maxAirports?: number;
    maxPerAirport?: number;
    includeOrigins?: string[];
    fullDataset?: boolean;
    algorithmName?: string;
  }) =>
    api<{
      message: string;
      result: EnviosImportResult;
    }>('/api/import/shipments/dataset', {
      method: 'POST',
      body: JSON.stringify(payload ?? {}),
    }),

  importEnviosDatasetFull: () =>
    api<{
      message: string;
      result: EnviosImportResult;
    }>('/api/import/shipments/dataset/full', {
      method: 'POST',
    }),

  startEnviosDatasetFullJob: () =>
    api<{ message: string; jobId: string }>('/api/import/shipments/dataset/full/start', {
      method: 'POST',
    }),

  getEnviosDatasetFullJobStatus: (jobId: string) =>
    api<EnviosImportJobState>(`/api/import/shipments/dataset/full/status/${encodeURIComponent(jobId)}`),

  getLatestEnviosDatasetFullJobStatus: () =>
    api<EnviosImportJobState | { status: 'IDLE'; message: string }>('/api/import/shipments/dataset/full/status'),
};
