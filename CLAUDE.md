# PDDS - Tasf.B2B

## Stack
- Backend: Spring Boot 3.4.4, Java 21, puerto 8080
- Frontend: Next.js (ver frontend/AGENTS.md para reglas específicas)
- BD: PostgreSQL 15
- Infraestructura: Docker Compose

## Contexto del proyecto
Sistema de simulación logística de traslado de maletas entre aeropuertos 
(América, Asia, Europa) para el curso 1INF54 PUCP 2026-1.

## Estado actual
- Fix aplicado: @Transactional removido de OperationalBootstrapService.bootstrap()
- Dataset del profesor: archivos _envios_XXXX_.txt van en datos/envios/ (no en repo)
- Importar dataset: POST /api/import/shipments/dataset/full?maxPerAirport=200

## Pendientes conocidos
- Añadir cancelación de vuelos durante simulación con replanificación automática
- Importación automática del dataset al iniciar bootstrap
- Verificar comportamiento del pre-planning antes de fecha inicial de simulación

## Decisiones de diseño importantes
- FutureDemandProjectionService proyecta ~120k envíos hasta 2030 en tabla shipments
- El engine NO escanea shipments durante ticks — trabaja solo con TravelStop
- /reset-demand borra TODOS los shipments incluyendo históricos reales
