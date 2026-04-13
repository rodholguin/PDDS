#!/usr/bin/env python3
"""
Genera PDFs de entregables academicos sin dependencias externas.
Salida:
  - documentacion/01.definicion.prototipo.v03.pdf
  - documentacion/12.ana.casos.uso.v01.pdf
  - documentacion/13.ana.modelo.clases.v01.pdf
  - documentacion/14.ana.secuencias.escenarios.v01.pdf
  - documentacion/15.ana.reglas.negocio.v01.pdf
  - documentacion/16.ana.trazabilidad.lee.v01.pdf
  - documentacion/21.dis.selec.algoritmos.v02.pdf
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
import textwrap


@dataclass
class PdfDocument:
    filename: str
    title: str
    lines: list[str]


TEAM = [
    "A1: Alonso Reyes Samaniego",
    "A2: Rodrigo Holguin Huari",
    "A3: Sebastian Badajoz Garcia Godos",
    "A4: Adrian Picoy Cotrina",
]


def wrap_lines(text: str, width: int = 102) -> list[str]:
    out: list[str] = []
    for paragraph in text.split("\n"):
        p = paragraph.rstrip()
        if not p:
            out.append("")
            continue
        if p.startswith("    "):
            out.append(p)
            continue
        wrapped = textwrap.wrap(
            p,
            width=width,
            break_long_words=False,
            break_on_hyphens=False,
            replace_whitespace=False,
        )
        out.extend(wrapped or [""])
    return out


def esc_pdf(s: str) -> str:
    return s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")


def build_pdf(path: Path, title: str, lines: list[str]) -> None:
    page_width = 612
    page_height = 792
    margin_x = 45
    margin_top = 64
    margin_bottom = 55
    font_size = 10
    leading = 14
    usable_height = page_height - margin_top - margin_bottom
    lines_per_page = max(1, usable_height // leading)

    pages: list[list[str]] = []
    i = 0
    while i < len(lines):
        pages.append(lines[i : i + lines_per_page])
        i += lines_per_page
    if not pages:
        pages = [[""]]

    objects: list[bytes] = []

    def add_obj(payload: str | bytes) -> int:
        if isinstance(payload, str):
            data = payload.encode("latin-1", errors="replace")
        else:
            data = payload
        objects.append(data)
        return len(objects)

    font_id = add_obj("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")

    content_ids: list[int] = []
    page_ids: list[int] = []

    for page_lines in pages:
        text_cmds = ["BT", f"/F1 {font_size} Tf", f"{margin_x} {page_height - margin_top} Td", f"{leading} TL"]
        for line in page_lines:
            text_cmds.append(f"({esc_pdf(line)}) Tj")
            text_cmds.append("T*")
        text_cmds.append("ET")
        stream = "\n".join(text_cmds)
        content = f"<< /Length {len(stream.encode('latin-1', errors='replace'))} >>\nstream\n{stream}\nendstream"
        content_id = add_obj(content)
        content_ids.append(content_id)

    pages_id = add_obj("<< /Type /Pages /Kids [] /Count 0 >>")

    for content_id in content_ids:
        page_obj = (
            "<< /Type /Page "
            f"/Parent {pages_id} 0 R "
            f"/MediaBox [0 0 {page_width} {page_height}] "
            f"/Resources << /Font << /F1 {font_id} 0 R >> >> "
            f"/Contents {content_id} 0 R >>"
        )
        page_ids.append(add_obj(page_obj))

    kids = " ".join(f"{pid} 0 R" for pid in page_ids)
    objects[pages_id - 1] = (
        f"<< /Type /Pages /Kids [{kids}] /Count {len(page_ids)} >>".encode("latin-1")
    )

    info_id = add_obj(
        "<< "
        f"/Title ({esc_pdf(title)}) "
        f"/Author ({esc_pdf(', '.join(TEAM))}) "
        f"/Creator (PDDS Deliverable Generator) "
        f"/Producer (Python Minimal PDF Writer) "
        f"/CreationDate (D:{datetime.utcnow().strftime('%Y%m%d%H%M%S')}Z) "
        ">>"
    )

    catalog_id = add_obj(f"<< /Type /Catalog /Pages {pages_id} 0 R >>")

    out = bytearray()
    out.extend(b"%PDF-1.4\n")
    xref_positions = [0]
    for idx, obj in enumerate(objects, start=1):
        xref_positions.append(len(out))
        out.extend(f"{idx} 0 obj\n".encode("latin-1"))
        out.extend(obj)
        out.extend(b"\nendobj\n")

    xref_start = len(out)
    out.extend(f"xref\n0 {len(objects) + 1}\n".encode("latin-1"))
    out.extend(b"0000000000 65535 f \n")
    for pos in xref_positions[1:]:
        out.extend(f"{pos:010d} 00000 n \n".encode("latin-1"))

    trailer = (
        "trailer\n"
        f"<< /Size {len(objects) + 1} /Root {catalog_id} 0 R /Info {info_id} 0 R >>\n"
        "startxref\n"
        f"{xref_start}\n"
        "%%EOF\n"
    )
    out.extend(trailer.encode("latin-1"))
    path.write_bytes(out)


def doc_01() -> PdfDocument:
    text = f"""
01.definicion.prototipo.v03

Proyecto: Plataforma de Distribucion y Deteccion de Saturacion (PDDS)
Version: v03
Fecha: {datetime.now().strftime('%Y-%m-%d')}

Equipo:
- {TEAM[0]}
- {TEAM[1]}
- {TEAM[2]}
- {TEAM[3]}

1. Objetivo del prototipo

Este prototipo valida funcionalidades esenciales de LE exigibles (LE tipo E):
monitoreo operacional en mapa, gestion de envios, simulacion en 3 escenarios,
y reporte SLA operacional. Se excluye benchmark visual de algoritmos por no
ser funcionalidad operativa obligatoria al usuario final.

2. Alcance funcional esencial (segun LE exigibles)

- LE-001..LE-028 (Monitoreo):
  - Panel principal con mapa operativo.
  - Visualizacion de envios y vuelos en ruta.
  - KPIs de estado y alertas.
  - Filtros y busqueda de envios.

- LE-029..LE-044 (Gestion de envios):
  - Registro/importacion de envios.
  - Planificacion de ruta y estados del envio.
  - Consulta de detalle y trazabilidad de paradas.

- LE-049..LE-057 (Simulacion):
  - Configuracion y ejecucion de escenarios.
  - Controles de iniciar, pausar, detener y velocidad.
  - Exportacion de resultados operativos (CSV/PDF).

- LE-074 (Reportes):
  - Cumplimiento SLA por tipo de ruta, cliente y destino.

3. Escenarios implementados

3.1 Operacion dia a dia (DAY_TO_DAY)
- Proposito: monitorear flujo continuo de envios en tiempo casi real.
- Evidencia esperada en UI:
  - Mapa con envios IN_ROUTE y rutas activas.
  - KPIs de envios activos, alertas y entregas.
  - Control de velocidad para acelerar visualizacion de ticks.

3.2 Simulacion de periodo (PERIOD_SIMULATION)
- Proposito: reproducir comportamiento de varios dias en ventana controlada.
- Evidencia esperada en UI:
  - Escenario seleccionado en panel y mapa.
  - Incremento de backlog/entregas por avance temporal.
  - Actualizacion de SLA y at-risk shipments.

3.3 Simulacion de colapso logistico (COLLAPSE_TEST)
- Proposito: medir riesgo de saturacion y cuellos de botella.
- Evidencia esperada en UI:
  - Indicadores de riesgo de colapso.
  - Lista de nodos cuello de botella.
  - Persistencia de operacion con alertas criticas.

4. Funcionalidades operativas transversales

- Importacion oficial de envios por lote completo:
  - Endpoint asincrono de carga full de carpeta datos/envios.
  - Resultado consistente: leidos = importados + fallidos.

- Estado y control de simulacion:
  - GET /api/simulation/state
  - POST /api/simulation/start
  - POST /api/simulation/pause
  - POST /api/simulation/stop
  - POST /api/simulation/speed

- Reportes y export:
  - GET /api/reports/sla-compliance
  - GET /api/simulation/results/export?format=csv|pdf

5. Evidencias minimas a adjuntar en entrega

E01: Mapa escenario DAY_TO_DAY con envios en ruta.
E02: Mapa escenario PERIOD_SIMULATION activo.
E03: Mapa escenario COLLAPSE_TEST + riesgo colapso.
E04: Import oficial DONE con consistencia de filas.
E05: Reporte SLA por ruta/cliente/destino.
E06: Export CSV y PDF generado.

6. Criterios de aceptacion del prototipo v03

- Se visualizan los 3 escenarios en mapa.
- La operacion principal no depende de benchmark visual.
- El usuario final no requiere conocer algoritmo ganador.
- La informacion mostrada corresponde a LE exigibles.

7. Control de calidad documental (doble barrido)

Barrido 1 (cobertura LE):
- Verificado que todas las funciones descritas pertenecen a LE exigibles.
- Eliminadas referencias de UI no esencial (race/benchmark).

Barrido 2 (consistencia tecnica):
- Verificados endpoints vigentes con frontend y backend actuales.
- Verificada presencia de los 3 escenarios y reporte SLA.
"""
    return PdfDocument(
        filename="01.definicion.prototipo.v03.pdf",
        title="Definicion del Prototipo v03",
        lines=wrap_lines(text.strip()),
    )


def doc_12_uc() -> PdfDocument:
    text = f"""
12.ana.casos.uso.v01

Proyecto: PDDS
Documento de Analisis: Casos de Uso
Fecha: {datetime.now().strftime('%Y-%m-%d')}

Equipo:
- {TEAM[0]}
- {TEAM[1]}
- {TEAM[2]}
- {TEAM[3]}

1. Alcance

Este documento describe casos de uso esenciales de LE exigibles:
monitoreo, gestion de envios, simulacion y reportes SLA.

2. Actores

- Operador de simulacion:
  Configura escenario, ejecuta simulacion, monitorea mapa y responde alertas.

- Analista operacional:
  Consulta indicadores, verifica cumplimiento SLA y exporta resultados.

3. Catalogo de casos de uso

CU-01 Configurar escenario de simulacion.
CU-02 Iniciar/pausar/detener simulacion.
CU-03 Monitorear envios y vuelos en mapa.
CU-04 Importar envios oficiales.
CU-05 Consultar detalle de envio y paradas.
CU-06 Gestionar envio manual (crear y marcar entrega).
CU-07 Consultar reporte SLA.
CU-08 Exportar resultados operativos CSV/PDF.

4. Especificacion de casos de uso (plantilla UML)

CU-01 Configurar escenario de simulacion
- Actor principal: Operador de simulacion.
- Precondiciones:
  - Sistema disponible.
  - Simulacion detenida.
- Flujo principal:
  1) El actor selecciona escenario (DAY_TO_DAY, PERIOD_SIMULATION o COLLAPSE_TEST).
  2) Ajusta parametros (dias, ventanas, capacidades, umbrales).
  3) Solicita guardar configuracion.
  4) Sistema valida y persiste configuracion.
- Flujos alternos:
  A1) Si simulacion esta corriendo, sistema rechaza reconfiguracion.
- Postcondiciones:
  - Configuracion activa queda registrada para ejecucion.

CU-02 Iniciar/Pausar/Detener simulacion
- Actor principal: Operador de simulacion.
- Precondiciones:
  - Configuracion valida disponible.
- Flujo principal:
  1) Actor inicia simulacion.
  2) Sistema marca estado RUNNING y arranca ticks.
  3) Actor puede pausar o cambiar velocidad.
  4) Actor detiene simulacion.
  5) Sistema marca estado STOPPED.
- Flujos alternos:
  A1) Si ya esta corriendo, sistema responde estado actual.
  A2) Si no hay simulacion corriendo y se intenta pausar, sistema rechaza.
- Postcondiciones:
  - Estado runtime consistente y consultable.

CU-03 Monitorear envios y vuelos en mapa
- Actor principal: Operador de simulacion.
- Precondiciones:
  - Simulacion iniciada o datos cargados.
- Flujo principal:
  1) Actor abre dashboard.
  2) Sistema muestra mapa, capas de rutas y envios.
  3) Actor filtra por estado o texto.
  4) Actor selecciona envio para detalle.
  5) Sistema muestra paradas, estado y progreso.
- Postcondiciones:
  - Actor identifica envios at-risk, overdue o criticos.

CU-04 Importar envios oficiales
- Actor principal: Operador de simulacion.
- Precondiciones:
  - Carpeta de datos oficiales disponible.
- Flujo principal:
  1) Actor inicia carga completa de envios.
  2) Sistema crea job asincrono de importacion.
  3) Actor consulta estado del job.
  4) Sistema finaliza con resumen de consistencia.
- Postcondiciones:
  - Envios persistidos y listos para planificacion incremental.

CU-05 Consultar detalle de envio y paradas
- Actor principal: Operador de simulacion.
- Flujo principal:
  1) Actor busca envio por codigo o seleccion en mapa.
  2) Sistema devuelve cabecera del envio y travel stops.
  3) Actor revisa estado por tramo.
- Postcondiciones:
  - Trazabilidad operativa del envio disponible.

CU-06 Gestionar envio manual
- Actor principal: Operador de simulacion.
- Flujo principal:
  1) Actor registra envio manual.
  2) Sistema planifica ruta con algoritmo activo interno.
  3) Actor puede confirmar entrega manual si aplica.
- Postcondiciones:
  - Estado del envio actualizado y auditado.

CU-07 Consultar reporte SLA
- Actor principal: Analista operacional.
- Flujo principal:
  1) Analista selecciona rango de fechas.
  2) Sistema calcula cumplimiento por ruta, cliente y destino.
  3) Analista revisa desagregados.
- Postcondiciones:
  - Informe SLA para seguimiento operativo.

CU-08 Exportar resultados operativos
- Actor principal: Analista operacional.
- Flujo principal:
  1) Analista solicita export CSV o PDF.
  2) Sistema genera archivo con KPIs operativos.
  3) Analista descarga evidencia.

5. Requisitos de calidad de casos de uso

- Cada caso define actor, precondicion, flujo principal, alternos y postcondicion.
- Trazabilidad con LE exigibles incluida en documento 16.ana.trazabilidad.lee.v01.

6. Barrido de calidad

Barrido 1 (completitud):
- Todos los casos cubren acciones esenciales del usuario final.

Barrido 2 (consistencia):
- Flujos validados contra endpoints y pantallas actuales del sistema.
"""
    return PdfDocument(
        filename="12.ana.casos.uso.v01.pdf",
        title="Analisis de Casos de Uso",
        lines=wrap_lines(text.strip()),
    )


def doc_13_classes() -> PdfDocument:
    text = f"""
13.ana.modelo.clases.v01

Proyecto: PDDS
Documento de Analisis: Modelo de Clases
Fecha: {datetime.now().strftime('%Y-%m-%d')}

1. Objetivo

Definir el modelo de dominio UML para soporte de simulacion logistica,
planificacion de rutas y monitoreo operacional.

2. Clases principales del dominio

Airport
- id: Long
- icaoCode: String
- city: String
- country: String
- latitude: Double
- longitude: Double
- maxStorageCapacity: Integer
- currentStorageLoad: Integer

Flight
- id: Long
- flightCode: String
- originAirport: Airport
- destinationAirport: Airport
- scheduledDeparture: LocalDateTime
- scheduledArrival: LocalDateTime
- status: FlightStatus
- maxCapacity: Integer
- currentLoad: Integer

Shipment
- id: Long
- shipmentCode: String
- airlineName: String
- originAirport: Airport
- destinationAirport: Airport
- registrationDate: LocalDateTime
- deadline: LocalDateTime
- status: ShipmentStatus
- luggageCount: Integer
- progressPercentage: Double

TravelStop
- id: Long
- shipment: Shipment
- flight: Flight
- airport: Airport
- stopOrder: Integer
- stopStatus: StopStatus
- scheduledArrival: LocalDateTime
- actualArrival: LocalDateTime

SimulationConfig
- id: Long
- scenario: SimulationScenario
- simulationDays: Integer
- executionMinutes: Integer
- initialVolumeAvg: Integer
- initialVolumeVariance: Integer
- primaryAlgorithm: AlgorithmType
- secondaryAlgorithm: AlgorithmType
- isRunning: Boolean

OperationalAlert
- id: Long
- airport: Airport
- type: String
- status: OperationalAlertStatus
- createdAt: LocalDateTime

ShipmentAuditLog
- id: Long
- shipment: Shipment
- eventType: ShipmentAuditType
- message: String
- eventAt: LocalDateTime

3. Relaciones UML (resumen)

- Airport 1..* <-> Flight (como origen/destino)
- Shipment 1 -> 0..* TravelStop
- TravelStop * -> 1 Flight
- TravelStop * -> 1 Airport
- Shipment 1 -> 0..* ShipmentAuditLog
- SimulationConfig 1 (singleton operativo)
- Airport 1 -> 0..* OperationalAlert

4. Diagrama UML textual (PlantUML)

@startuml
class Airport {{
  +icaoCode: String
  +maxStorageCapacity: Integer
  +currentStorageLoad: Integer
}}

class Flight {{
  +flightCode: String
  +scheduledDeparture: LocalDateTime
  +scheduledArrival: LocalDateTime
  +status: FlightStatus
  +maxCapacity: Integer
  +currentLoad: Integer
}}

class Shipment {{
  +shipmentCode: String
  +registrationDate: LocalDateTime
  +deadline: LocalDateTime
  +status: ShipmentStatus
  +luggageCount: Integer
}}

class TravelStop {{
  +stopOrder: Integer
  +stopStatus: StopStatus
  +scheduledArrival: LocalDateTime
  +actualArrival: LocalDateTime
}}

class SimulationConfig {{
  +scenario: SimulationScenario
  +simulationDays: Integer
  +executionMinutes: Integer
  +primaryAlgorithm: AlgorithmType
  +isRunning: Boolean
}}

class OperationalAlert
class ShipmentAuditLog

Airport "1" <-- "*" Flight : origin
Airport "1" <-- "*" Flight : destination
Shipment "1" --> "*" TravelStop
Flight "1" <-- "*" TravelStop
Airport "1" <-- "*" TravelStop
Shipment "1" --> "*" ShipmentAuditLog
Airport "1" --> "*" OperationalAlert
@enduml

5. Reglas de consistencia del modelo

- Un Shipment sin TravelStop planificado permanece en PENDING.
- Un Flight no puede exceder maxCapacity.
- El progreso de Shipment depende de TravelStops completados.
- SimulationConfig es unica y gobierna escenario activo.

6. Barrido de calidad

Barrido 1 (cohesion de dominio):
- Clases y relaciones representan entidades reales del problema logistico.

Barrido 2 (trazabilidad tecnica):
- Clases validadas contra modelos y servicios backend implementados.
"""
    return PdfDocument(
        filename="13.ana.modelo.clases.v01.pdf",
        title="Analisis de Modelo de Clases",
        lines=wrap_lines(text.strip()),
    )


def doc_14_sequences() -> PdfDocument:
    text = f"""
14.ana.secuencias.escenarios.v01

Proyecto: PDDS
Documento de Analisis: Secuencias UML de Escenarios
Fecha: {datetime.now().strftime('%Y-%m-%d')}

1. Objetivo

Documentar secuencias clave del sistema en escenarios operativos exigibles.

2. Participantes comunes

Actor Operador
Frontend (Dashboard/Simulation)
SimulationController
SimulationRuntimeService
SimulationEngineService
RoutePlannerService
Repositories (Shipment/Flight/TravelStop)

3. Secuencia S1: Configurar e iniciar simulacion

Flujo:
1) Operador selecciona escenario y parametros en Frontend.
2) Frontend -> SimulationController.configure(dto).
3) Controller valida "simulacion detenida" y persiste config.
4) Operador presiona iniciar.
5) Frontend -> SimulationController.start().
6) Controller marca config.isRunning = true y runtime.markStarted().
7) Frontend consulta state periodicamente.

PlantUML:
@startuml
actor Operador
participant Frontend
participant SimulationController
participant SimulationRuntimeService
participant SimulationConfigRepository

Operador -> Frontend: Seleccionar escenario y guardar
Frontend -> SimulationController: POST /configure
SimulationController -> SimulationConfigRepository: save(config)
SimulationController --> Frontend: state

Operador -> Frontend: Iniciar simulacion
Frontend -> SimulationController: POST /start
SimulationController -> SimulationRuntimeService: markStarted()
SimulationController --> Frontend: state RUNNING
@enduml

4. Secuencia S2: Tick de simulacion y planificacion incremental

Flujo:
1) Scheduler activa tick en SimulationEngineService.
2) Engine obtiene config y simulatedNow.
3) Engine planifica lote incremental de shipments PENDING sin ruta.
4) RoutePlannerService asigna TravelStops y reserva carga de vuelos.
5) Engine activa/cierra vuelos segun horizonte.
6) Engine actualiza estados y KPIs runtime.

PlantUML:
@startuml
participant SimulationEngineService
participant ShipmentRepository
participant RoutePlannerService
participant TravelStopRepository
participant FlightRepository
participant SimulationRuntimeService

SimulationEngineService -> ShipmentRepository: findPendingWithoutRouteForPlanning(horizon, batch)
SimulationEngineService -> RoutePlannerService: planShipment(...)
RoutePlannerService -> TravelStopRepository: saveAll(stops)
RoutePlannerService -> FlightRepository: saveAll(updatedLoads)
SimulationEngineService -> FlightRepository: activate/close flights
SimulationEngineService -> ShipmentRepository: markActiveAsDelayedBefore(now)
SimulationEngineService -> SimulationRuntimeService: markTick(horizon)
@enduml

5. Secuencia S3: Importacion oficial de envios (job asincrono)

Flujo:
1) Operador inicia import full desde Frontend Import.
2) Frontend -> DataImportController.startFullJob().
3) EnviosImportJobService crea job RUNNING.
4) DataImportService procesa archivos por lotes y persiste shipments.
5) Frontend consulta status polling.
6) Job finaliza DONE/FAILED con resumen de consistencia.

6. Secuencia S4: Reporte SLA

Flujo:
1) Analista selecciona rango de fechas.
2) Frontend -> ReportsController.slaCompliance(from,to).
3) ReportingService calcula filas por dimensiones.
4) Frontend renderiza SLA por ruta, cliente y destino.

7. Barrido de calidad

Barrido 1 (correctitud de interacciones):
- Mensajeria y orden de llamadas coincide con controladores/servicios.

Barrido 2 (alineacion funcional):
- Secuencias cubren escenarios obligatorios y funciones LE exigibles.
"""
    return PdfDocument(
        filename="14.ana.secuencias.escenarios.v01.pdf",
        title="Analisis de Secuencias de Escenarios",
        lines=wrap_lines(text.strip()),
    )


def doc_15_rules() -> PdfDocument:
    text = f"""
15.ana.reglas.negocio.v01

Proyecto: PDDS
Documento de Analisis: Reglas de Negocio
Fecha: {datetime.now().strftime('%Y-%m-%d')}

1. Objetivo

Formalizar reglas que gobiernan el comportamiento operativo del sistema.

2. Reglas de simulacion

RN-01: La simulacion solo puede reconfigurarse cuando no esta en ejecucion.
RN-02: El estado de simulacion debe ser consultable en todo momento.
RN-03: La velocidad de simulacion debe permitir aceleracion controlada.
RN-04: Deben existir 3 escenarios operativos configurables:
       DAY_TO_DAY, PERIOD_SIMULATION, COLLAPSE_TEST.

3. Reglas de envios y rutas

RN-05: Todo envio tiene origen, destino, fecha registro, deadline y estado.
RN-06: Un envio sin ruta planificada permanece PENDING.
RN-07: Un envio planificado pasa a IN_ROUTE cuando inicia tramo activo.
RN-08: Un envio con deadline vencido y no entregado pasa a DELAYED.
RN-09: Al completar todos los tramos, estado final es DELIVERED.
RN-10: El progreso de envio depende del avance de TravelStops.

4. Reglas de capacidad y colapso

RN-11: Un vuelo no puede superar su capacidad maxima (maxCapacity).
RN-12: La ocupacion de nodo (aeropuerto) determina estado NORMAL/ALERTA/CRITICO.
RN-13: Riesgo de colapso combina carga promedio, nodos criticos y envios problematicos.
RN-14: El escenario COLLAPSE_TEST debe exponer riesgo y cuellos de botella.

5. Reglas de importacion y consistencia

RN-15: La importacion oficial full se ejecuta por job asincrono.
RN-16: Resultado de importacion debe satisfacer:
       filas_leidas = filas_importadas + filas_fallidas.
RN-17: Fallos de importacion deben quedar clasificados por causa.

6. Reglas de reportes

RN-18: El reporte SLA se presenta por tipo de ruta, cliente y nodo destino.
RN-19: Export de resultados operativos debe soportar CSV y PDF.
RN-20: Reportes de benchmark de algoritmos son internos, no operativos para usuario final.

7. Reglas de auditoria

RN-21: Eventos de planificacion/replanificacion/entrega deben registrarse en audit log.
RN-22: Toda accion critica debe conservar timestamp y entidad relacionada.

8. Criterios de validacion

- Cumplimiento funcional medido en UI y endpoints.
- Coherencia de estado entre backend y dashboard.
- Trazabilidad a LE exigibles consolidada en documento 16.

9. Barrido de calidad

Barrido 1 (ambiguedad):
- Reglas redactadas con verbo normativo y condicion verificable.

Barrido 2 (testabilidad):
- Cada regla se puede validar por endpoint, consulta o evidencia de UI.
"""
    return PdfDocument(
        filename="15.ana.reglas.negocio.v01.pdf",
        title="Analisis de Reglas de Negocio",
        lines=wrap_lines(text.strip()),
    )


def doc_16_trace() -> PdfDocument:
    text = f"""
16.ana.trazabilidad.lee.v01

Proyecto: PDDS
Documento de Analisis: Trazabilidad LE Exigibles
Fecha: {datetime.now().strftime('%Y-%m-%d')}

1. Objetivo

Mapear LE exigibles del curso con funcionalidades implementadas y evidencia.

2. Fuente de referencia

- documentacion/03.lista.exigencias.v02.xlsx
- documentacion/le-e-trazabilidad-v02.md

3. Matriz de trazabilidad (resumen)

LE-001..LE-028 Monitoreo
- Funcionalidad: Dashboard operativo en mapa.
- Backend: DashboardController, ShipmentController, AirportController, FlightController.
- Frontend: frontend/app/page.tsx.
- Evidencia: mapa con envios/vuelos, filtros, KPIs, detalle de envio.

LE-029..LE-044 Gestion de envios
- Funcionalidad: registro/importacion/seguimiento de envios.
- Backend: ShipmentController, ShipmentOrchestratorService, RoutePlannerService.
- Frontend: frontend/app/shipments/page.tsx y frontend/app/import/page.tsx.
- Evidencia: import oficial, consulta por codigo, estados de envio.

LE-049..LE-057 Simulacion
- Funcionalidad: ejecucion de escenarios y control runtime.
- Backend: SimulationController, SimulationRuntimeService, SimulationEngineService.
- Frontend: frontend/app/simulation/page.tsx y frontend/app/page.tsx.
- Evidencia: DAY_TO_DAY, PERIOD_SIMULATION, COLLAPSE_TEST.

LE-074 Reporte SLA
- Funcionalidad: SLA por ruta, cliente y destino.
- Backend: ReportsController, ReportingService.
- Frontend: frontend/app/reports/page.tsx.
- Evidencia: tabla y barras de cumplimiento por dimensiones.

4. Trazabilidad de casos de uso (vinculo con DA)

CU-01, CU-02 -> LE-049..LE-057
CU-03 -> LE-001..LE-028
CU-04, CU-05, CU-06 -> LE-029..LE-044
CU-07, CU-08 -> LE-074 y LE-049..LE-057 (export)

5. Decisiones de alcance

- Benchmark y comparativa visual de algoritmos fuera de alcance operativo.
- Se mantiene GA como perfil interno por defecto.
- Se preserva compatibilidad tecnica con ACO/SA para sustentacion.

6. Cobertura y estado

Estado general: cobertura funcional esencial de LE exigibles operativa.
Pendientes de mejora: optimizaciones no funcionales y refinamiento UX secundario.

7. Barrido de calidad

Barrido 1 (cobertura):
- Verificada inclusion de todos los bloques LE exigibles declarados.

Barrido 2 (consistencia de artefactos):
- Coherencia verificada entre PT v03, DA y funcionalidades backend/frontend.
"""
    return PdfDocument(
        filename="16.ana.trazabilidad.lee.v01.pdf",
        title="Analisis de Trazabilidad LE Exigibles",
        lines=wrap_lines(text.strip()),
    )


def doc_21_isa() -> PdfDocument:
    text = f"""
21.dis.selec.algoritmos.v02

Proyecto: PDDS
Documento ISA ampliado: Seleccion y Programacion de Algoritmos
Fecha: {datetime.now().strftime('%Y-%m-%d')}

Equipo:
- {TEAM[0]}
- {TEAM[1]}
- {TEAM[2]}
- {TEAM[3]}

1. Objetivo

Justificar seleccion e implementacion de los dos algoritmos del componente
planificador, incluyendo pseudocodigo, estructuras y evidencia de programacion
en pares para equipo de 4 integrantes.

2. Algoritmos seleccionados

Algoritmo 1: Genetic Algorithm (GA)
- Uso: busqueda de rutas candidatas minimizando costo-tiempo-riesgo.
- Justificacion:
  - Buen compromiso exploracion/explotacion.
  - Adaptable a restricciones de capacidad y ventanas temporales.
  - Robusto para espacio de soluciones grande.

Algoritmo 2: Ant Colony Optimization (ACO)
- Uso: construccion probabilistica de rutas sobre grafo de vuelos.
- Justificacion:
  - Aprovecha feromonas para reforzar rutas prometedoras.
  - Flexible ante cambios dinamicos y replanificacion.
  - Complementa a GA en exploracion de caminos.

3. Pseudocodigo y estructuras

3.1 Pseudocodigo GA

Entrada: shipment, flights, airports
Salida: lista de TravelStop optimizada

1) Generar poblacion inicial de rutas factibles.
2) Evaluar fitness de cada ruta.
3) Repetir por generaciones:
   a) Seleccionar padres por fitness.
   b) Cruzar rutas para crear descendencia.
   c) Mutar rutas con probabilidad p.
   d) Reparar rutas invalidas (capacidad/tiempos).
   e) Reemplazar poblacion conservando elite.
4) Retornar mejor ruta.

Fitness sugerido:
fitness = w1*completion - w2*transitHours - w3*operationalCost - w4*riskPenalty

3.2 Pseudocodigo ACO

Entrada: shipment, grafo de vuelos, parametros alpha/beta/rho
Salida: lista de TravelStop optimizada

1) Inicializar feromonas tau(i,j).
2) Para iteracion en 1..N:
   a) Cada hormiga construye ruta usando probabilidad:
      P(i,j) proporcional a [tau(i,j)]^alpha * [eta(i,j)]^beta
   b) Evaluar costo/tiempo/factibilidad de cada ruta.
   c) Evaporar feromonas: tau <- (1-rho)*tau
   d) Depositar feromonas en mejores rutas.
3) Retornar mejor ruta global.

3.3 Estructuras de datos requeridas

- Grafo de vuelos: nodos aeropuerto, aristas vuelo disponible.
- Matriz/tabla de feromonas para ACO.
- Cromosoma de ruta para GA (secuencia de tramos).
- Restricciones: capacidad de vuelo, ventanas temporales, conectividad.

4. Programacion de rutas y asignaciones (equipo de 4)

Se implementa:
- Metaheuristica de rutas (GA y ACO).
- Asignacion de rutas a envios con reserva de capacidad de vuelo.

Esquema de asignacion aplicado:
1) Planificar ruta candidata factible.
2) Verificar capacidad por tramo.
3) Reservar carga por vuelo (currentLoad += luggageCount) sin exceder maxCapacity.
4) Persistir TravelStops y estado del envio.

5. Integracion en arquitectura

Componentes relevantes:
- RoutePlannerService: orquestacion y seleccion de candidato.
- GeneticAlgorithm: implementacion del optimizador GA.
- AntColonyOptimization: implementacion del optimizador ACO.
- Shipment/Flight/TravelStop repositories: persistencia.

6. Programacion en pares y aportes

Par 1 (A1 + A2)
- A1 Alonso Reyes Samaniego:
  - Integracion de flujos de simulacion con planificacion incremental.
  - Validacion funcional de escenarios en mapa.
- A2 Rodrigo Holguin Huari:
  - Ajustes de control runtime y exposicion de resultados operativos.
  - Evidencia de importacion/exportacion para pruebas de operacion.

Par 2 (A3 + A4)
- A3 Sebastian Badajoz Garcia Godos:
  - Especificacion y ajuste de componentes metaheuristicos (GA/ACO).
  - Definicion de criterio de evaluacion y comparacion tecnica interna.
- A4 Adrian Picoy Cotrina:
  - Implementacion de asignacion de capacidad por tramos y persistencia.
  - Soporte a robustez de importacion masiva y consistencia de datos.

7. Criterios de verificacion tecnica

- Los dos algoritmos estan programados en el lenguaje del proyecto (Java).
- Existe ruta planificada factible para envios elegibles.
- La asignacion respeta capacidad de vuelos.
- El sistema mantiene operacion en los 3 escenarios exigidos.

8. Barrido de calidad

Barrido 1 (alineacion academica ISA):
- Incluye seleccion, pseudocodigo, estructuras y alcance de programacion.

Barrido 2 (trazabilidad a codigo):
- Secciones validadas contra componentes backend existentes.
"""
    return PdfDocument(
        filename="21.dis.selec.algoritmos.v02.pdf",
        title="ISA ampliado - Seleccion de Algoritmos",
        lines=wrap_lines(text.strip()),
    )


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    out_dir = root / "documentacion"
    out_dir.mkdir(parents=True, exist_ok=True)

    docs = [
        doc_01(),
        doc_12_uc(),
        doc_13_classes(),
        doc_14_sequences(),
        doc_15_rules(),
        doc_16_trace(),
        doc_21_isa(),
    ]

    for d in docs:
        build_pdf(out_dir / d.filename, d.title, d.lines)

    print("PDFs generados:")
    for d in docs:
        print(f"- {out_dir / d.filename}")


if __name__ == "__main__":
    main()
