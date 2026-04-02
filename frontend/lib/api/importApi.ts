import type { DataImportLog } from '@/lib/types';
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
};
