# PDDS - Tasf.B2B

## Stack
- Backend: Spring Boot 3.4.4, Java 21, puerto 8080
- Frontend: Next.js (ver frontend/AGENTS.md para reglas específicas)
- BD: PostgreSQL 15
- Infraestructura: Docker Compose (postgres + backend + frontend; reconstruir backend:
  `docker compose up -d --build backend`; requiere `.env` con POSTGRES_DB/USER/PASSWORD=tasfb2b)

## Contexto del proyecto
Sistema de simulación logística de traslado de maletas entre aeropuertos
(América, Asia, Europa) para el curso 1INF54 PUCP 2026-1.

## Estado actual
- La simulación trabaja SOLO con envíos importados de `datos/envios` (sin demanda futura/sintética).
  Se eliminó la proyección automática a 2030 del bootstrap y el bloqueo `projectedDemandReady`.
  Importación manual desde la página Importar (muestra o dataset completo async).
- Escenario COLLAPSE_TEST: el "colapso" = el primer envío que no llega a tiempo (deadline vencido
  sin entrega). Al detectarlo se registra (`collapse_detected_at_sim` / `collapse_shipment_code`)
  y la simulación se DETIENE. Inicio por defecto = primer envío importado.
- Umbrales del semáforo de KPIs de riesgo (SLA, en-riesgo, nodos críticos) configurables desde el
  front y persistidos en `SimulationConfig`; los KPIs de volumen son informativos.
- Dataset del profesor: archivos `_envios_XXXX_.txt` van en `datos/envios/` (no en repo).
- Importar: POST `/api/import/shipments/dataset` (muestra) o `/dataset/full` (completo async).

## Hallazgos de auditoría y arreglos (colapso)
- [FIX] El import de envíos ya NO re-fecha: conserva la fecha real del archivo (2026-01 → 2029-01),
  muestrea distribuido en todo el rango (`sampleLinesAcrossRange`) e importa solo-almacenamiento (sin
  planificar; la simulación planifica por adelantado, ver plan-ahead abajo).
- Capacidades FIJAS del dataset (NO se tocan): vuelos `ORIG-DEST-HD:MD-HA:MA-CAPACIDAD` y nodos con
  columna CAPACIDAD. El auto-override de capacidad de nodos fue REMOVIDO de configure/start.
- El ruteo solo respeta la capacidad de VUELOS (fija; ~16× holgura sobre la demanda total). El almacén de
  NODOS es COSMÉTICO para el colapso por deadline: el motor lo topa (`min(maxCap,…)`) pero no bloquea
  entregas y el planner no lo consulta → "subir capacidad de nodos" NO retrasa el colapso. El colapso =
  primer envío cuyo mejor ruteo no cumple su deadline (o par sin ruta = CRITICAL). La palanca legítima para
  prolongarlo es el RUTEO (encontrar conexiones a tiempo), no las capacidades.
- [FIX] HUSOS HORARIOS (UTC): el archivo de aeropuertos trae columna GMT (`Airport.gmtOffset`). Las horas de
  vuelos/envíos del archivo son LOCALES; el import las convierte a UTC (`utc = local - gmtOffset*60`). Sin
  esto, p.ej. `LBSF-LATI-11:55-11:21` se inflaba a 23h26m (tránsito falso) → colapso espurio día 1.
- [FIX] RUTEO MULTI-TRAMO (causa real del "colapso" temprano): el colapso de jun-2026 (envío 005179151
  SUAA→SKBO) NO era límite de la red — era un BUG del planner. Dos defectos encadenados, ambos arreglados:
  (1) `RoutePlanningSupport.planningCandidatesFromIndex/FromEligible` llenaba el cupo de candidatos PRIMERO
  con salidas del origen; en orígenes de alto grado (cientos de salidas/día) agotaba el presupuesto y las
  2das piernas (hub→destino) y el directo NUNCA entraban → el planner solo veía salidas del origen y elegía
  un directo TARDÍO aunque existiera conexión a tiempo. Fix: CUPO RESERVADO por rol (llegadas al destino
  primero, luego origen, luego hubs; `CANDIDATE_DESTINATION_QUOTA/ORIGIN_QUOTA/TOTAL`). (2) El one-hop del
  modo fast (`prepareBootstrapPlanningContext(flightIndex)`) se alimentaba de `eligiblePlanningFlightsFromIndex`
  (SOLO vuelos del origen) → nunca veía 2das piernas. Fix: `connectionIndexByOrigin` (une elegibles+candidatos)
  + si el candidato barato MISSEA el deadline, `FastRoutePlanning.planBestEffort` enumera multi-tramo. Resultado:
  005179151 ahora rutea SUAA→SABE→SKBO y entrega ~17h ANTES del deadline; el colapso de junio desapareció.
- Demanda real total = 9,519,995 envíos (2026-01-01 → 2029-01-06, UTC), CRECIENTE: 2026≈989k, 2027≈3.04M,
  2028≈5.40M (se quintuplica). DATASET COMPLETO CARGADO en BD (~3 GB), íntegro (0 duplicados, 0 sin deadline).
- [FIX] Import FULL real (no muestra): `fullDataset=true` ahora importa TODAS las líneas (se quitó el tope de
  50k/aeropuerto). Inserción por lotes (`FULL_DATASET_BATCH_SIZE`) + `@Transactional(NOT_SUPPORTED)` → no
  acumula persistencia. REANUDABLE (resume): `countGroupedByOriginAirportId` cuenta lo ya cargado por origen y
  el import salta esas líneas → se puede apagar/encender la PC a media carga sin duplicar ni reiniciar (POST
  `/api/import/shipments/dataset/full/start` continúa donde quedó). Datos persisten en volumen Postgres.
- [FIX] OOM en reset con dataset grande: una corrida materializa MILLONES de clones de vuelos
  (`baseCode+R+yyyy-MM-dd`); `resetDemandKeepingNetwork` hacía `flightRepository.findAll()` y los cargaba todos
  al heap → OutOfMemoryError. Ahora resetea por BULK QUERY (`resetOperationalStateFast`/`resetStorageLoadFast`,
  los mismos de `resetOperationalData`). El ruteo plan-ahead NO carga todos los vuelos (consulta por ventana).
- Capacidad de la red vs demanda COMPLETA: el corredor más cargado en el mes pico (dic-2028) usa ~36% de su
  capacidad de vuelos; total ~946k maletas/día de capacidad vs ~34k/día de demanda pico (~28× holgura). Por
  tanto NO hay colapso por VOLUMEN ni con los 9.5M; el colapso (escenario COLLAPSE_TEST) es siempre por
  RUTEO/TIMING (primer envío cuya mejor ruta no cumple deadline). PERIOD_SIMULATION sobre ventana acotada corre
  fiel y rápido; COLLAPSE_TEST plan-ahead es lento (planifica ~1k envíos/seg) pero fiel.
- [FIX] Planificación PLAN-AHEAD UNIFICADA: PERIOD_SIMULATION y COLLAPSE_TEST usan la MISMA maquinaria
  (`seedPeriod` + planner async incremental + `plannedThrough` + guard en el tick de `SimulationEngineService`);
  solo cambia el parámetro de fin (`SimulationRuntimeService.resolveScenarioEnd`: PERIOD = inicio + días,
  COLLAPSE = fin de datos). DAY_TO_DAY queda en modo live (planificar al vuelo). El guard evita que el reloj
  adelante a la planificación → corridas RÁPIDAS y FIELES (el colapso refleja envíos realmente tarde, no un
  atraso del planificador). Tope de velocidad subido a 5000× (`SimulationSpeedDto` + clamp de `setSpeed`).

## Decisiones de diseño importantes
- El engine NO escanea shipments durante ticks — trabaja solo con TravelStop. La detección de
  colapso usa una consulta acotada (LIMIT 1, índice `idx_shipment_deadline`) solo en COLLAPSE_TEST.
- /reset-demand borra TODOS los shipments.
- Cancelación de vuelos: MANUAL (UI Vuelos → "Cancelar vuelo") con replanificación automática del
  equipaje afectado. No hay cancelación automática.
- `FutureDemandProjectionService` / `DemandGenerationService` quedan como beans inertes (sin
  invocarse); no se borraron por dependencias en tests (`@MockBean`).

## Base de conocimiento de evaluación
- Para futuras sesiones, usar `documentacion/evaluacion-sw-base-conocimiento.md` como fuente de verdad
  sobre el Excel `c1inf54.983.Eq3C.AUTO.Eval.Sw.xlsx`, la interpretación de "almacenes" como aeropuertos
  y el plan priorizado de alineación con la rúbrica.
