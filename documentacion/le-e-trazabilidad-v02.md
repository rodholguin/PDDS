# Trazabilidad LE exigibles (v02)

Fuente: `documentacion/03.lista.exigencias.v02.xlsx`

## Alcance

- Solo LE tipo `E`.
- Benchmark y comparativa GA/ACO/SA se mantienen internos (backend/scripts), sin UI operativa.
- Operacion por defecto con perfil ganador `GA-P1`.

## Resumen de LE(E)

- Monitoreo: `LE-001..LE-028`
- Gestion de envios: `LE-029..LE-044`
- Simulacion: `LE-049..LE-057`
- Reportes: `LE-074`

## Mapeo funcional (backend/frontend)

### Monitoreo (`LE-001..LE-028`)

- Backend: `DashboardController`, `ShipmentController`, `AirportController`, `FlightController`, `OperationalAlertController`.
- Frontend: `frontend/app/page.tsx` (mapa, rutas, panel KPI, alertas, filtros, detalle envio/nodo).
- Estado esperado: mantener.

### Gestion de envios (`LE-029..LE-044`)

- Backend: `ShipmentController`, `ShipmentOrchestratorService`, `RoutePlannerService`, `ShipmentCodeService`.
- Frontend: `frontend/app/shipments/page.tsx`.
- Estado esperado: mantener.

### Simulacion (`LE-049..LE-057`)

- Backend: `SimulationController`, `SimulationRuntimeService`, `SimulationEngineService`, `SimulationExportService`, `DemandGenerationService`.
- Frontend: `frontend/app/simulation/page.tsx`.
- Estado esperado: mantener.

### Reportes (`LE-074`)

- Backend: `ReportsController` (`/api/reports/sla-compliance`), `ReportingService`.
- Frontend: `frontend/app/reports/page.tsx`.
- Estado esperado: mantener solo reporte SLA por tipo de ruta, cliente y nodo destino.

## Limpieza aplicada por criterio LE(E)

- UI de benchmark/race removida de pantallas operativas.
- Se preserva benchmark interno por API/scripts para sustentacion academica.
- Se fija default operativo a ganador (`GA-P1`) sin eliminar implementaciones ACO/SA del backend.
