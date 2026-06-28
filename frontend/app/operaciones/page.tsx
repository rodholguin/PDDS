'use client';

import { OperationsDashboard } from '@/components/OperationsDashboard';

// Mapa de operaciones dia a dia: vista de logistica en vivo, separada del data entry (/registro).
// Usa el provider live del layout y no muestra controles de simulacion.
export default function OperacionesPage() {
  return <OperationsDashboard liveOnly />;
}
