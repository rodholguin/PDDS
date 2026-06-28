# Base de conocimiento – Evaluación de software `c1inf54.983.Eq3C.AUTO.Eval.Sw.xlsx`

Fecha de análisis: 2026-06-20  
Fuente principal: `PDDS/c1inf54.983.Eq3C.AUTO.Eval.Sw.xlsx`, hoja `91.EvaSw`  
Proyecto: Tasf.B2B – simulación logística de traslado de maletas entre aeropuertos

## 1. Propósito de este documento

Este documento deja una base de conocimiento consultable para futuras sesiones sobre:

1. Qué pide el Excel de evaluación en las tablas que empiezan por `Concepto de evaluación`.
2. Cómo deben interpretarse esos criterios en este proyecto, porque el Excel mezcla vocabulario de un proyecto anterior.
3. Cómo funciona actualmente la solución backend/frontend.
4. Qué brechas existen contra la rúbrica.
5. Qué plan de implementación conviene seguir, ordenado por criticidad.

No es un documento de implementación. Es una guía de análisis, trazabilidad y priorización.

## 2. Convenciones obligatorias para adaptar la rúbrica al dominio

El Excel usa términos genéricos o heredados. Para Tasf.B2B deben leerse así:

| Término en Excel | Interpretación en Tasf.B2B |
|---|---|
| almacén / almacenes | aeropuerto(s) / nodo(s) logístico(s) |
| almacenes principales | aeropuertos principales o nodos origen/relevantes; no hay una categoría separada real en el modelo actual |
| almacenes de paso | aeropuertos intermedios/hubs/escalas |
| unidades de transporte / UT | vuelos / aviones |
| productos | maletas |
| envíos | envíos o grupos de maletas |
| stock / llenado / ocupación | carga de maletas en aeropuertos o vuelos, usualmente número y porcentaje |
| tramos | vuelo origen-destino de una UT |
| ruta | ruta completa de un envío/maleta, potencialmente con escalas |

Decisión de diseño vigente del proyecto: el colapso de `COLLAPSE_TEST` se define como el primer envío que no llega a tiempo, no como saturación de aeropuertos. La capacidad de aeropuertos se usa principalmente para semáforos/KPIs y visualización; la capacidad dura para planificación está en vuelos.

## 3. Resumen ejecutivo de la evaluación

El Excel contiene estas secciones relevantes:

| Sección | Concepto | Requeridos en avance/software vivo | Opcionales `Op` | Marcados `No` / futuro |
|---|---:|---:|---:|---:|
| A | General | 17 | 0 | 6 |
| B | Configuración | 8 | 0 | 8 |
| C | Mapa base – todos los escenarios | 31 | 0 | 4 |
| D | Circunstancias | 2 | 0 | 13 |
| E | Panel | 16 | 18 | 2 |
| F | Vinculación mapa-panel | 9 | 4 | 5 |
| G | Cierre | 15 en avance / 16 en software vivo | 0 | 3-4 |

Prioridad real para la demo/evaluación:

1. Mapa base sólido: aeropuertos, vuelos, ocupación, semáforos, movimiento fluido y líneas de tramo.
2. Panel operativo: listas de vuelos y aeropuertos con ocupación, acceso a envíos y ordenamiento por ocupación.
3. Vinculación bidireccional mapa-panel: seleccionar aeropuerto/vuelo/envío en panel y enfocarlo en mapa, y viceversa.
4. Visualización de envíos/maletas/rutas por ID.
5. Reporte de cierre por escenario con última planificación estable.
6. Evidencia/documentación: Ta, Sa, Sc, despliegue, diagramas y fechas usadas.
7. Cancelaciones visibles en mapa.
8. Multi-visualizador para simulación 5D.

No conviene invertir tiempo, salvo que ya exista sin estorbar, en bloqueos, averías tipo 1-4 o mantenimiento específico de cancelaciones, porque están marcados `No`/futuro en la rúbrica.

## 4. Requisitos aplicables extraídos del Excel

### 4.1 A – General

| Ítem | Requisito aplicable |
|---|---|
| A01 | Versión: semana de avance o versión de software/prototipo. |
| A02 | Fecha de subida o presentación. |
| A03 | Comentarios o problemas reportados antes de iniciar la revisión. |
| A10 | Nombre del algoritmo planificador. |
| A11 | Nivel de avance del planificador. |
| A12 | Estudiantes asignados al planificador. |
| A13 | Nombre del algoritmo o tecnologías para el visualizador. |
| A14 | Nivel de avance del visualizador. |
| A15 | Estudiantes asignados al visualizador. |
| A16 | Evidencia de revisión del video sobre simulación consumiendo datos. |
| A17 | Tiempo de ejecución del algoritmo: `Ta`. |
| A18 | Tiempo de salto del algoritmo: `Sa`. |
| A19 | Salto temporal del eje de consumo: `Sc`. |
| A20 | Estado de despliegue del frontend. |
| A21 | Estado de despliegue del backend. |
| A22 | Diagrama de consumo de datos en bloques `Sc` por cada bloque del planificador `Sa`. |
| A23 | Diagrama de interacción de varios navegadores durante la simulación del periodo. |

Notas:

- Estos son mayormente requisitos de sustentación/evidencia, no necesariamente de código.
- Conviene preparar una lámina o documento breve con `Ta`, `Sa`, `Sc`, despliegue y arquitectura multi-navegador.

### 4.2 B – Configuración

| Ítem | Requisito aplicable |
|---|---|
| B01 | Explicar cómo se configura el mapa en la pantalla principal. |
| B03 | Establecer/actualizar atributos de aeropuertos: ubicación, capacidad, mantenimiento/importación. |
| B04 | Establecer/actualizar atributos de vuelos/aviones: ubicación, capacidad, mantenimiento/importación. |
| B11 | Establecer/actualizar tramos que recorren los vuelos: origen, destino, horarios de salida/llegada. |
| B13 | Explicar cómo se carga/sube la data histórica/futura a la solución, con o sin RDBMS. |
| B14 | Fechas/horas usadas para pruebas de simulación del periodo. |
| B15 | Usar datos proporcionados sin reducción de registros. |
| B16 | Carga de todos los datos independiente de los escenarios. |

Lectura recomendada:

- Para B03/B04/B11, el sistema puede sustentarlo con importación de datos oficiales y endpoints de consulta. Si el evaluador exige mantenimiento UI/CRUD explícito, habría que agregar una pantalla mínima; hoy no es el hueco más visible frente a mapa/panel.
- B15/B16 son fuertes en el backend actual: la carga full del dataset oficial está separada de los escenarios y es reanudable.

### 4.3 C – Mapa base, todos los escenarios

| Ítem | Requisito aplicable |
|---|---|
| C01 | La carga de archivos/datos de envíos se realiza en otra opción. |
| C02 | El inicio pide fecha y hora hasta nivel de minuto. |
| C03 | Tiempo aproximado desde presionar iniciar hasta mostrar el mapa. |
| C04 | Tiempo aproximado desde presionar iniciar hasta que el transporte se mueva. |
| C07 | La interfaz está toda en español o toda en inglés, sin mezcla. |
| C08 | Zoom in/out adecuado para zonas y detalles. |
| C09 | Al iniciar, presenta completa la pantalla principal. |
| C10 | Muestra todos los aeropuertos simultáneamente. |
| C11 | Pantalla limpia: elementos no disponibles ocultos/deshabilitados. |
| C12 | Muestra fecha-hora simulada a nivel de minuto en simulación 5D y colapso. |
| C13 | Muestra tiempo transcurrido del momento simulado. |
| C14 | Muestra fecha-hora actual/presente. |
| C15 | Muestra tiempo transcurrido hasta el momento actual. |
| C16 | Los datos de tiempo están bien ubicados, con tamaño y contraste adecuados. |
| C17 | Cada aeropuerto aparece en la ubicación prevista. |
| C18 | Ícono de aeropuerto con tamaño adecuado. |
| C19 | Ícono representa correctamente un aeropuerto. |
| C20 | Ícono de aeropuerto contrasta con el mapa. |
| C21 | Ícono de aeropuerto usa semáforo + vacío según stock/ocupación. |
| C22 | Muestra ocupación del aeropuerto en número o porcentaje. |
| C23 | Cada vuelo/avión aparece en la ubicación prevista. |
| C24 | Ícono de vuelo/avión con tamaño adecuado. |
| C25 | Ícono representa correctamente un avión. |
| C26 | Ícono de vuelo/avión contrasta con el resto. |
| C27 | Ícono de vuelo/avión usa semáforo-vacío según ocupación. |
| C28 | Muestra ocupación de cada vuelo/avión en número o porcentaje. |
| C29 | El vuelo/avión se desplaza con fluidez, sin saltos ni anomalías. |
| C30 | El vuelo/avión se presenta coherente con su desplazamiento y alineado al movimiento. |
| C31 | Se presenta el tramo origen-destino como línea al inicio del vuelo. |
| C33 | La línea del tramo tiene grosor/ideografía adecuada. |
| C34 | La línea del tramo se borra o cambia luego de ser recorrida. |

No aplican en esta sección:

- C05/C06: visualizadores con acciones independientes amplias. Ojo: G06 sí pide al menos dos visualizadores para simulación 5D.
- C32: ruta completa del avión como línea. La rúbrica exige el tramo del vuelo, no necesariamente la ruta completa del envío.
- C35: avión en tierra cuando no vuela.

### 4.4 D – Circunstancias

| Ítem | Requisito aplicable |
|---|---|
| D14 | Cada cancelación se presenta en el mapa y se puede describir cómo. |
| D15 | La cancelación está visible el tiempo previsto. |

No conviene implementar ahora:

- Bloqueos de tramos/nodos: D03-D09.
- Averías tipo 1-4: D10-D13.
- Ítems D01-D02: futuro.

### 4.5 E – Panel

| Ítem | Requisito aplicable |
|---|---|
| E01 | Uno o más paneles contraídos/colapsados al inicio en ubicaciones idóneas. |
| E02 | Lista de todos los vuelos/aviones con ocupación/stock en número o %. |
| E03 | Desde la lista de vuelos/aviones, acceso a los envíos que traslada. |
| E04 | Desde la lista de vuelos/aviones, acceso a las maletas que traslada. |
| E05 | Stock actual de cada vuelo/avión como semáforo-vacío. |
| E06 | Búsqueda de vuelo/avión por código o equivalente como tramo. |
| E07 | Búsqueda de vuelo/avión por aeropuerto/ciudad de origen. |
| E08 | Búsqueda de vuelo/avión por aeropuerto/ciudad de destino. |
| E12 | Ordenamiento de lista de vuelos/aviones por nivel de ocupación. |
| E17 | Lista de aeropuertos con ocupación/stock en número o %. |
| E18 | Lista de aeropuertos con acceso a envíos en el aeropuerto, destino final y en tránsito. |
| E20 | Lista de aeropuertos con colores semáforo-vacío. |
| E21 | Lista de aeropuertos con información planificada de envíos que entran. |
| E23 | Lista de aeropuertos con información planificada de envíos que salen. |
| E27 | Ordenamiento de lista de aeropuertos por nivel de ocupación. |
| E30 | Lista planificada de envíos a transportar, indicando destino, vuelo/avión y cantidad de maletas. |

Opcionales `Op` de panel:

| Ítem | Opcional |
|---|---|
| E09 | Filtrar vuelos/aviones por código o patrón. |
| E10 | Filtrar vuelos/aviones por aeropuerto/ciudad de origen. |
| E11 | Filtrar vuelos/aviones por aeropuerto/ciudad de destino. |
| E13 | Ordenar vuelos/aviones por hora de salida. |
| E14 | Ordenar vuelos/aviones por hora de llegada. |
| E15 | Ordenar vuelos/aviones por origen. |
| E16 | Ordenar vuelos/aviones por destino. |
| E19 | En lista de aeropuertos, acceso a lista de maletas en aeropuerto. |
| E22 | Información planificada de maletas que entran a aeropuertos. |
| E24 | Información planificada de maletas que salen de aeropuertos. |
| E25 | Filtrar aeropuertos por código/patrón. |
| E26 | Filtrar aeropuertos por región/continente. |
| E28 | Ordenar aeropuertos por hora de salida del próximo vuelo. |
| E29 | Ordenar aeropuertos por hora de llegada del próximo vuelo. |
| E31 | Lista de envíos que están en vuelos, con origen, destino, vuelo y cantidad de maletas. |
| E32 | Lista de envíos entregados en las últimas 4 horas. |
| E33 | Filtrar envíos por origen, en tramo o ruta. |
| E34 | Filtrar vuelos/aviones por destino, en tramo o ruta. |

### 4.6 F – Vinculación mapa-panel bidireccional

| Ítem | Requisito aplicable |
|---|---|
| F01 | Desde botón/panel, mostrar en el mapa la ruta que sigue una maleta por ID, a demanda. |
| F03 | Desde botón/panel, mostrar en el mapa la ruta que sigue un envío/grupo de maletas por ID, a demanda. |
| F05 | Seleccionar aeropuerto en panel y enlazar/enfocar en mapa. |
| F06 | Seleccionar aeropuerto en mapa y enlazar/enfocar en panel. |
| F07 | Seleccionar vuelo/avión en panel y enlazar/enfocar en mapa. |
| F08 | Seleccionar vuelo/avión en mapa y enlazar/enfocar en panel. |
| F09 | Seleccionar envío en panel y enlazar/enfocar en mapa, sea en aeropuerto o vuelo/avión. |
| F15 | Filtrar por semáforo de aeropuertos en panel y reflejarlo en mapa. |
| F16 | Filtrar por semáforo de vuelos/aviones en panel y reflejarlo en mapa. |

Opcionales `Op` de vinculación:

| Ítem | Opcional |
|---|---|
| F02 | Mostrar ruta de una maleta con datos relevantes de escalas. |
| F04 | Mostrar ruta de un envío/grupo de maletas con datos relevantes de escalas. |
| F17 | Otros filtros de aeropuertos en panel reflejados en mapa. |
| F18 | Otros filtros de vuelos/aviones en panel reflejados en mapa. |

No conviene implementar ahora:

- F10: lista/vinculación de bloqueos.
- F11-F14: averías tipo 1-4.

### 4.7 G – Cierre

| Ítem | Requisito aplicable |
|---|---|
| G01 | Indicador global de ocupación/llenado de la flota de vuelos/aviones. |
| G02 | Indicador global de flota presentado como semáforo. |
| G03 | Indicador global de ocupación/llenado de aeropuertos. |
| G04 | Indicador global de aeropuertos presentado como semáforo. |
| G05 | Respeto del tiempo mínimo de permanencia de maletas en aeropuertos. |
| G06 | Al menos 2 visualizadores/navegadores desde dispositivos distintos, con interacción independiente, para un único planificador en simulación 5D. |
| G08 | Reporte de última planificación estable al finalizar simulación del periodo. |
| G09 | Reporte de última planificación estable al cerrar operaciones día a día. |
| G10 | Reporte de última planificación estable al finalizar simulación de colapso. |
| G11 | Percepción global: completo / al día. |
| G12 | Percepción global: apropiado. |
| G13 | Percepción global: claro, no ambiguo. |
| G14 | Percepción global: factible en el plazo. |
| G17 | Percepción global: fortalezas. |
| G18 | Percepción global: aspectos a mejorar. |
| G19 | Solo software vivo: potencial invitación XpoSTEM. En avance aparece como `No`. |

Notas:

- G05 tiene soporte técnico en el planificador: `RoutePlanningSupport` define `MIN_CONNECTION_MINUTES = 30`, por lo que las escalas exigen al menos 30 minutos entre vuelos.
- G08-G10 no están plenamente cubiertos por la pantalla actual de reportes; existe export de resultados, pero el cierre integral de corrida debe reforzarse.

## 5. Requisitos marcados `No`/futuro que no deben priorizarse

| Ítems | Motivo |
|---|---|
| A04-A09 | Eran para prototipo: detalle del 1er/2do algoritmo y estudiantes. |
| B02 | Mantenimiento específico de aeropuertos principales. |
| B05-B06 | Configuración/mantenimiento de bloqueos de tramos o nodos. |
| B07-B10 | Configuración de averías tipo 1, 2, 3 y 4. |
| B12 | Mantenimiento de atributos de cancelaciones. Visualizarlas sí aplica por D14-D15. |
| C05-C06 | Visualizadores independientes amplios. G06 sí exige 2 navegadores para un único planificador. |
| C32 | Línea de ruta completa del avión; aplica tramo, no ruta completa. |
| C35 | Avión en tierra cuando no vuela. |
| D01-D02 | Futuro. |
| D03-D09 | Bloqueos en mapa. |
| D10-D13 | Averías en mapa. |
| E35-E36 | Futuro. |
| F10 | Vinculación/lista de bloqueos. |
| F11-F14 | Vinculación de averías. |
| G07 | Manejo de bloqueos en planificador. |
| G15-G16 | Verificable/conformidad marcados como no aplicables. |

## 6. Funcionamiento actual de la solución

### 6.1 Stack y estructura

Backend:

- Spring Boot 3.4.4, Java 21.
- PostgreSQL 15.
- JPA/Hibernate con `ddl-auto=update` y parches manuales en `SchemaPatchService`.
- API REST bajo `/api`.
- Swagger en `/swagger-ui.html`.

Frontend:

- Next.js 16.2.2, React 19.2.4, TypeScript.
- MapLibre/`react-map-gl` para el mapa.
- Estado global de simulación en `frontend/lib/SimulationContext.tsx`.
- Pantallas principales:
  - `/`: centro operativo y mapa.
  - `/registro`: registro/carga live de envíos.
  - `/shipments`: maestro de envíos.
  - `/flights`: maestro de vuelos.
  - `/import`: importación de datos.
  - `/reports`: reportes SLA.

### 6.2 Modelo de dominio backend

Archivos clave:

- `backend/src/main/java/com/tasfb2b/model/Airport.java`
- `backend/src/main/java/com/tasfb2b/model/Flight.java`
- `backend/src/main/java/com/tasfb2b/model/Shipment.java`
- `backend/src/main/java/com/tasfb2b/model/TravelStop.java`
- `backend/src/main/java/com/tasfb2b/model/SimulationConfig.java`

Resumen:

| Modelo | Rol en rúbrica | Campos/función clave |
|---|---|---|
| `Airport` | almacén/aeropuerto | ICAO, ciudad, país, continente, lat/lon, GMT, capacidad, carga actual, semáforo. |
| `Flight` | UT/vuelo/avión | código, origen/destino, capacidad, carga actual/reservada, salida/llegada, estado. |
| `Shipment` | envío/grupo de maletas | código, origen/destino, maletas, registro, deadline, estado, fuente `HISTORICAL`/`LIVE`. |
| `TravelStop` | parada/tramo de ruta | envío, aeropuerto, vuelo, orden, llegada programada/real, estado. |
| `SimulationConfig` | configuración/estado de escenario | escenario, días, umbrales, velocidad, timestamps, colapso detectado. |

Estados principales:

- `SimulationScenario`: `DAY_TO_DAY`, `PERIOD_SIMULATION`, `COLLAPSE_TEST`.
- `ShipmentStatus`: `PENDING`, `IN_ROUTE`, `DELIVERED`, `DELAYED`, `CRITICAL`.
- `FlightStatus`: `SCHEDULED`, `IN_FLIGHT`, `COMPLETED`, `CANCELLED`.
- `AlgorithmType`: `GENETIC`, `ANT_COLONY`, `SIMULATED_ANNEALING`.

### 6.3 Importación y datos

Archivos clave:

- `backend/src/main/java/com/tasfb2b/service/DataImportService.java`
- `backend/src/main/java/com/tasfb2b/service/EnviosImportJobService.java`
- `backend/src/main/java/com/tasfb2b/service/FlightScheduleService.java`
- `frontend/app/import/page.tsx`

Comportamiento actual:

1. Aeropuertos se cargan desde `datos/c.1inf54.26.1.v1.Aeropuerto.husos.v1.20250818__estudiantes.txt`.
2. Vuelos base se cargan desde `datos/planes_vuelo.txt`.
3. Envíos oficiales van en `datos/envios/` y no están en repo.
4. La importación full de envíos oficiales es async y reanudable.
5. El import conserva fechas reales del dataset y convierte horarios locales a UTC con `gmtOffset`.
6. La carga de datos está separada de los escenarios, lo que cubre B13/B15/B16.

Endpoints relevantes:

- `POST /api/import/dataset/default`
- `GET /api/import/dataset-status`
- `POST /api/import/shipments/dataset`
- `POST /api/import/shipments/dataset/full`
- `POST /api/import/shipments/dataset/full/start`
- `GET /api/import/shipments/dataset/full/status`
- `POST /api/import/shipments`, `/airports`, `/flights`
- `GET /api/import/template/{type}`

### 6.4 Planificación y algoritmos

Archivos clave:

- `backend/src/main/java/com/tasfb2b/service/RoutePlannerService.java`
- `backend/src/main/java/com/tasfb2b/service/algorithm/RoutePlanningSupport.java`
- `backend/src/main/java/com/tasfb2b/service/algorithm/FastRoutePlanning.java`
- `backend/src/main/java/com/tasfb2b/service/algorithm/GeneticAlgorithm.java`
- `backend/src/main/java/com/tasfb2b/service/algorithm/AntColonyOptimization.java`
- `backend/src/main/java/com/tasfb2b/service/algorithm/SimulatedAnnealingOptimization.java`

Comportamiento actual:

1. Busca vuelos elegibles por origen/destino, ventana temporal, estado y capacidad.
2. Respeta capacidad de vuelos con `currentLoad + reservedLoad`.
3. Reserva capacidad con actualización atómica `FlightRepository.reserveCapacityIfAvailable`.
4. Evalúa rutas directas y multi-tramo.
5. Aplica mínimo de conexión de 30 minutos (`MIN_CONNECTION_MINUTES = 30`).
6. Usa fast path y, si hace falta, algoritmos de optimización.
7. Materializa rutas como `TravelStop`.
8. Si no encuentra ruta, marca el envío como `CRITICAL` y genera alerta.

Riesgo de alineación:

- `SimulationConfigUpdateDto` permite `primaryAlgorithm` y `secondaryAlgorithm`, pero `SimulationController.configure` fuerza `GENETIC` en ambos. Si se quiere sustentar comparación real GA/ACO, hay que corregir o documentar que GA es el planificador operativo seleccionado.

### 6.5 Simulación

Archivos clave:

- `backend/src/main/java/com/tasfb2b/service/SimulationRuntimeService.java`
- `backend/src/main/java/com/tasfb2b/service/SimulationEngineService.java`
- `backend/src/main/java/com/tasfb2b/service/SimulationAsyncOperationsService.java`
- `backend/src/main/java/com/tasfb2b/service/PeriodSimulationBootstrapService.java`

Escenarios:

| Escenario | Uso actual |
|---|---|
| `DAY_TO_DAY` | Operación live, usa envíos `LIVE`; reloj real. |
| `PERIOD_SIMULATION` | Simulación por 3/5/7 días; usa dataset histórico; plan-ahead. |
| `COLLAPSE_TEST` | Simulación hasta primer envío tarde; usa dataset histórico; plan-ahead; se detiene al colapso. |

Motor:

1. Tick programado cada 250 ms.
2. Calcula horizonte simulado según escenario/velocidad.
3. En plan-ahead, no deja avanzar el reloj más allá de `plannedThrough`.
4. Activa salidas y paradas pendientes.
5. Cierra vuelos llegados y actualiza stops/envíos.
6. En `COLLAPSE_TEST`, detecta primer envío con deadline vencido o entregado tarde y detiene.

### 6.6 Dashboard, mapa y feeds vivos

Backend:

- `DashboardController` expone:
  - `/api/dashboard/overview`
  - `/api/dashboard/system-status`
  - `/api/dashboard/map-live`
  - `/api/dashboard/map-live-flights`
  - `/api/dashboard/shipments`
  - `/api/dashboard/shipments/search`
  - `/api/dashboard/nodes/{icao}`
  - `/api/dashboard/routes-network`

Frontend:

- `SimulationContext` hace polling de:
  - estado de simulación cada 2 s,
  - mapa cada 2 s,
  - overview cada 2.5 s,
  - sistema/riesgo cada 6 s,
  - aeropuertos cada 30 s,
  - próximos vuelos cada 3 s.
- El mapa principal está en `frontend/app/page.tsx`.
- Vuelos se renderizan como marcadores de avión animados.
- Aeropuertos se renderizan como puntos con semáforo/ocupación.
- `MapLiveShipmentDto` ya existe y trae posición de envíos, pero el frontend actual no renderiza marcadores de envíos/maletas.

DTOs útiles:

- `MapLiveFlightDto`: `flightId`, `flightCode`, origen/destino, coordenadas actuales, coordenadas origen/destino, `loadPct`.
- `MapLiveShipmentDto`: `shipmentId`, `shipmentCode`, origen/destino, coordenadas actuales, próximo punto, progreso.
- `NodeDetailDto`: capacidad/carga de aeropuerto, vuelos programados/en vuelo, envíos almacenados/entrantes/salientes, próximos vuelos.

### 6.7 Pantallas frontend actuales

| Pantalla | Archivo | Cobertura fuerte | Brechas principales |
|---|---|---|---|
| Inicio/mapa | `frontend/app/page.tsx` | mapa, aeropuertos, vuelos, KPIs, controles, alertas, colapso | no renderiza envíos/maletas; filtros de mapa mínimos; selección global parcial; velocidades UI solo 1/5/20 aunque backend soporta más. |
| Registro | `frontend/app/registro/page.tsx` | creación live de envío, factibilidad, carga `.txt`, comprobante | no es foco de rúbrica mapa-panel. |
| Envíos | `frontend/app/shipments/page.tsx` | tabla, filtros, detalle, ruta, historial, replanificar, entregar | no consume `?selected=<id>`, aunque otros componentes enlazan así. |
| Vuelos | `frontend/app/flights/page.tsx` | tabla, filtros, deep-link `?selected=`, detalle, envíos asignados, cancelar | buen soporte de UT; falta enlace real de vuelta al mapa. |
| Importar | `frontend/app/import/page.tsx` | dataset status, full async, plantillas, uploads | progreso full granular limitado. |
| Reportes | `frontend/app/reports/page.tsx` | SLA por rango, cliente, ruta, destino | no es reporte integral de cierre/última planificación estable. |

## 7. Cobertura actual contra la rúbrica

### 7.1 Lo que está razonablemente cubierto

- Importación separada de datos (`/import`) y endpoints dedicados.
- Uso del dataset oficial sin reducción en modo full.
- Separación de carga de datos y escenarios.
- Conversión de husos horarios local→UTC.
- Escenarios `DAY_TO_DAY`, `PERIOD_SIMULATION`, `COLLAPSE_TEST`.
- Planificación multi-tramo con capacidad de vuelos y deadline.
- Mínimo de conexión de 30 minutos.
- Mapa con aeropuertos en ubicación real.
- Mapa con vuelos/aviones vivos y animados.
- Popups de aeropuerto/vuelo.
- Página de vuelos con ocupación, filtros y envíos asignados.
- Página de envíos con detalle, ruta y auditoría.
- Cancelación manual de vuelo y replanificación backend.
- KPIs, alertas y riesgo de colapso.
- Reporte SLA básico.
- Export CSV/PDF de resultados de simulación existe a nivel API.

### 7.2 Brechas críticas

| Prioridad | Brecha | Impacto en rúbrica | Archivos principales |
|---:|---|---|---|
| P0 | No hay marcadores/selección de envíos o maletas en el mapa, aunque el feed existe. | F01, F03, F09 y parte de E30/E31 quedan débiles. | `frontend/app/page.tsx`, `SimulationContext.tsx`, `MapLiveShipmentDto.java` |
| P0 | `/shipments?selected=<id>` no funciona. | Vinculación desde mapa/vuelos/alertas hacia detalle de envío falla. | `frontend/app/shipments/page.tsx` |
| P0 | No hay selección global bidireccional mapa-panel. | F05-F09 quedan parciales. | `frontend/app/page.tsx`, `flights/page.tsx`, `shipments/page.tsx`, `SimulationContext.tsx` |
| P0 | Reporte de cierre/última planificación estable no está integrado en UI. | G08-G10 quedan incompletos. | `frontend/app/reports/page.tsx`, `SimulationExportService.java`, `SimulationController.java` |
| P1 | Panel de aeropuertos no muestra listas detalladas de envíos en nodo ni entradas/salidas planificadas. | E18, E21, E23, E27 débiles. | `DashboardController.java`, `NodeDetailDto.java`, `frontend/app/page.tsx` |
| P1 | Filtros por semáforo de aeropuertos/vuelos no se reflejan en el mapa. | F15-F16. | `frontend/app/page.tsx` |
| P1 | Cancelaciones no tienen representación temporal clara en el mapa. | D14-D15. | `FlightController.java`, `DashboardController.java`, `frontend/app/page.tsx` |
| P1 | Indicadores globales de ocupación de flota/aeropuertos no están presentados como semáforo global suficientemente explícito. | G01-G04. | `DashboardController.java`, `frontend/app/page.tsx` |
| P2 | Configuración de algoritmo primario/secundario se expone en tipos pero se fuerza a GA. | A10/A11 y sustentación GA/ACO pueden ser inconsistentes. | `SimulationController.java`, `SimulationConfigUpdateDto.java` |
| P2 | Resultados comparativos de algoritmos no reflejan una corrida real completa. | Sustentación de algoritmos y cierre. | `AlgorithmRaceService.java`, `SimulationExportService.java`, `SimulationController.java` |
| P2 | Tests backend reportados como fallidos/desactualizados. | Riesgo técnico si el evaluador ejecuta tests. | `backend/src/test/java/...` |
| P3 | Algunos comentarios/docs del backend hablan de colapso por saturación de almacenes, pero el motor usa primer envío tarde. | Riesgo de explicación contradictoria. | `CollapseMonitorService.java`, `Airport.java`, docs |
| P3 | `BenchmarkTuningService` puede resetear demanda oficial. | Riesgo operativo antes de demo. | `BenchmarkTuningService.java`, `SimulationRuntimeService.java` |

## 8. Plan de implementación priorizado

No implementar todavía; este es el orden recomendado cuando se decida avanzar.

### Fase 0 – Alineación y evidencia para evaluación

Objetivo: evitar perder puntos por explicación, contradicción o demo mal preparada.

1. Formalizar en documentación/demo que:
   - almacenes = aeropuertos,
   - UT = vuelos/aviones,
   - productos = maletas,
   - colapso = primer envío que no llega a tiempo.
2. Preparar evidencia para A01-A23:
   - versión/fecha,
   - equipo/responsables,
   - algoritmo planificador operativo,
   - tecnologías del visualizador,
   - tiempos `Ta`, `Sa`, `Sc`,
   - estado de despliegue frontend/backend,
   - diagrama Sc/Sa,
   - diagrama multi-navegador.
3. Definir fechas/horas de demo para B14.
4. Tener capturas o pantalla de importación full/dataset status para B13-B16.
5. Limpiar o documentar comentarios contradictorios sobre colapso por saturación.
6. No ejecutar benchmark/reset cerca de la demo sin respaldo de BD.

### Fase 1 – Cerrar brechas visibles mapa-panel

Objetivo: cubrir los criterios más observables por el profesor.

1. Renderizar `mapLive` de envíos/maletas en el mapa:
   - marcador pequeño por envío/grupo de maletas,
   - color por estado/riesgo si el backend lo provee o se deriva,
   - popup con código, origen, destino, progreso, vuelo actual si existe,
   - enlace funcional al detalle.
2. Hacer funcionar `/shipments?selected=<id>`:
   - leer `useSearchParams`,
   - buscar/abrir detalle del envío aunque no esté en la primera página,
   - mantener selección al navegar desde vuelos, mapa o alertas.
3. Implementar selección global mapa-panel:
   - `selectedAirportIcao`, `selectedFlightId`, `selectedShipmentId` compartidos.
   - panel → mapa: enfocar y resaltar.
   - mapa → panel: abrir/expandir detalle y resaltar fila.
4. Agregar acción “ver en mapa” desde detalle de vuelo/envío/aeropuerto.
5. Mostrar ruta de envío por ID:
   - usar `ShipmentDetail.stops/legs`,
   - dibujar polilínea de la ruta completa del envío,
   - indicar escalas si se decide cubrir F02/F04 opcionales.
6. Implementar filtros por semáforo de aeropuertos y vuelos que afecten el mapa.

Criterios cubiertos principalmente: C21-C28, E03-E05, E17-E20, F01, F03, F05-F09, F15-F16.

### Fase 2 – Panel operativo completo

Objetivo: cumplir los criterios E sin depender solo de páginas separadas.

1. Panel de vuelos/UT:
   - lista de vuelos con ocupación número/% y semáforo,
   - ordenar por ocupación,
   - búsqueda por código/tramo/origen/destino,
   - acceso a envíos asignados.
2. Panel de aeropuertos:
   - lista de aeropuertos con ocupación número/% y semáforo,
   - ordenar por ocupación,
   - detalle con envíos almacenados/en tránsito/finales,
   - entradas planificadas y salidas planificadas.
3. Si `NodeDetailDto` no alcanza, agregar endpoints livianos:
   - envíos actuales por aeropuerto,
   - inbound/outbound planificado por aeropuerto,
   - agregados de maletas por aeropuerto.
4. Lista planificada de envíos a transportar:
   - destino,
   - vuelo/UT,
   - cantidad de maletas,
   - estado.

Criterios cubiertos principalmente: E01-E08, E12, E17-E18, E20-E23, E27, E30.

### Fase 3 – Cierre y reportes de corrida

Objetivo: cubrir G08-G10 y evitar que `/reports` sea solo SLA operativo.

1. Persistir o reconstruir “última planificación estable” por corrida/escenario.
2. En `/reports` o una pestaña de cierre:
   - resumen de la corrida,
   - escenario,
   - inicio/fin,
   - envíos planificados, entregados, tarde, críticos,
   - supervivencia hasta colapso,
   - ocupación promedio/max de vuelos,
   - ocupación promedio/max de aeropuertos,
   - replanificaciones,
   - cancelaciones/eventos,
   - ruta/causa del envío de colapso si aplica.
3. Conectar UI con:
   - `simulationApi.getResults()`
   - `simulationApi.exportResults('csv'|'pdf')`
4. Mejorar backend si `/api/simulation/results` devuelve placeholders o ganador fijo.
5. Preparar export CSV/PDF presentable.

Criterios cubiertos: G01-G04, G08-G10, G11-G18.

### Fase 4 – Cancelaciones y circunstancias aplicables

Objetivo: cubrir D14-D15 sin meterse en bloqueos/averías.

1. Mostrar vuelos cancelados en mapa por un tiempo configurable/razonable.
2. Usar color/estilo distinguible para cancelación.
3. Mostrar tooltip/popup con:
   - código de vuelo,
   - origen/destino,
   - hora de cancelación,
   - envíos replanificados/afectados.
4. Si el backend no emite cancelaciones recientes en feeds, agregar un endpoint o extender `routes-network`/`map-live-flights` sin sobrecargar.

Criterios cubiertos: D14-D15.

### Fase 5 – Calidad técnica y consistencia

Objetivo: reducir riesgos de auditoría técnica.

1. Arreglar tests existentes:
   - `RoutePlannerInitialStateTest` por stubbing desactualizado,
   - `SimulationEngineDepartureFlowTest` por flujo de tick actualizado.
2. Agregar pruebas críticas:
   - importación con GMT,
   - ruteo multi-tramo,
   - reserva atómica de capacidad,
   - colapso por primer deadline,
   - cancelación/replanificación,
   - separación `LIVE` vs `HISTORICAL`,
   - full import/resume si se puede testear sin dataset enorme.
3. Alinear comentarios y README con comportamiento actual.
4. Decidir si algoritmo secundario será funcional o se quitará/explicará de la UI.
5. Proteger endpoints destructivos o al menos advertir claramente:
   - `/reset-demand`,
   - benchmark,
   - import full,
   - cancelación.
6. Considerar migraciones formales si el despliegue/evaluación lo exige.

### Fase 6 – Opcionales con buen retorno si queda tiempo

1. Filtros opcionales de vuelos por código/origen/destino en el panel si aún no están integrados al mapa.
2. Filtros opcionales de aeropuertos por código/continente.
3. Ordenamientos opcionales por hora de salida/llegada.
4. Visualización de escalas con datos relevantes para F02/F04.
5. Integrar `FlightMapLayer.tsx` si el número de vuelos visibles vuelve pesado el render con markers React.
6. Exponer velocidades altas en UI para escenarios acelerados, alineadas con el backend.

## 9. Riesgos y decisiones a recordar en futuras sesiones

1. No reinterpretar “almacenes” como entidad nueva. En este proyecto son aeropuertos.
2. No implementar bloqueos/averías por inercia: la rúbrica los marca `No`/futuro.
3. Si se cambia la definición de colapso a saturación de aeropuertos, sería un cambio de diseño mayor y afectaría backend, métricas y explicación. Hoy no conviene salvo indicación explícita.
4. La UI ya pide/usa feeds de envíos en mapa, pero no los renderiza: es una brecha de alto impacto y bajo/medio esfuerzo.
5. La página de envíos no consume `?selected`, rompiendo varios enlaces ya existentes.
6. Los reportes actuales son SLA; la rúbrica de cierre pide última planificación estable por escenario.
7. Backend compila según análisis, pero la suite de tests fue reportada como roja por tests desactualizados.
8. Hay muchas modificaciones no confirmadas en el working tree; no sobrescribir código sin revisar `git status`.
9. El archivo Excel de evaluación está en raíz y aparece como no trackeado en Git; no borrarlo.

## 10. Archivos clave para futuras sesiones

Backend:

- `backend/src/main/java/com/tasfb2b/controller/DataImportController.java`
- `backend/src/main/java/com/tasfb2b/controller/SimulationController.java`
- `backend/src/main/java/com/tasfb2b/controller/DashboardController.java`
- `backend/src/main/java/com/tasfb2b/controller/FlightController.java`
- `backend/src/main/java/com/tasfb2b/controller/ShipmentController.java`
- `backend/src/main/java/com/tasfb2b/controller/AirportController.java`
- `backend/src/main/java/com/tasfb2b/service/DataImportService.java`
- `backend/src/main/java/com/tasfb2b/service/RoutePlannerService.java`
- `backend/src/main/java/com/tasfb2b/service/SimulationEngineService.java`
- `backend/src/main/java/com/tasfb2b/service/SimulationAsyncOperationsService.java`
- `backend/src/main/java/com/tasfb2b/service/SimulationRuntimeService.java`
- `backend/src/main/java/com/tasfb2b/service/ReportingService.java`
- `backend/src/main/java/com/tasfb2b/service/SimulationExportService.java`
- `backend/src/main/java/com/tasfb2b/service/algorithm/RoutePlanningSupport.java`

Frontend:

- `frontend/app/page.tsx`
- `frontend/app/registro/page.tsx`
- `frontend/app/shipments/page.tsx`
- `frontend/app/flights/page.tsx`
- `frontend/app/import/page.tsx`
- `frontend/app/reports/page.tsx`
- `frontend/lib/SimulationContext.tsx`
- `frontend/lib/types/index.ts`
- `frontend/lib/api/dashboardApi.ts`
- `frontend/lib/api/simulationApi.ts`
- `frontend/components/FlightTrajectoryLayer.tsx`
- `frontend/components/FlightMapLayer.tsx`

Documentación/datos:

- `c1inf54.983.Eq3C.AUTO.Eval.Sw.xlsx`
- `CLAUDE.md`
- `documentacion/le-e-trazabilidad-v02.md` – ojo: parece parcialmente desactualizado, menciona `frontend/app/simulation/page.tsx`, que ya no existe.
- `datos/c.1inf54.26.1.v1.Aeropuerto.husos.v1.20250818__estudiantes.txt`
- `datos/planes_vuelo.txt`
- `datos/envios/` – carpeta esperada para dataset oficial de envíos, no versionada.
