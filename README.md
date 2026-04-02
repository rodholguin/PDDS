# Tasf.B2B — Sistema de Gestión de Traslado de Maletas

**Proyecto universitario PUCP 2026-1**  
Simula operaciones logísticas aéreas entre aeropuertos de América, Asia y Europa,
con planificación de rutas, monitoreo visual y simulación de escenarios de colapso.

---

## Descripción del sistema

El sistema gestiona el **traslado de maletas entre aeropuertos**, organizando envíos
(grupos de maletas de una aerolínea) a través de vuelos disponibles, con el objetivo
de mantener el sistema operativo el mayor tiempo posible antes del colapso por saturación.

### Entidades principales

| Entidad        | Descripción                                              |
|----------------|----------------------------------------------------------|
| `Airport`      | Aeropuerto con capacidad de almacenamiento (500–800 mal) |
| `Flight`       | Vuelo entre aeropuertos (intra/inter continental)        |
| `Shipment`     | Envío = grupo de maletas de una aerolínea con deadline   |
| `TravelStop`   | Parada del plan de viaje de un envío                     |
| `SimulationConfig` | Configuración del escenario de simulación            |

---

## Stack técnico

| Capa       | Tecnología                                     |
|------------|------------------------------------------------|
| Frontend   | Next.js 15 · TypeScript · Tailwind CSS v4      |
| Backend    | Spring Boot 3.4.4 · Java 24 · Spring Data JPA  |
| Base datos | PostgreSQL 15                                  |
| Infra      | Docker Compose                                 |

---

## Requisitos

- **Docker Desktop** ≥ 4.x (con Docker Compose v2)
- **Node.js 22+** (solo para desarrollo local del frontend)
- **Java 24 + Maven 3.9+** (solo para desarrollo local del backend)

---

## Levantar el proyecto completo

```bash
# 1. Clonar el repositorio
git clone <repo-url>
cd tasf-b2b

# 2. Copiar variables de entorno
cp .env.example .env

# 3. Construir y levantar los 3 servicios
docker compose up --build

# 4. Para ejecutar en segundo plano
docker compose up --build -d
```

### URLs disponibles

| Servicio       | URL                                   |
|----------------|---------------------------------------|
| Frontend       | http://localhost:3000                 |
| API REST       | http://localhost:8080/api             |
| Swagger UI     | http://localhost:8080/swagger-ui.html |
| API Docs JSON  | http://localhost:8080/api-docs        |
| PostgreSQL     | localhost:5432 (db: tasfb2b)          |

---

## Desarrollo local (sin Docker)

### Backend

```bash
cd backend

# Requiere PostgreSQL local en localhost:5432
# con DB tasfb2b, usuario tasfb2b, contraseña tasfb2b

mvn spring-boot:run
# API disponible en http://localhost:8080
```

### Frontend

```bash
cd frontend
npm install
npm run dev
# Interfaz disponible en http://localhost:3000
```

---

## Cómo importar datos

### 1. Descargar plantillas

Accede a **Importar Datos → Descargar Plantillas** en el menú lateral.
Hay tres plantillas Excel:

- `template-shipments.xlsx` — Envíos de maletas
- `template-airports.xlsx` — Aeropuertos
- `template-flights.xlsx` — Vuelos

También puedes descargarlas directamente:
```
GET http://localhost:8080/api/import/template/shipments
GET http://localhost:8080/api/import/template/airports
GET http://localhost:8080/api/import/template/flights
```

### 2. Preparar el archivo

Copia la plantilla y llena los datos según el formato de la primera fila (encabezados).
El sistema acepta `.csv` (separador `,` o `;`) y `.xlsx`.

**Formato Envíos (shipments):**
```
airline_name | origin_icao | destination_icao | luggage_count | registration_date
LATAM        | JFK         | BOG              | 120           | 2025-06-01 08:00:00
```

**Formato Aeropuertos (airports):**
```
icao_code | city       | country       | continent | max_storage_capacity
SCL       | Santiago   | Chile         | AMERICA   | 650
```

**Formato Vuelos (flights):**
```
flight_code  | origin_icao | destination_icao | max_capacity | scheduled_departure      | scheduled_arrival
FL-NEW-001   | SCL         | LIM              | 180          | 2025-06-01 10:00:00      | 2025-06-01 22:00:00
```

### 3. Importar

Arrastra el archivo al área de drag & drop en **Importar Datos** o usa el botón
"Seleccionar archivo". Haz clic en **Importar**.

El sistema procesa cada fila de manera independiente: si una fila tiene error,
el resto continúa importándose. Al finalizar verás el resumen de filas exitosas y errores.

### 4. Endpoint directo (API)

```bash
curl -X POST http://localhost:8080/api/import/shipments \
     -F "file=@mis_envios.csv"
```

---

## Algoritmo anticolapso

### Objetivo

El algoritmo asigna maletas a vuelos de modo que el sistema **colapse lo más tarde posible**.
El colapso se define como el momento en que todos los aeropuertos superan el umbral crítico
de ocupación (por defecto 90%), impidiendo asignar nuevos envíos.

### Estrategia (5 reglas en orden de prioridad)

1. **URGENCIA** — Maletas con deadline más próximo se asignan primero (menor slack temporal).
2. **BALANCE DE CARGA** — Preferir vuelos y aeropuertos con menor % de ocupación.
3. **RUTA MÁS CORTA** — Minimizar escalas: vuelo directo o máximo 1 escala intermedia.
4. **CONTINGENCIA** — Si el vuelo directo está lleno, buscar ruta alternativa con 1 hub de baja carga.
5. **RECHAZO CONTROLADO** — Si no hay ruta factible, marcar el envío como CRITICAL y alertar al operador.

### Dos algoritmos implementados

| Algoritmo               | Enfoque                                                   |
|-------------------------|-----------------------------------------------------------|
| **Genético (GA)**       | Evoluciona una población de rutas candidatas; penaliza retrasos, aeropuertos saturados y vuelos sobrecargados en la función de fitness |
| **Colonia de Hormigas (ACO)** | Las hormigas depositan feromonas en rutas de baja saturación; las feromonas guían futuras asignaciones hacia caminos anticolapso |

Ambos algoritmos se comparan en tiempo real en **Simulación → Resultados**.
El algoritmo primario se configura en **Simulación → Configurar**.

### Métricas de riesgo

- **Riesgo de colapso** (0.0–1.0): promedio ponderado de ocupación de almacenes (60%), aeropuertos críticos (30%) y envíos problemáticos (10%).
- **Tiempo estimado al colapso**: capacidad libre / tasa de llegadas estimada en horas.
- **Cuellos de botella**: aeropuertos que superan el umbral de alerta (default 90%).

---

## Comandos Docker útiles

```bash
# Ver logs en tiempo real
docker compose logs -f backend
docker compose logs -f frontend

# Reiniciar solo el backend (sin rebuild)
docker compose restart backend

# Reconstruir solo el backend
docker compose up --build backend

# Detener y eliminar contenedores (los datos de postgres se conservan)
docker compose down

# Detener y eliminar TODO incluido el volumen de postgres
docker compose down -v

# Ver estado de los servicios
docker compose ps
```

---

## Estructura del proyecto

```
tasf-b2b/
├── docker-compose.yml
├── .env                          ← Variables de entorno (no commitear)
├── .env.example                  ← Plantilla de variables
├── backend/
│   ├── Dockerfile
│   ├── pom.xml                   ← Spring Boot 3.4.4, Java 24
│   └── src/main/java/com/tasfb2b/
│       ├── model/                ← Entidades JPA (Airport, Flight, Shipment…)
│       ├── repository/           ← Spring Data JPA
│       ├── service/
│       │   ├── algorithm/        ← GeneticAlgorithm, AntColonyOptimization
│       │   ├── RoutePlannerService.java
│       │   ├── CollapseMonitorService.java
│       │   └── DataImportService.java
│       ├── controller/           ← REST endpoints
│       ├── dto/                  ← DTOs de request/response
│       └── config/               ← CORS, Scheduling, Exception handler
└── frontend/
    ├── Dockerfile
    ├── app/
    │   ├── page.tsx              ← Dashboard con KPIs y aeropuertos
    │   ├── import/page.tsx       ← Importación CSV/Excel
    │   ├── simulation/page.tsx   ← Control de simulación
    │   └── reports/page.tsx      ← Reportes
    ├── components/Sidebar.tsx
    └── lib/
        ├── types/index.ts        ← Interfaces TypeScript del dominio
        └── api/                  ← Servicios HTTP por entidad
```
