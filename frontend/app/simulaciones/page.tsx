'use client';

import { SimulationProvider } from '@/lib/SimulationContext';
import { OperationsDashboard } from '@/components/OperationsDashboard';

// Simulaciones = el dashboard completo (escenarios + controles + mapa), apuntando al runtime de
// SIMULACIÓN (id=2) — que corre EN PARALELO a la operación día a día sin detenerla. Se envuelve en su
// propio provider en modo "sim" para que el mapa, KPIs y reloj reflejen la simulación (no la operación viva).
export default function SimulacionesPage() {
  return (
    <SimulationProvider mode="sim">
      <OperationsDashboard />
    </SimulationProvider>
  );
}
