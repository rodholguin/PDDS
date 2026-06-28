# PLAN DE IMPLEMENTACIÓN DETALLADO — Alineación con rúbrica `c1inf54.983.Eq3C.AUTO.Eval.Sw`

Fecha: 2026-06-21
Versión: v2 (exhaustiva, por cada ítem)

---

## Convenciones

- **Almacén** = aeropuerto/nodo
- **UT** = unidad de transporte = vuelo/avión
- **Producto** = maleta
- **Envío** = grupo de maletas
- **Tramo** = vuelo origen-destino de una UT
- **Ruta** = ruta completa de un envío (puede tener múltiples tramos)
- **Colapso** = primer envío que no llega a su deadline

---

## PARTE 1: CÓDIGO A ELIMINAR

### 1.1 Servicios Backend completos

| # | Archivo | Líneas | ¿Por qué? | Dependencias |
|---|---------|--------|-----------|-------------|
| 1 | `service/DemandGenerationService.java` | 250 | Generación sintética de envíos. El proyecto SOLO usa datos reales del dataset. AGENTS.md: "quedan como beans inertes (sin invocarse)". | Depende de `ShipmentOrchestratorService`, `AirportRepository`, `FlightRepository`. Tests: ningún test lo mockea directamente. |
| 2 | `service/FutureDemandProjectionService.java` | 609 | Proyección de demanda a 2030. Eliminada del bootstrap (AGENTS.md). | Depende de `ShipmentRepository`, `AirportRepository`, `SimulationConfigRepository`, `FlightScheduleService`. Test: `SimulationControllerSpeedTest` y `SeedStatisticalTest` lo tienen como `@MockBean`. |
| 3 | `service/BenchmarkTuningService.java` | 1026 | Benchmark GA vs ACO con datos sintéticos. Puede resetear datos oficiales (`OFFICIAL_POOL_SIZE`, `generateDemandFromScenarioRows`). No es requisito de rúbrica. | Depende de 10+ servicios. Peligroso (borra y recrea shipments). |
| 4 | `service/BenchmarkJobService.java` | 159 | Orquestador async de benchmark. Solo llama a BenchmarkTuningService. | Solo depende de BenchmarkTuningService. |
| 5 | `service/AlgorithmProfileService.java` | 54 | Configura parámetros de GA/ACO/SA. Solo es llamado por `BenchmarkTuningService`. | Depende de GA, ACO, SA. |
| 6 | `service/AlgorithmRaceService.java` | 93 | Reporte de carrera GA vs ACO. Datos placeholders. Endpoint `GET /race-report` casi no se usa. | Depende de `RoutePlannerService`, `ShipmentRepository`. |

### 1.2 DTOs Backend a eliminar

| # | Archivo | Líneas | Asociado a |
|---|---------|--------|-----------|
| 7 | `dto/DemandGenerationRequestDto.java` | 13 | DemandGenerationService |
| 8 | `dto/DemandGenerationResultDto.java` | 13 | DemandGenerationService |
| 9 | `dto/FutureDemandGenerationRequestDto.java` | 20 | FutureDemandProjectionService |
| 10 | `dto/FutureDemandGenerationResultDto.java` | 19 | FutureDemandProjectionService |
| 11 | `dto/BenchmarkMetricsDto.java` | 15 | AlgorithmRaceService |
| 12 | `dto/AlgorithmRaceReportDto.java` | 14 | AlgorithmRaceService |
| 13 | `repository/projection/FutureRouteBaselineRow.java` | 13 | FutureDemandProjectionService |

### 1.3 Endpoints Backend a eliminar o simplificar

| # | Endpoint | Controller | Acción |
|---|----------|-----------|--------|
| 14 | `POST /api/simulation/seed-statistical` | SimulationController | ELIMINAR. No expuesto en UI. No es requisito de rúbrica. |
| 15 | `GET /api/simulation/initial-volume-samples` | SimulationController | ELIMINAR. Debug, no se usa. |
| 16 | `GET /api/simulation/race-report` | SimulationController | ELIMINAR. Placeholder, no es requisito. |
| 17 | `POST /api/import/benchmark/start` | DataImportController | ELIMINAR. Asociado a benchmark. |
| 18 | `GET /api/import/benchmark/status/{jobId}` | DataImportController | ELIMINAR. Asociado a benchmark. |
| 19 | `GET /api/import/benchmark/status` | DataImportController | ELIMINAR. Asociado a benchmark. |
| 20 | `GET /api/import/template/shipments-scenarios` | DataImportController | ELIMINAR. Asociado a benchmark. |
| 21 | `POST /api/simulation/reset-demand` | SimulationController | MANTENER pero marcar como peligroso. No exponer en UI normal. |

### 1.4 Tests a modificar

| # | Archivo | Acción |
|---|---------|--------|
| 22 | `SimulationControllerSpeedTest.java` | Quitar `@MockBean FutureDemandProjectionService` y `DemandGenerationService` cuando se eliminen esos servicios. Ajustar expects. |
| 23 | `SimulationControllerSeedStatisticalTest.java` | ELIMINAR todo el test (prueba endpoint `seed-statistical` que también eliminamos). |

### 1.5 Frontend a eliminar

| # | Archivo | Líneas | Razón |
|---|---------|--------|-------|
| 24 | `components/FlightMapLayer.tsx` | 263 | Capa MapLibre con sprites SVG. Reemplazado por marcadores `<PlaneIcon>` inline en `OperationsDashboard.tsx`. No se importa en ningún archivo activo. VERIFICAR: grep "FlightMapLayer" en todo el proyecto. Si no hay imports, eliminar. |
| 25 | `lib/api/importApi.ts`: métodos `startBenchmarkJob`, `getBenchmarkJobStatus`, `getLatestBenchmarkStatus`, `downloadScenarioDemandTemplate` | ~15 líneas | Endpoints de benchmark eliminados del backend. También `downloadScenarioDemandTemplate`. |
| 26 | `lib/types/index.ts`: interfaces `BenchmarkJobState`, `DemandGenerationResult`, `FutureDemandGenerationResult`, `FlightCapacityView` | ~40 líneas | DTOs eliminados del backend. |

### 1.6 Campos en SimulationConfig a marcar como obsoletos (no eliminar de la DB por compatibilidad)

| Campo | Razón |
|-------|-------|
| `projectedDemandReady` | Proyección eliminada |
| `projectedHistoricalFrom/To` | Proyección eliminada |
| `projectedFrom/To` | Proyección eliminada |
| `projectedGeneratedAt` | Proyección eliminada |
| `initialVolumeAvg/Variance` | Modelo sintético eliminado |
| `flightFrequencyMultiplier` | Modelo sintético eliminado |
| `intraNodeCapacity` / `interNodeCapacity` | No se usan para planificación (AGENTS.md) |
| `executionMinutes` | No usado |

### 1.7 Código a corregir

| # | Archivo | Línea(s) | Problema | Solución |
|---|---------|----------|----------|----------|
| 27 | `SimulationController.java` | ~143 | `config.setPrimaryAlgorithm(AlgorithmType.GENETIC)` y `setSecondaryAlgorithm(GENETIC)` — fuerza ambos algoritmos ignorando el DTO. | Cambiar a: si `dto.primaryAlgorithm()` != null, usarlo; sino mantener default. O directamente: config.setPrimaryAlgorithm(dto.primaryAlgorithm() != null ? dto.primaryAlgorithm() : config.getPrimaryAlgorithm()); |
| 28 | `CollapseMonitorService.java` | varios | Comentarios que hablan de colapso por "saturación de almacenes". | Alinear documentación: colapso = primer envío que no llega a tiempo. |
| 29 | `Airport.java` | `getStatus()` | Comentarios sobre thresholds. | Alinear. |
| 30 | `FlightMapLayer.tsx` o imports en proyecto | grep | Verificar si algún componente importa FlightMapLayer. | Si no hay imports, eliminar archivo. |

---

## PARTE 2: PLAN POR CADA ÍTEM DE RÚBRICA

Para cada ítem aplicable (Aplica=Sí en Excel), se detalla:
- **Estado actual** (✅ implementado, ⚠️ parcial, ❌ no implementado)
- **Qué implementar** (descripción técnica precisa)

---

### SECCIÓN A — GENERAL (17 requeridos)

#### A01 — Versión del software
- **Estado**: ❌
- **Qué implementar**: 
  - Frontend: Agregar un footer o badge en el layout (`layout.tsx`) con la versión del build.
  - Si existe `package.json` version, usarla. Sino hardcodear "v1.0.0" como placeholder.
  - Ubicación: sidebar-footer o esquina inferior del mapa.
  - Archivo: `frontend/components/Sidebar.tsx` o `frontend/app/layout.tsx`

#### A02 — Fecha de subida/presentación
- **Estado**: ❌
- **Qué implementar**: 
  - Documentar en PDF/lámina externa. No es código.
  - Opcionalmente agregar en el footer junto a A01.

#### A03 — Comentarios o problemas reportados
- **Estado**: ❌
- **Qué implementar**: 
  - Documentación de sustentación. No es código.

#### A10 — Nombre del algoritmo planificador
- **Estado**: ✅ 
- **Detalle**: Backend tiene `GeneticAlgorithm`, `AntColonyOptimization`, `SimulatedAnnealing`. GA es el operativo.
- **Qué implementar**: 
  - Mostrar en UI cuál es el algoritmo activo (ej: en el panel de configuración).
  - Archivo: `frontend/components/OperationsDashboard.tsx` — agregar texto "Planificador: Algoritmo Genético (GA)" en la sección de configuración.
  - Backend: Endpoint `GET /simulation/state` ya devuelve `primaryAlgorithm` en `SimulationStateDto`.

#### A11 — Nivel de avance del planificador (%)
- **Estado**: ❌
- **Qué implementar**: 
  - Documentación de sustentación. Estimar % basado en funcionalidad implementada (ruteo multi-tramo, capacidad, dead­line, 3 algoritmos → ~90%).

#### A12 — Estudiantes asignados al planificador
- **Estado**: ❌
- **Qué implementar**: Documentación.

#### A13 — Nombre/tecnologías del visualizador
- **Estado**: ✅
- **Detalle**: MapLibre + react-map-gl + Next.js + React 19 + TypeScript.
- **Qué implementar**: Documentación.

#### A14 — Nivel de avance del visualizador
- **Estado**: ❌
- **Qué implementar**: Documentación (~85%).

#### A15 — Estudiantes asignados al visualizador
- **Estado**: ❌
- **Qué implementar**: Documentación.

#### A16 — Evidencia revisión video
- **Estado**: ❌
- **Qué implementar**: Documentación.

#### A17 — Ta (tiempo ejecución algoritmo)
- **Estado**: ❌
- **Qué implementar**: 
  - Capturar en backend el tiempo que toma `RoutePlannerService.planRoute()`.
  - Exponer en `SimulationStateDto` como campo nuevo `lastPlanningDurationMs`.
  - Mostrar en UI como métrica.
  - Archivos: `RoutePlannerService.java`, `SimulationStateDto.java`, `OperationsDashboard.tsx`

#### A18 — Sa (tiempo salto algoritmo)
- **Estado**: ❌
- **Qué implementar**: 
  - Sa = tiempo simulado que avanza por cada tick de planificación.
  - Ya existe en backend: `simulationSecondsPerTick` en `SimulationStateDto`.
  - Mostrar en UI: el label `formatTimeScaleLabel` ya lo muestra. Solo falta etiquetarlo como "Sa".
  - Archivo: `OperationsDashboard.tsx` línea ~1079.

#### A19 — Sc (salto eje consumo)
- **Estado**: ❌
- **Qué implementar**: 
  - Sc = tiempo simulado entre cada "consumo" de datos (tick del engine).
  - Es el mismo valor que Sa en el diseño actual, pero conceptualmente Sc es el intervalo de procesamiento de datos.
  - Mostrar en UI como "Sc".
  - Archivo: `OperationsDashboard.tsx`.

#### A20 — Estado despliegue frontend
- **Estado**: ✅
- **Detalle**: VM desplegada en `https://1inf54-984-3c.inf.pucp.edu.pe:3000`.
- **Qué implementar**: Documentación.

#### A21 — Estado despliegue backend
- **Estado**: ✅
- **Detalle**: VM desplegada en `https://1inf54-984-3c.inf.pucp.edu.pe:8081`.
- **Qué implementar**: Documentación.

#### A22 — Diagrama Sc/Sa
- **Estado**: ❌
- **Qué implementar**: Crear diagrama de bloques (herramienta externa: draw.io, PowerPoint). Documentación.

#### A23 — Diagrama multi-navegador
- **Estado**: ❌
- **Qué implementar**: Crear diagrama mostrando N navegadores → mismo backend REST → polling independiente. Documentación.

---

### SECCIÓN B — CONFIGURACIÓN (8 requeridos)

#### B01 — ¿Cómo se configura el mapa?
- **Estado**: ⚠️
- **Qué implementar**: 
  - Agregar texto informativo en el panel de configuración (OperationsDashboard.tsx):
    "El mapa base usa MapLibre con datos cartográficos gratuitos. Use los botones Mapa/Satelital para cambiar el estilo. Use los controles +/− para zoom. Los aeropuertos se cargan desde el archivo oficial de datos."
  - Archivo: `frontend/components/OperationsDashboard.tsx`, sección panel izquierdo.

#### B03 — Mantenimiento aeropuertos (ubicación, capacidad)
- **Estado**: ✅
- **Detalle**: Se cargan desde `datos/c.1inf54.26.1.v1.Aeropuerto.husos...` via `/api/import/airports`. También se pueden consultar via `GET /api/airports`.
- **Qué implementar**: Nada (ya funciona).

#### B04 — Mantenimiento UT/vuelos (ubicación, capacidad)
- **Estado**: ✅
- **Detalle**: Se cargan desde `datos/planes_vuelo.txt` via `/api/import/flights`.
- **Qué implementar**: Nada.

#### B11 — Mantenimiento tramos (origen, destino, horarios)
- **Estado**: ✅
- **Detalle**: Los vuelos ya contienen origen, destino, salida, llegada. Se importan desde planes_vuelo.txt.
- **Qué implementar**: Nada.

#### B13 — Explicar carga de datos
- **Estado**: ✅
- **Detalle**: Página /import explica TODO el proceso. Tiene carga de aeropuertos, vuelos, envíos (muestra y full async).
- **Qué implementar**: Nada.

#### B14 — Fechas/horas para pruebas
- **Estado**: ✅
- **Detalle**: Input `datetime-local` en panel de configuración `OperationsDashboard.tsx` línea ~951.
- **Qué implementar**: Nada.

#### B15 — Datos proporcionados sin reducción
- **Estado**: ✅
- **Detalle**: Modo full import importa los 9,519,995 envíos completos.
- **Qué implementar**: Nada.

#### B16 — Carga independiente de escenarios
- **Estado**: ✅
- **Detalle**: Importación es independiente de la simulación. La simulación solo usa datos ya importados.
- **Qué implementar**: Nada.

---

### SECCIÓN C — MAPA BASE (31 requeridos)

#### C01 — Carga de datos en otra opción
- **Estado**: ✅
- **Detalle**: Página /import separada de / y /simulaciones.
- **Qué implementar**: Nada.

#### C02 — Pide fecha/hora a nivel minuto
- **Estado**: ✅
- **Detalle**: Input `datetime-local` con `step=60` en OperationsDashboard.tsx.
- **Qué implementar**: Nada.

#### C03 — Tiempo en mostrar mapa tras iniciar
- **Estado**: ❌
- **Qué implementar**: Medir y documentar en sustentación. Agregar métrica opcional en UI.

#### C04 — Tiempo hasta que transporte se mueva
- **Estado**: ❌
- **Qué implementar**: Medir y documentar. Agregar métrica opcional.

#### C07 — Todo español o todo inglés (sin mezcla)
- **Estado**: ❌
- **Qué implementar**: Revisar TODOS los textos en el frontend y pasarlos a español. Lista de cambios:
  - `OperationsDashboard.tsx`: 
    - `liveOnly` → prop name se queda (código), pero textos visibles: "Operación día a día", "Monitoreo EN VIVO"
    - Tooltip "Maximizar mapa" / "Restaurar paneles" → ya están en español
    - Placeholder de fecha input OK
    - Títulos: "Escenario y parámetros", "Indicadores" OK
    - Labels: "Umbral normal %", "Umbral alerta %", "SLA verde ≥ %" OK
    - Botones "Iniciar", "Pausar", "Detener", "Reanudar" OK
    - Reloj: "● EN VIVO · PERÚ (GMT-5)" y "◷ RELOJ SIMULADO" OK
  - `simulaciones/page.tsx`: Comentario "corren EN PARALELO" → OK
  - `flights/page.tsx`: 
    - Fechas formato `es-PE` OK
    - Estados: "Programado", "En vuelo", "Completado", "Cancelado" OK
  - `shipments/page.tsx`: "En ruta", "Pendiente", "Crítico", "Atrasado", "Entregado" OK
  - `import/page.tsx`: Todo en español OK
  - Sidebar: "Principal", "Inicio", "Registro", "Envíos", "Importar", "Operaciones", "Simulaciones", "Vuelos", "Reportes" OK
  - `reports/page.tsx`: Revisar textos en inglés
  - **Archivos a revisar**: `OperationsDashboard.tsx`, `reports/page.tsx` (tiene "SLA", "INTRA", "INTER"), `globals.css` (nombres de clases OK porque son técnicos)

#### C08 — Zoom in/out adecuado
- **Estado**: ✅
- **Detalle**: `NavigationControl` de MapLibre.
- **Qué implementar**: Nada.

#### C09 — Pantalla principal completa al iniciar
- **Estado**: ✅
- **Detalle**: Mapa + sidebar + configuración todo visible.
- **Qué implementar**: Nada.

#### C10 — Todos los aeropuertos simultáneamente
- **Estado**: ✅
- **Detalle**: 30 aeropuertos renderizados en el mapa.
- **Qué implementar**: Nada.

#### C11 — Pantalla limpia (elementos no disponibles ocultos/deshabilitados)
- **Estado**: ⚠️
- **Detalle**: Los paneles son colapsables. Botones deshabilitados cuando la simulación no está activa. 
- **Qué implementar**: En modo `liveOnly` (página de inicio /), el panel de configuración debería estar completamente oculto (no visible). Actualmente `liveOnly` oculta el panel via `display: none` en el grid, pero revisar que sea correcto.

#### C12 — Fecha-hora simulada a nivel minuto
- **Estado**: ⚠️
- **Detalle**: `formatSimTime` muestra `DD/MM/AAAA HH:mm:ss` en el reloj simulado.
- **Qué implementar**: El formato debe estar en `DD/MM/AAAA HH:mm` (minuto, no segundo). Ajustar `formatSimTime` para que muestre hasta minuto. O mantener segundos (la rúbrica dice "a nivel de minuto" pero no prohíbe segundos). Dejar como está (con segundos) es mejor.

#### C13 — Tiempo transcurrido del momento simulado
- **Estado**: ❌
- **Qué implementar**: Agregar contador "Tiempo simulado transcurrido" en el reloj del mapa.
  - Calcular: `simulatedNowMs - scenarioStartMs` 
  - Mostrar formato: `Xd Xh Xm` o `DD:HH:mm`
  - Archivo: `frontend/components/OperationsDashboard.tsx`, junto al reloj simulado actual.
  - Ubicación: dentro del recuadro del reloj, como segunda línea.

#### C14 — Fecha-hora actual/presente
- **Estado**: ✅
- **Detalle**: `peruClock` muestra hora actual de Perú.
- **Qué implementar**: Nada.

#### C15 — Tiempo transcurrido hasta el momento actual
- **Estado**: ❌
- **Qué implementar**: Agregar contador "Tiempo real transcurrido".
  - Calcular: `Date.now() - sessionStartTime` (cuando se inició la simulación).
  - Mostrar formato: `Xd Xh Xm`.
  - Archivo: `OperationsDashboard.tsx`, abajo del reloj real.
  - NOTA: Solo cuando simulación está activa.

#### C16 — Datos de tiempo bien ubicados, tamaño y contraste
- **Estado**: ⚠️
- **Detalle**: Reloj centrado arriba del mapa, tamaño 19px, fondo semitransparente. Contraste adecuado.
- **Qué implementar**: Asegurar que los 4 tiempos (C12, C13, C14, C15) estén visibles. Actualmente solo C12 y C14 están. Agregar C13 y C15 como se describió arriba. Revisar diseño para que no saturen el espacio.

#### C17 — Aeropuertos en ubicación prevista
- **Estado**: ✅
- **Detalle**: Coordenadas del dataset oficial.
- **Qué implementar**: Nada.

#### C18 — Ícono de aeropuerto tamaño adecuado
- **Estado**: ✅
- **Detalle**: 10px dot.
- **Qué implementar**: Nada. Considerar si 10px es muy pequeño en pantallas grandes (C18 dice "idóneo"). Quizás subir a 12px.

#### C19 — Ícono representa correctamente un aeropuerto
- **Estado**: ⚠️
- **Detalle**: Es un punto circular genérico. La rúbrica dice "el ícono del almacén es idóneo (o sea representa un aeropuerto)". Un punto no es específico de aeropuerto.
- **Qué implementar**: Cambiar el marcador de aeropuerto a un SVG con forma de aeropuerto (ej: ícono de terminal) o al menos un cuadrado con una "A" dentro, o un marcador con forma de señalización aeroportuaria. Alternativa simple: cambiar el círculo por un SVG en forma de marca de ubicación (pin) con el código ICAO. Archivo: `OperationsDashboard.tsx` línea ~408.

#### C20 — Ícono contrasta con el mapa
- **Estado**: ✅
- **Detalle**: Colores brillantes (verde/#22c55e, ámbar/#f59e0b, rojo/#ef4444) sobre fondo oscuro del mapa.
- **Qué implementar**: Nada.

#### C21 — Semáforo+vacío según stock
- **Estado**: ✅
- **Detalle**: Verde (<70%), ámbar (70-90%), rojo (>90%) según `AirportStatus`.
- **Qué implementar**: Nada.

#### C22 — Ocupación del aeropuerto en número o %
- **Estado**: ✅
- **Detalle**: Popup del aeropuerto muestra `occupancyPct.toFixed(1)%`.
- **Qué implementar**: Nada. Opcional: mostrar siempre el % cerca del marcador (no solo en popup).

#### C23 — UT en ubicación prevista
- **Estado**: ✅
- **Detalle**: Posición interpolada con `requestAnimationFrame`.
- **Qué implementar**: Nada.

#### C24 — Ícono UT tamaño adecuado
- **Estado**: ✅
- **Detalle**: 24x24 SVG.
- **Qué implementar**: Nada.

#### C25 — Ícono representa un avión
- **Estado**: ✅
- **Detalle**: SVG `PlaneIcon` con forma de avión.
- **Qué implementar**: Nada.

#### C26 — Ícono UT contrasta
- **Estado**: ✅
- **Detalle**: Colores brillantes, borde oscuro.
- **Qué implementar**: Nada.

#### C27 — Semáforo UT según ocupación
- **Estado**: ✅
- **Detalle**: `PlaneIcon` fill: verde (<70% loadPct), ámbar (70-90%), rojo (>90%). Incluye "vacío" (0% = verde).
- **Qué implementar**: Nada.

#### C28 — Ocupación UT en número o %
- **Estado**: ✅
- **Detalle**: Popup del vuelo muestra `loadPct.toFixed(1)%`.
- **Qué implementar**: Nada.

#### C29 — UT se desplaza con fluidez
- **Estado**: ✅
- **Detalle**: `requestAnimationFrame` + interpolación Mercator.
- **Qué implementar**: Nada.

#### C30 — UT alineada al movimiento
- **Estado**: ✅
- **Detalle**: `computeBearing` rota el SVG del avión según dirección.
- **Qué implementar**: Nada.

#### C31 — Tramo origen-destino como línea al inicio
- **Estado**: ✅
- **Detalle**: `FlightTrajectoryLayer` dibuja líneas de todos los vuelos activos.
- **Qué implementar**: Nada.

#### C33 — Grosor/ideografía adecuada
- **Estado**: ✅
- **Detalle**: Líneas de 1-2px con opacidad 0.3-0.6.
- **Qué implementar**: Nada.

#### C34 — Línea se borra/cambia tras ser recorrida
- **Estado**: ⚠️
- **Detalle**: `FlightTrajectoryLayer` dibuja tramos pendientes (sólidos) y completados (punteados/tenues). La línea del tramo actual se muestra en un color diferente.
- **Qué implementar**: Mejorar visibilidad: tramo completado = línea gris tenue, tramo en curso = línea sólida brillante, tramo pendiente = línea punteada. Archivo: `FlightTrajectoryLayer.tsx`.

---

### SECCIÓN D — CIRCUNSTANCIAS (2 requeridos)

#### D14 — Cancelación visible en mapa
- **Estado**: ❌
- **Qué implementar**: 
  - Backend: Agregar un campo en `MapLiveFlightDto` o un endpoint `/api/dashboard/cancelled-flights` que devuelva vuelos cancelados recientes con su hora de cancelación.
  - Frontend: Renderizar marcadores especiales para vuelos cancelados en el mapa. Usar color gris/rojo con un símbolo de cancelación (X). Mostrar popup con código, ruta, hora de cancelación y envíos afectados.
  - Archivos backend: `DashboardController.java`, `MapLiveFlightDto.java`. Archivos frontend: `OperationsDashboard.tsx`, tipos.
  - Detalle: Los vuelos cancelados deben permanecer visibles por un tiempo razonable (ej: 1 hora simulada después de la cancelación).

#### D15 — Cancelación visible el tiempo previsto
- **Estado**: ❌
- **Qué implementar**: 
  - Backend: Al cancelar un vuelo (`FlightController.cancel`), registrar `cancelledAt`. El feed de mapa debe incluir cancelaciones dentro de una ventana de tiempo (ej: última hora simulada).
  - Frontend: Mostrar el marcador de cancelación con un timer de desaparición.
  - Archivos: `FlightController.java`, `Flight.java`, `OperationsDashboard.tsx`.

---

### SECCIÓN E — PANEL (16 requeridos)

#### E01 — Paneles contraídos/colapsados al inicio
- **Estado**: ✅
- **Detalle**: `panelCollapsed` y `kpisCollapsed` se persisten en localStorage.
- **Qué implementar**: Nada.

#### E02 — Lista UT con ocupación
- **Estado**: ✅
- **Detalle**: Página `/flights` con tabla de vuelos, columnas de carga/ocupación.
- **Qué implementar**: Nada.

#### E03 — Desde UT, acceso a envíos que traslada
- **Estado**: ✅
- **Detalle**: Detalle de vuelo (`/flights?selected=X`) muestra `assignedShipments`.
- **Qué implementar**: Nada.

#### E04 — Desde UT, acceso a maletas que traslada
- **Estado**: ✅
- **Detalle**: Mismo que E03 (maletas = envíos con `luggageCount`).
- **Qué implementar**: Nada.

#### E05 — Stock actual UT como semáforo-vacío
- **Estado**: ✅
- **Detalle**: `loadPct` coloreado en la tabla de vuelos.
- **Qué implementar**: Nada.

#### E06 — Buscar UT por código/tramo
- **Estado**: ✅
- **Detalle**: Input de búsqueda "Código" en `/flights`.
- **Qué implementar**: Nada.

#### E07 — Buscar UT por origen
- **Estado**: ✅
- **Detalle**: Filtro "Origen" en `/flights`.
- **Qué implementar**: Nada.

#### E08 — Buscar UT por destino
- **Estado**: ✅
- **Detalle**: Filtro "Destino" en `/flights`.
- **Qué implementar**: Nada.

#### E12 — Ordenar UT por ocupación
- **Estado**: ✅
- **Detalle**: Backend `FlightRepository` acepta `sort=loadPct`. Frontend envía sort param.
- **Qué implementar**: Nada.

#### E17 — Lista de aeropuertos con ocupación
- **Estado**: ❌
- **Qué implementar**: 
  - Agregar un panel de aeropuertos en el dashboard principal (OperationsDashboard.tsx), en la columna izquierda (debajo del panel de configuración, o como pestaña).
  - Mostrar tabla: código ICAO, ciudad, país, ocupación (número y %), semáforo.
  - Backend: ya existe `GET /api/airports` con todos los datos.
  - Ordenable por ocupación (E27).
  - Archivo: `OperationsDashboard.tsx`. Tipo: `Airport[]` ya está disponible en `useSimulation()`.

#### E18 — Acceso a envíos en aeropuerto
- **Estado**: ❌
- **Qué implementar**: 
  - Al hacer clic en un aeropuerto de la lista (E17), expandir detalle con:
    - Envíos almacenados (storedShipments)
    - Envíos en tránsito (inbound/outbound)
    - Envíos con destino final ese aeropuerto
  - Backend: `GET /api/dashboard/nodes/{icao}` devuelve `NodeDetail` con `storedShipments`, `inboundShipments`, `outboundShipments`.
  - Frontend: usar `dashboardApi.getNodeDetail(icao)` al seleccionar.

#### E20 — Lista aeropuertos con semáforo-vacío
- **Estado**: ❌
- **Qué implementar**: 
  - En la lista de aeropuertos (E17), cada fila debe tener un indicador de color (verde/ámbar/rojo) según su `status` (NORMAL/ALERTA/CRITICO).
  - Mismo componente que C21 pero en formato lista.

#### E21 — Info planificada de envíos que entran
- **Estado**: ❌
- **Qué implementar**: 
  - Backend: Si `NodeDetailDto` no tiene la información de llegadas planificadas con suficiente detalle, agregar un endpoint `GET /api/dashboard/nodes/{icao}/inbound-planned` que devuelva envíos programados para llegar.
  - Frontend: Mostrar en el detalle expandido del aeropuerto: "Próximas llegadas: X envíos (Y maletas)".
  - Alternativa: Usar `NodeDetail.nextFlights` que ya tiene los próximos vuelos. Calcular maletas entrantes de los vuelos.

#### E23 — Info planificada de envíos que salen
- **Estado**: ❌
- **Qué implementar**: 
  - Similar a E21 pero para salidas: mostrar próximos vuelos salientes con cantidad de envíos/maletas.
  - Backend: `NodeDetail.nextFlights` ya tiene los vuelos programados desde el aeropuerto.

#### E27 — Ordenar aeropuertos por ocupación
- **Estado**: ❌
- **Qué implementar**: 
  - En la lista de E17, permitir ordenar por `occupancyPct` ascendente/descendente.
  - Frontend: estado de orden y función `sort()` en el componente.
  - Archivo: `OperationsDashboard.tsx`.

#### E30 — Lista planificada de envíos a transportar
- **Estado**: ❌
- **Qué implementar**: 
  - Backend: Crear endpoint `GET /api/shipments/to-transport?date=X&page=X&size=X` que devuelva envíos PENDING planificados para un día, con: código, destino, vuelo asignado, cantidad de maletas, estado.
  - Frontend: Agregar sección en el dashboard o página de envíos con esta tabla.
  - Archivos backend: `ShipmentController.java`, `ShipmentRepository.java`. Frontend: `OperationsDashboard.tsx` o nuevo componente.

---

### SECCIÓN F — VINCULACIÓN MAPA-PANEL (9 requeridos)

#### F01 — Ruta de maleta por ID en mapa
- **Estado**: ⚠️
- **Detalle**: Envíos se renderizan como marcadores en el mapa (implementado en OperationsDashboard.tsx líneas 463-494). Pero NO hay línea de ruta.
- **Qué implementar**: 
  - Cuando se selecciona un envío en el mapa (click) o desde el panel, dibujar una polilínea mostrando la ruta completa del envío.
  - Usar `ShipmentDetail.stops` para obtener la secuencia de puntos (cada stop tiene `airportLatitude/Longitude`).
  - Dibujar con MapLibre GeoJSON source + layer.
  - Archivos: `OperationsDashboard.tsx`, nuevo componente `ShipmentRouteLayer.tsx`.
  - Color: según estado del envío (verde=entregado, azul=en ruta, rojo=crítico).
  - Grosor: 2px, opacidad 0.7.

#### F03 — Ruta de envío por ID en mapa
- **Estado**: ⚠️
- **Detalle**: Misma implementación que F01.
- **Qué implementar**: Mismo que F01.

#### F05 — Seleccionar aeropuerto en panel → enfocar en mapa
- **Estado**: ❌
- **Qué implementar**: 
  - Cuando el usuario haga clic en un aeropuerto en la lista del panel (E17), el mapa debe:
    1. `flyTo` la ubicación del aeropuerto (zoom ~6)
    2. Resaltar el marcador (agrandar o cambiar color)
    3. Mostrar popup con info
  - Usar `mapRef.current.flyTo({ center: [lng, lat], zoom: 6 })`.
  - Estado global: `selectedAirportIcao` en `SimulationContext` o estado local en OperationsDashboard.
  - Archivo: `OperationsDashboard.tsx`.

#### F06 — Seleccionar aeropuerto en mapa → enfocar en panel
- **Estado**: ❌
- **Qué implementar**: 
  - Cuando el usuario hace clic en un aeropuerto en el mapa (ya hay `onOpenNode`):
    1. Desplazar la lista de aeropuertos del panel para que el aeropuerto seleccionado esté visible
    2. Resaltar la fila (clase `is-selected`)
    3. Mostrar detalle expandido (E18)
  - Usar `ref` en la lista + `scrollIntoView`.
  - Archivo: `OperationsDashboard.tsx`.

#### F07 — Seleccionar UT en panel → enfocar en mapa
- **Estado**: ❌
- **Qué implementar**: 
  - Desde la página `/flights`, agregar botón "Ver en mapa" en el detalle del vuelo.
  - Al hacer clic: navegar a `/` (inicio) o `/simulaciones` con query param `?flight=X`.
  - El dashboard debe leer `?flight=X` y hacer `flyTo` + resaltar.
  - Alternativa: usar estado global compartido.
  - Archivos: `flights/page.tsx`, `OperationsDashboard.tsx`.

#### F08 — Seleccionar UT en mapa → enfocar en panel
- **Estado**: ❌
- **Qué implementar**: 
  - Cuando el usuario hace clic en un vuelo en el mapa:
    1. Abrir detalle del vuelo (popup existe)
    2. En el popup, el link "Abrir vuelo" ya navega a `/flights?selected=X`
    3. Adicional, resaltar el vuelo en alguna lista de UT si existe en el panel principal.
  - Archivo: `OperationsDashboard.tsx` (popup ya tiene link).

#### F09 — Seleccionar envío en panel → enfocar en mapa
- **Estado**: ❌
- **Qué implementar**: 
  - **PASO 1 (crítico)**: Hacer funcionar `/shipments?selected=<id>`.
    - Agregar `useSearchParams` en `frontend/app/shipments/page.tsx`.
    - Leer `selected` de URL, buscar el envío, abrir detalle.
    - Archivo: `shipments/page.tsx`.
  - **PASO 2**: Agregar botón "Ver en mapa" en el detalle del envío.
    - Navegar a `/` o `/simulaciones` con `?shipment=X`.
  - **PASO 3**: Dashboard debe leer `?shipment=X` y hacer `flyTo` + resaltar.
    - Archivo: `OperationsDashboard.tsx`.

#### F15 — Filtrar por semáforo de aeropuertos → reflejar en mapa
- **Estado**: ❌
- **Qué implementar**: 
  - Agregar en el panel izquierdo checkboxes o chips para filtrar: "Normal", "Alerta", "Crítico".
  - Al seleccionar un filtro, los aeropuertos en el mapa que no coincidan se ocultan o atenúan.
  - Backend: endpoint `GET /api/airports?status=ALERTA` ya existe.
  - Frontend: estado de filtro `airportStatusFilter: ('ALL' | 'NORMAL' | 'ALERTA' | 'CRITICO')`. Filtro aplicado a `airports` antes de renderizar.
  - Archivo: `OperationsDashboard.tsx`.

#### F16 — Filtrar por semáforo de UT → reflejar en mapa
- **Estado**: ❌
- **Qué implementar**: 
  - Similar a F15 pero para UT/vuelos.
  - Filtro por rango de `loadPct`: Vacío (0%), Bajo (<70%), Medio (70-90%), Alto (>90%).
  - Archivo: `OperationsDashboard.tsx`.

---

### SECCIÓN G — CIERRE (16 requeridos)

#### G01 — Indicador global de ocupación de flota
- **Estado**: ❌
- **Qué implementar**: 
  - Backend: Endpoint o campo en `DashboardOverviewDto` con `avgFlightOccupancyPct` (promedio de `loadPct` de todos los vuelos activos).
  - Frontend: KPI grande en el panel de indicadores con el promedio general.
  - Archivos: `DashboardController.java`, `DashboardOverviewDto.java`, `OperationsDashboard.tsx`.

#### G02 — Indicador flota como semáforo
- **Estado**: ❌
- **Qué implementar**: 
  - El indicador debe tener un círculo de color:
    - Verde: promedio < 70%
    - Ámbar: 70-90%
    - Rojo: > 90%
  - Misma lógica que `semaforoSla` pero para ocupación de flota.
  - Archivo: `OperationsDashboard.tsx`.

#### G03 — Indicador global de ocupación de aeropuertos
- **Estado**: ❌
- **Qué implementar**: 
  - Backend: Agregar `avgNodeOccupancyPct` en `DashboardOverviewDto` (promedio de `occupancyPct` de todos los aeropuertos).
  - Frontend: KPI similar a G01.
  - Archivos: `DashboardController.java`, `DashboardOverviewDto.java`, `OperationsDashboard.tsx`.

#### G04 — Indicador aeropuertos como semáforo
- **Estado**: ❌
- **Qué implementar**: Misma lógica que G02 pero para ocupación de aeropuertos.

#### G05 — Tiempo mínimo de permanencia
- **Estado**: ✅
- **Detalle**: `RoutePlanningSupport.MIN_CONNECTION_MINUTES = 30`. Las escalas requieren al menos 30 minutos entre llegada y próxima salida.
- **Qué implementar**: Nada.

#### G06 — 2+ visualizadores/navegadores
- **Estado**: ✅
- **Detalle**: Arquitectura REST + polling independiente. Múltiples pestañas/navegadores funcionan simultáneamente.
- **Qué implementar**: Documentación y diagrama (A23).

#### G08 — Reporte última planificación estable → fin PERIOD_SIMULATION
- **Estado**: ❌
- **Qué implementar**: 
  - Backend: 
    - Mejorar `GET /api/simulation/results` para que devuelva datos reales (no placeholders).
    - Incluir: escenario, inicio, fin, envíos planificados/entregados/tarde/críticos, ocupación promedio, replanificaciones, causa de colapso si aplica.
    - Persistir `SimulationResultsDto` al finalizar la simulación.
  - Frontend:
    - Nueva sección en `/reports` o en el dashboard post-simulación.
    - Mostrar resumen completo al detener la simulación.
    - Botón de export CSV/PDF (ya existe `simulationApi.exportResults`).
  - Archivos: `SimulationController.java`, `SimulationExportService.java`, `reports/page.tsx`.

#### G09 — Reporte última planificación estable → cierre DAY_TO_DAY
- **Estado**: ❌
- **Qué implementar**: Similar a G08 pero para modo DAY_TO_DAY. Reporte al "cerrar operaciones" (al hacer stop o fin del día).

#### G10 — Reporte última planificación estable → fin COLLAPSE_TEST
- **Estado**: ❌
- **Qué implementar**: Similar a G08 pero para COLLAPSE_TEST. Debe mostrar:
  - Envío que causó el colapso (código, ruta, deadline)
  - Tiempo de supervivencia
  - Estado del sistema al momento del colapso
  - Datos ya existen: `state.collapseShipmentCode`, `state.collapseSurvivalSeconds`, `state.collapseDetectedAt`.

#### G11-G14, G17-G18 — Percepción global
- **Estado**: ❌
- **Qué implementar**: Documentación de sustentación. No es código.

#### G19 — Potencial invitación XpoSTEM
- **Estado**: ❌
- **Qué implementar**: Documentación.

---

## PARTE 3: REORGANIZACIÓN DE PANTALLAS

### Estructura actual (con cambios marcados)

```
/ (Inicio)              → OperationsDashboard liveOnly    ✅ MANTENER
/simulaciones           → OperationsDashboard             ✅ MANTENER (ahí se configura escenario)
/flights                → Tabla + detalle de vuelos       ✅ MANTENER
/shipments              → Tabla + detalle de envíos       ✅ MANTENER (agregar ?selected)
/import                 → Importación de datos            ✅ MANTENER
/reports                → Reportes SLA                    ⚠️ MODIFICAR: agregar reporte de cierre (G08-G10)
/registro               → Creación live de envíos         ⚠️ EVALUAR: útil para DAY_TO_DAY pero no es requisito de rúbrica. MANTENER.
```

### Nuevos componentes a crear

| Componente | Archivo | Propósito |
|-----------|---------|-----------|
| `AirportPanel.tsx` | `components/AirportPanel.tsx` | Lista de aeropuertos con ocupación, semáforo, ordenamiento (E17, E20, E27) |
| `ShipmentRouteLayer.tsx` | `components/ShipmentRouteLayer.tsx` | Polilínea de ruta de envío en el mapa (F01, F03) |
| `CancelledFlightsLayer.tsx` | `components/CancelledFlightsLayer.tsx` | Marcadores de vuelos cancelados (D14, D15) |
| `GlobalIndicators.tsx` | `components/GlobalIndicators.tsx` | KPIs globales de ocupación (G01-G04) |
| `SimulationClosureReport.tsx` | `components/SimulationClosureReport.tsx` | Reporte de cierre post-simulación (G08-G10) |

### Modificaciones mayores a componentes existentes

| Componente | Cambios |
|-----------|---------|
| `OperationsDashboard.tsx` | Agregar: AirportPanel, ShipmentRouteLayer, CancelledFlightsLayer, GlobalIndicators, filtros F15-F16, vinculación F05-F09, contadores C13/C15 |
| `Sidebar.tsx` | Sin cambios (navegación completa) |
| `shipments/page.tsx` | Agregar useSearchParams para ?selected= |
| `flights/page.tsx` | Agregar botón "Ver en mapa" en detalle (F07) |
| `reports/page.tsx` | Agregar sección de cierre de simulación (G08-G10) |

---

## PARTE 4: PLAN DE IMPLEMENTACIÓN FASEADO

### Fase 1 — LIMPIEZA de código muerto
1. Eliminar servicios backend (ítems 1-6)
2. Eliminar DTOs (ítems 7-12) y proyección (ítem 13)
3. Eliminar endpoints (ítems 14-21)
4. Eliminar tests obsoletos (ítems 22-23)
5. Eliminar FlightMapLayer.tsx (ítem 24)
6. Eliminar funciones de API frontend (ítem 25)
7. Eliminar tipos frontend (ítem 26)
8. Corregir forzado de algoritmos (ítem 27)
9. Alinear comentarios contradictorios (ítems 28-29)

### Fase 2 — REQUISITOS DE EVIDENCIA (A)
1. A01: Agregar versión en layout
2. A17-A19: Capturar y mostrar Ta, Sa, Sc en UI
3. A22-A23: Crear diagramas para documentación

### Fase 3 — MAPA BASE (C)
1. C07: Estandarizar textos a español (revisar todas las páginas)
2. C13: Agregar contador elapsed simulado
3. C15: Agregar contador elapsed real
4. C16: Ajustar layout de tiempos
5. C19: Mejorar ícono de aeropuerto
6. C34: Mejorar cambio de línea de tramo

### Fase 4 — PANEL DE AEROPUERTOS (E)
1. E17, E20, E27: Lista de aeropuertos con semáforo y orden
2. E18: Acceso a envíos en aeropuerto
3. E21, E23: Entradas/salidas planificadas
4. E30: Lista planificada de envíos a transportar

### Fase 5 — VINCULACIÓN MAPA-PANEL (F)
1. F09: Hacer funcionar `/shipments?selected=X`
2. F01/F03: Polilínea de ruta de envío en mapa
3. F05/F06: Vincular selección aeropuerto panel↔mapa
4. F07/F08: Vincular selección vuelo panel↔mapa
5. F15/F16: Filtros semáforo en panel → mapa

### Fase 6 — CIRCUNSTANCIAS (D)
1. D14/D15: Marcadores de vuelos cancelados en mapa

### Fase 7 — CIERRE (G)
1. G01-G04: Indicadores globales de ocupación
2. G08-G10: Reporte de última planificación estable

### Fase 8 — OPCIONALES Y PULIDO
1. Opcionales E (E09-E16, E19, E22, E24-E26, E28-E29, E31-E34)
2. Mejoras visuales
3. Tests
