# Plan de alineación con rúbrica `c1inf54.983.Eq3C.AUTO.Eval.Sw`

Fecha: 2026-06-21
Fuente: Excel `91.EvaSw` + `evaluacion-sw-base-conocimiento.md` + análisis de código

---

## 1. MAPEO COMPLETO AXX-GXX: Estado vs Rúbrica

Leyenda:
- **✅** = Implementado correctamente
- **⚠️** = Parcial/falta pulir
- **❌** = No implementado
- **N/A** = No aplica según rúbrica
- **Op** = Opcional según rúbrica

---

### SECCIÓN A — GENERAL (17 requeridos, 0 opcionales, 6 N/A)

| Item | Descripción | Estado | Cómo se cubre / Brecha |
|------|-------------|--------|----------------------|
| **A01** | Versión del software/prototipo | ❌ | No hay versión visible. Agregar en UI o documentación. |
| **A02** | Fecha de subida/presentación | ❌ | No visible. Agregar. |
| **A03** | Comentarios/problemas reportados | ❌ | Evidencia de sustentación. |
| **A04-A09** | NOMBRE/AVANCE/ESTUDIANTES 1er/2do algoritmo | N/A | Marcado No en rúbrica. |
| **A10** | Nombre del algoritmo planificador | ✅ | GENETIC (GeneticAlgorithm.java). El sistema tiene GA, ACO, SA pero GA es el operativo. |
| **A11** | Nivel de avance del planificador (%) | ❌ | No hay métrica visible. Documentar. |
| **A12** | Estudiantes asignados al planificador | ❌ | Documentar en lámina. |
| **A13** | Nombre del algoritmo/tecnologías del visualizador | ✅ | MapLibre + Next.js + React. |
| **A14** | Nivel de avance del visualizador (%) | ❌ | Documentar. |
| **A15** | Estudiantes asignados al visualizador | ❌ | Documentar. |
| **A16** | Evidencia revisión video simulación | ❌ | Documentar. |
| **A17** | Ta (tiempo ejecución algoritmo) | ❌ | No hay métrica visible. Capturar en UI. |
| **A18** | Sa (tiempo salto algoritmo) | ❌ | No hay métrica visible. Capturar en UI. |
| **A19** | Sc (salto eje consumo) | ❌ | No hay métrica visible. Capturar en UI. |
| **A20** | Estado despliegue frontend | ✅ | VM desplegada en PUCP. |
| **A21** | Estado despliegue backend | ✅ | VM desplegada en PUCP. |
| **A22** | Diagrama Sc/Sa en bloques | ❌ | Crear diagrama (evidencia sustentación). |
| **A23** | Diagrama multi-navegador simulación 5D | ❌ | Crear diagrama (evidencia sustentación). |

**Conclusión A:** 13/17 son documentación/evidencia (no código). Solo A10/A13/A20/A21 están cubiertos.

---

### SECCIÓN B — CONFIGURACIÓN (8 requeridos, 0 opcionales, 8 N/A)

| Item | Descripción | Estado | Cómo se cubre / Brecha |
|------|-------------|--------|----------------------|
| **B01** | Explicar configuración del mapa | ⚠️ | El mapa se configura vía botones Mapa/Satelital + zoom. Explicación textual implícita en UI. |
| **B02** | Mantenimiento almacenes principales | N/A | Marcado No. |
| **B03** | Mantenimiento aeropuertos (ubicación, capacidad) | ✅ | Importación desde archivo oficial + GET/PUT AirportController. |
| **B04** | Mantenimiento UT/vuelos (ubicación, capacidad) | ✅ | Importación desde planes_vuelo.txt + GET/PUT FlightController. |
| **B05-B10** | Bloqueos y averías tipo 1-4 | N/A | Marcado No. |
| **B11** | Mantenimiento tramos (origen, destino, horarios) | ✅ | Importación desde planes_vuelo.txt. |
| **B12** | Mantenimiento cancelaciones | N/A | Marcado No. |
| **B13** | Explicar carga de datos (con/sin RDBMS) | ✅ | Página /import + endpoint dataset status. |
| **B14** | Fechas/horas usadas para pruebas | ✅ | Input datetime-local en panel de configuración. |
| **B15** | Datos proporcionados sin reducción | ✅ | Modo full import (9,519,995 envíos). |
| **B16** | Carga independiente de escenarios | ✅ | Importación separada antes de simular. |

**Conclusión B:** 7/8 cubiertos. B01 requiere texto de ayuda.

---

### SECCIÓN C — MAPA BASE (31 requeridos, 0 opcionales, 4 N/A)

| Item | Descripción | Estado | Cómo se cubre / Brecha |
|------|-------------|--------|----------------------|
| **C01** | Carga datos en otra opción | ✅ | Página /import separada. |
| **C02** | Pide fecha/hora a nivel minuto | ✅ | Input datetime-local con step=60. |
| **C03** | Tiempo hasta mostrar mapa | ❌ | Medir y documentar. |
| **C04** | Tiempo hasta que transporte se mueva | ❌ | Medir y documentar. |
| **C05-C06** | Visualizadores independientes | N/A | Marcado No (pero G06 sí aplica). |
| **C07** | UI todo español o todo inglés | ❌ | **MEZCLADO.** Títulos y labels en español; props, tooltips, placeholders mezclan inglés. |
| **C08** | Zoom in/out adecuado | ✅ | NavigationControl + scroll zoom. |
| **C09** | Pantalla principal completa al iniciar | ✅ | Mapa + paneles + KPIs. |
| **C10** | Todos los aeropuertos simultáneamente | ✅ | 30 aeropuertos renderizados. |
| **C11** | Pantalla limpia (elementos no disponibles ocultos) | ⚠️ | Paneles colapsables pero botones no deshabilitados apropiadamente. |
| **C12** | Fecha-hora simulada a nivel minuto (5D/colapso) | ⚠️ | `formatSimTime` muestra DD/MM/AAAA HH:mm:ss. OK para 5D, para colapso igual. |
| **C13** | Tiempo transcurrido del momento simulado | ❌ | **No implementado.** No hay contador de elapsed simulated time. |
| **C14** | Fecha-hora actual/presente | ✅ | Reloj Perú en modo live. |
| **C15** | Tiempo transcurrido hasta momento actual | ❌ | **No implementado.** No hay contador elapsed real time. |
| **C16** | Datos de tiempo bien ubicados/tamaño/contraste | ⚠️ | Reloj centrado arriba del mapa. Tamaño adecuado. Falta C13 y C15. |
| **C17** | Aeropuerto en ubicación prevista | ✅ | Coordenadas del dataset oficial. |
| **C18** | Ícono aeropuerto tamaño adecuado | ✅ | 10px dot. |
| **C19** | Ícono representa aeropuerto | ⚠️ | Punto genérico, no ícono distintivo de aeropuerto. |
| **C20** | Ícono contrasta con mapa | ✅ | Colores brillantes sobre fondo oscuro. |
| **C21** | Semáforo+vacío según stock | ✅ | Verde/ámbar/rojo según `status`. |
| **C22** | Ocupación en número o % | ✅ | Popup muestra `occupancyPct`. |
| **C23** | UT en ubicación prevista | ✅ | Posición interpolada. |
| **C24** | Ícono UT tamaño adecuado | ✅ | 24x24 SVG. |
| **C25** | Ícono representa avión | ✅ | SVG de avión. |
| **C26** | Ícono UT contrasta | ✅ | Colores brillantes. |
| **C27** | Semáforo UT según ocupación | ✅ | `PlaneIcon` fill: verde/ámbar/rojo según `loadPct`. |
| **C28** | Ocupación UT en número o % | ✅ | Popup muestra `loadPct`. |
| **C29** | UT se desplaza con fluidez, sin saltos | ✅ | `requestAnimationFrame` + interpolación. |
| **C30** | UT alineada al movimiento | ✅ | `computeBearing` rotación. |
| **C31** | Tramo origen-destino como línea al inicio | ✅ | `FlightTrajectoryLayer`. |
| **C32** | Ruta completa del avión como línea | N/A | Marcado No. |
| **C33** | Grosor/ideografía adecuada del tramo | ✅ | Líneas delgadas con opacidad. |
| **C34** | Línea se borra/cambia tras ser recorrida | ⚠️ | Parcial. Se dibujan tramos pendientes vs completados con distinto estilo. |
| **C35** | Avión en tierra cuando no vuela | N/A | Marcado No. |

**Conclusión C:** 24/31 implementados. Brechas: C07, C13, C15, C19 menor, C34 menor.

---

### SECCIÓN D — CIRCUNSTANCIAS (2 requeridos, 0 opcionales, 13 N/A)

| Item | Descripción | Estado | Cómo se cubre / Brecha |
|------|-------------|--------|----------------------|
| **D01-D13** | Bloqueos/averías/futuro | N/A | Todo marcado No. |
| **D14** | Cancelación visible en mapa | ❌ | **No implementado.** Backend soporta cancelación (FlightController) pero no hay representación visual en el mapa. |
| **D15** | Cancelación visible tiempo previsto | ❌ | No implementado. |

**Conclusión D:** 0/2 implementados. Hay que agregar representación visual de vuelos cancelados.

---

### SECCIÓN E — PANEL (16 requeridos, 18 opcionales, 2 N/A)

| Item | Descripción | Estado | Cómo se cubre / Brecha |
|------|-------------|--------|----------------------|
| **E01** | Paneles contraídos/colapsados al inicio | ✅ | localStorage persiste estado colapsado. |
| **E02** | Lista UT con ocupación número/% | ✅ | Página /flights con tabla. |
| **E03** | Desde UT, acceso a envíos que traslada | ✅ | Detalle de vuelo muestra shipments asignados. |
| **E04** | Desde UT, acceso a maletas que traslada | ✅ | Mismo que E03 (maletas = envíos). |
| **E05** | Stock actual UT como semáforo-vacío | ✅ | loadPct coloreado en tabla. |
| **E06** | Buscar UT por código/tramo | ✅ | Input de búsqueda en /flights. |
| **E07** | Buscar UT por origen | ✅ | Filtro origen en /flights. |
| **E08** | Buscar UT por destino | ✅ | Filtro destino en /flights. |
| **E09** | Filtrar UT por código/patrón | Op | Filtro code exists. |
| **E10** | Filtrar UT por origen | Op | Filtro origen exists. |
| **E11** | Filtrar UT por destino | Op | Filtro destino exists. |
| **E12** | Ordenar UT por ocupación | ✅ | Sort param en API. |
| **E13** | Ordenar UT por salida | Op | Sort param. |
| **E14** | Ordenar UT por llegada | Op | Sort param. |
| **E15** | Ordenar UT por origen | Op | Sort param. |
| **E16** | Ordenar UT por destino | Op | Sort param. |
| **E17** | Lista aeropuertos con ocupación número/% | ❌ | **No implementado en panel principal.** Solo en popup de mapa y página /flights. |
| **E18** | Lista aeropuertos con acceso a envíos en almacén | ❌ | **No implementado.** NodeDetailDto tiene la data pero no hay panel. |
| **E19** | Lista aeropuertos acceso a maletas | Op | No implementado. |
| **E20** | Lista aeropuertos con semáforo-vacío | ❌ | En el mapa sí (C21), en panel no. |
| **E21** | Info planificada envíos que entran | ❌ | **No implementado.** |
| **E22** | Info planificada maletas que entran | Op | No implementado. |
| **E23** | Info planificada envíos que salen | ❌ | **No implementado.** |
| **E24** | Info planificada maletas que salen | Op | No implementado. |
| **E25** | Filtrar aeropuertos por código | Op | No implementado. |
| **E26** | Filtrar aeropuertos por continente | Op | AirportController.getByContinent existe. |
| **E27** | Ordenar aeropuertos por ocupación | ❌ | **No implementado.** |
| **E28** | Ordenar aeropuertos por próxima salida | Op | No implementado. |
| **E29** | Ordenar aeropuertos por próxima llegada | Op | No implementado. |
| **E30** | Lista planificada envíos a transportar | ❌ | **No implementado.** |
| **E31** | Envíos en vuelos con origen/destino/UT/cantidad | Op | No implementado. |
| **E32** | Envíos entregados últimas 4h | Op | No implementado. |
| **E33** | Filtrar envíos por origen | Op | shipsments page tiene filtro origen. |
| **E34** | Filtrar UT por destino en tramo/ruta | Op | Filtro destino exists. |
| **E35-E36** | Futuro | N/A | Marcado No. |

**Conclusión E:** 8/16 REQUERIDOS implementados. **8 REQUERIDOS faltan**: E17, E18, E20, E21, E23, E27, E30 (críticos).

---

### SECCIÓN F — VINCULACIÓN MAPA-PANEL (9 requeridos, 4 opcionales, 5 N/A)

| Item | Descripción | Estado | Cómo se cubre / Brecha |
|------|-------------|--------|----------------------|
| **F01** | Ruta maleta por ID en mapa | ⚠️ | Envíos renderizados como markers en mapa. Falta línea de ruta (polilínea). |
| **F02** | Ruta con datos de escalas | Op | ShipmentDetail tiene stops/legs. |
| **F03** | Ruta envío por ID en mapa | ⚠️ | Mismo que F01. |
| **F04** | Ruta con escalas | Op | Mismo que F02. |
| **F05** | Seleccionar aeropuerto en panel → enfocar mapa | ❌ | **No implementado.** No hay panel de aeropuertos. |
| **F06** | Seleccionar aeropuerto en mapa → enfocar panel | ❌ | **No implementado.** Popup existe pero no hay panel que se enfoque. |
| **F07** | Seleccionar UT en panel → enfocar mapa | ❌ | **No implementado.** /flights no tiene botón "Ver en mapa". |
| **F08** | Seleccionar UT en mapa → enfocar panel | ❌ | **No implementado.** Popup tiene link a /flights pero no hay selección global. |
| **F09** | Seleccionar envío en panel → enfocar mapa | ❌ | **No implementado.** shipments page no usa `?selected=`. |
| **F10** | Lista/vinculación bloqueos | N/A | Marcado No. |
| **F11-F14** | Vinculación averías | N/A | Marcado No. |
| **F15** | Filtrar por semáforo aeropuertos → reflejar mapa | ❌ | **No implementado.** |
| **F16** | Filtrar por semáforo UT → reflejar mapa | ❌ | **No implementado.** |
| **F17** | Otros filtros aeropuertos → mapa | Op | No. |
| **F18** | Otros filtros UT → mapa | Op | No. |

**Conclusión F:** 0/9 REQUERIDOS completamente implementados. Todos los items F son brecha crítica.

---

### SECCIÓN G — CIERRE (16 requeridos, 0 opcionales, 3-4 N/A)

| Item | Descripción | Estado | Cómo se cubre / Brecha |
|------|-------------|--------|----------------------|
| **G01** | Indicador global ocupación flota UT | ❌ | **No implementado.** KPIs individuales existen (vuelos visibles, próximos vuelos) pero no ocupación global de flota. |
| **G02** | Indicador flota como semáforo | ❌ | No implementado. |
| **G03** | Indicador global ocupación aeropuertos | ❌ | **No implementado.** KPIs tienen "Nodos críticos" pero no ocupación global. |
| **G04** | Indicador aeropuertos como semáforo | ❌ | No implementado. |
| **G05** | Tiempo mínimo permanencia maletas | ✅ | `MIN_CONNECTION_MINUTES = 30` en RoutePlanningSupport. |
| **G06** | 2+ visualizadores/navegadores, interacción independiente, único planificador 5D | ✅ | Arquitectura REST + polling lo permite. Múltiples pestañas/navegadores funcionan. |
| **G07** | Manejo bloqueos en planificador | N/A | Marcado No. |
| **G08** | Reporte última planificación estable → fin PERIOD_SIMULATION | ❌ | **No implementado.** Reporte SLA existe pero no es reporte de cierre de corrida. |
| **G09** | Reporte última planificación estable → cierre DAY_TO_DAY | ❌ | No implementado. |
| **G10** | Reporte última planificación estable → fin COLLAPSE_TEST | ❌ | No implementado. |
| **G11** | Percepción: completo/al día | ❌ | Subjetivo (evaluador). |
| **G12** | Percepción: apropiado | ❌ | Subjetivo. |
| **G13** | Percepción: claro/no ambiguo | ❌ | Subjetivo. |
| **G14** | Percepción: factible en plazo | ❌ | Subjetivo. |
| **G15-G16** | Verificable/conformidad | N/A | Marcado No. |
| **G17** | Fortalezas | ❌ | Documentar. |
| **G18** | Aspectos a mejorar | ❌ | Documentar. |
| **G19** | Potencial invitación XpoSTEM | ❌ | Documentar. |

**Conclusión G:** 2/16 implementados (G05, G06). 14 REQUERIDOS faltan.

---

## 2. MAPA DE BRECHAS (priorizado)

### Brechas P0 — Críticas para nota

| Sección | Items | Esfuerzo | Impacto |
|---------|-------|----------|---------|
| **C07** | Todo español o todo inglés | Bajo | Medio |
| **C13, C15** | Tiempo transcurrido simulado y real | Bajo | Medio |
| **E17, E20, E27** | Lista aeropuertos con ocupación y semáforo | Medio | Alto |
| **E18, E21, E23** | Acceso envíos en aeropuerto, entradas/salidas planificadas | Medio | Alto |
| **E30** | Lista planificada envíos a transportar | Medio | Alto |
| **F01, F03** | Ruta de envío/maleta por ID en mapa (polilínea) | Bajo | Alto |
| **F05-F09** | Vinculación bidireccional mapa-panel | Alto | Muy alto |
| **F15-F16** | Filtros semáforo en panel → mapa | Medio | Alto |
| **G01-G04** | Indicadores globales de ocupación | Medio | Alto |
| **G08-G10** | Reporte de última planificación estable por escenario | Alto | Muy alto |
| **D14-D15** | Cancelaciones visibles en mapa | Bajo | Medio |

### Brechas P1 — Importantes

| Sección | Items | Esfuerzo |
|---------|-------|----------|
| **A01-A03** | Versión, fecha, comentarios | Bajo |
| **A11, A14, A17-A19** | Métricas Ta, Sa, Sc | Medio |
| **A22-A23** | Diagramas Sc/Sa y multi-navegador | Bajo |
| **B01** | Texto explicativo configuración mapa | Bajo |
| **C03, C04** | Medir tiempos de inicio | Bajo |
| **C19** | Ícono aeropuerto más representativo | Bajo |
| **C34** | Mejorar cambio de línea de tramo | Bajo |

---

## 3. CÓDIGO A ELIMINAR (funcionalidades que NO aplican)

### 3.1 Backend — Servicios completos a REMOVER

| Archivo | Líneas | Razón |
|---------|--------|-------|
| `FutureDemandProjectionService.java` | 609 | Proyección demanda futura a 2030. La rúbrica NO pide generar demanda sintética. El proyecto ya decidió eliminarlo (AGENTS.md). |
| `DemandGenerationService.java` | 250 | Generación sintética de demanda. Misma razón que arriba. |
| `BenchmarkTuningService.java` | 1026 | Benchmark y tuneo de algoritmos GA/ACO. No es requisito de rúbrica. Además puede resetear datos. |
| `BenchmarkJobService.java` | 159 | Orquestador de benchmark. No se invoca desde UI. |
| `AlgorithmProfileService.java` | 54 | Configuración de parámetros de algoritmos. No se usa en runtime real. |
| `AlgorithmRaceService.java` | 93 | Reporte de carrera GA vs ACO. Datos placeholders. No es requisito. |

### 3.2 Backend — DTOs a REMOVER

| DTO | Asociado a servicio muerto |
|-----|---------------------------|
| `DemandGenerationRequestDto.java` | DemandGenerationService |
| `DemandGenerationResultDto.java` | DemandGenerationService |
| `FutureDemandGenerationRequestDto.java` | FutureDemandProjectionService |
| `FutureDemandGenerationResultDto.java` | FutureDemandProjectionService |
| `BenchmarkMetricsDto.java` | AlgorithmRaceService |
| `AlgorithmRaceReportDto.java` | AlgorithmRaceService |

### 3.3 Backend — Endpoints a REMOVER o DESHABILITAR

| Endpoint | Controller | Acción |
|----------|-----------|--------|
| `POST /api/simulation/seed-statistical` | SimulationController | REMOVER (no expuesto en UI) |
| `GET /api/simulation/initial-volume-samples` | SimulationController | REMOVER (debug, no se usa) |
| `GET /api/simulation/race-report` | SimulationController | REMOVER o simplificar (datos placeholders) |
| `POST /api/simulation/reset-demand` | SimulationController | MANTENER pero proteger con confirmación |

### 3.4 Frontend — Componentes a REMOVER

| Archivo | Líneas | Razón |
|---------|--------|-------|
| `components/FlightMapLayer.tsx` | 263 | Capa MapLibre con sprites SVG. Reemplazado por marcadores `PlaneIcon` inline en `OperationsDashboard.tsx`. NO se importa en ningún archivo activo. |

### 3.5 Backend — Corrección de configuración forzada

En `SimulationController.configure()`:
```java
config.setPrimaryAlgorithm(AlgorithmType.GENETIC);
config.setSecondaryAlgorithm(AlgorithmType.GENETIC);
```
Estas líneas IGNORAN el valor enviado por el frontend. **CORREGIR** para que respete `dto.primaryAlgorithm()` / `dto.secondaryAlgorithm()`. O alternativamente, quitar la selección de algoritmo del frontend si GA es el único planificador operativo.

### 3.6 Código contradictorio sobre colapso

- Comentarios en `CollapseMonitorService.java` y `Airport.java` que hablan de colapso por "saturación de almacenes". La implementación real usa "primer envío que no llega a tiempo". **ALINEAR** comentarios y documentación.

---

## 4. PLAN DE IMPLEMENTACIÓN (orden priorizado)

### Fase 0 — Docker local funcional (inmediato)

1. Agregar `NEXT_PUBLIC_API_URL` configurable por entorno en docker-compose
2. Crear `docker-compose.override.yml` para desarrollo local
3. Documentar en README cómo levantar local vs producción

### Fase 1 — QUITAR código muerto

1. Eliminar servicios: FutureDemandProjectionService, DemandGenerationService, BenchmarkTuningService, BenchmarkJobService, AlgorithmProfileService, AlgorithmRaceService
2. Eliminar DTOs huérfanos asociados
3. Eliminar FlightMapLayer.tsx (no se usa)
4. Eliminar endpoints no expuestos de SimulationController
5. Corregir configure() para que respete primaryAlgorithm/secondaryAlgorithm
6. Alinear comentarios contradictorios sobre definición de colapso

### Fase 2 — Agregar brechas P0 de mapa y tiempo

1. **C07**: Estandarizar toda la UI a español (props, placeholders, tooltips, aria-labels)
2. **C13**: Agregar contador "Tiempo transcurrido simulado" (elapsed simulated time from start)
3. **C15**: Agregar contador "Tiempo transcurrido real" (wall clock elapsed)
4. **C16**: Reubicar/adjustar tiempos en el mapa para que los 4 estén visibles
5. **F01/F03**: Agregar polilínea de ruta del envío seleccionado (usar ShipmentDetail.stops)
6. **D14/D15**: Agregar representación visual de vuelos cancelados en el mapa (color gris/rojo, tooltip)

### Fase 3 — Panel de aeropuertos en el dashboard principal

1. **E17, E20, E27**: Agregar lista colapsable de aeropuertos con:
   - Ocupación número + %
   - Semáforo (verde/ámbar/rojo)
   - Ordenable por ocupación
2. **E18**: Al hacer clic en aeropuerto, mostrar envíos almacenados, en tránsito y destino final
3. **E21, E23**: Mostrar entradas y salidas planificadas
4. Backend: Endpoint liviano de envíos por aeropuerto si NodeDetailDto no alcanza

### Fase 4 — Vinculación bidireccional mapa-panel

1. **F05-F09**: Estado global de selección en SimulationContext:
   - `selectedAirportIcao`, `selectedFlightId`, `selectedShipmentId`
   - Panel → mapa: flyTo + highlight
   - Mapa → panel: abrir detalle + resaltar fila
2. **F07/F08**: Botón "Ver en mapa" desde detalle de vuelo
3. **F09**: Hacer funcionar `/shipments?selected=<id>` (agregar useSearchParams en shipments/page.tsx)
4. **F15/F16**: Filtros por semáforo que afecten renderizado del mapa

### Fase 5 — Reporte de cierre y planificación estable

1. **G01-G04**: Agregar indicadores globales:
   - Ocupación promedio de flota (como semáforo)
   - Ocupación promedio de aeropuertos (como semáforo)
2. **G08-G10**: Reporte de última planificación estable:
   - Dashboard post-simulación con resumen
   - Envíos planificados, entregados, tarde, críticos
   - Ocupación promedio/max
   - Envío de colapso si aplica
   - Export CSV/PDF

### Fase 6 — Evidencia y documentación (A01-A23)

1. Preparar lámina/documento con:
   - Versión, fecha, equipo
   - Algoritmo planificador y tecnologías visualizador
   - Ta, Sa, Sc (métricas capturadas)
   - Estado de despliegue frontend/backend
   - Diagrama Sc/Sa
   - Diagrama multi-navegador
2. Agregar versión y fecha en UI (footer o sidebar)

### Fase 7 — Mejoras opcionales (si queda tiempo)

1. E09-E11: Filtros por código/origen/destino
2. E13-E16: Ordenamientos por hora
3. E19, E22, E24: Acceso a maletas individuales
4. E31-E32: Envíos en vuelo y entregados
5. E25-E26: Filtros de aeropuertos
6. E28-E29: Ordenamientos por próxima salida/llegada

---

## 5. RESUMEN DE ESFUERZO

| Fase | Descripción | Archivos a modificar | Esfuerzo |
|------|-------------|---------------------|----------|
| 0 | Docker local | docker-compose.yml, README | Bajo |
| 1 | Quitar código muerto | ~12 archivos backend, 1 frontend | Medio |
| 2 | Brechas mapa/tiempo/C07 | OperationsDashboard.tsx, globals.css | Medio |
| 3 | Panel aeropuertos | OperationsDashboard.tsx, DashboardController.java | Medio-Alto |
| 4 | Vinculación bidireccional | SimulationContext.tsx, OperationsDashboard.tsx, flights/page.tsx, shipments/page.tsx | Alto |
| 5 | Reporte cierre | /reports, SimulationExportService, SimulationController | Alto |
| 6 | Evidencia AXX | Lámina/PPT, footer UI | Bajo |
| 7 | Opcionales | Varios | Bajo-Medio |

**Total brechas P0-P1:** ~25 items
**Código a eliminar:** ~2,200 líneas de backend, ~260 líneas de frontend

---

## 6. RIESGOS

1. Al eliminar servicios muertos, verificar que no haya `@MockBean` en tests que los referencie
2. Al eliminar `FutureDemandProjectionService`, revisar `SimulationConfig` campos `projectedDemandReady`, `projectedFrom`, `projectedTo` — pueden quedar como campos inertes en la entidad
3. Al eliminar endpoints, verificar que Swagger/docs no los referencie
4. `SchemaPatchService` ejecuta parches SQL manuales; revisar si siguen siendo necesarios con `ddl-auto=update`
5. 33 archivos modificados + 7 untracked en working tree — gestionar commits antes de cambios mayores
