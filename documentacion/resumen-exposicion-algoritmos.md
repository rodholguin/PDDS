# Resumen tecnico corto para exponer los algoritmos

## Que decir al inicio

"El sistema opera con Genetic Algorithm, pero implementamos tambien Ant Colony Optimization completo para poder compararlos tecnicamente y justificar la eleccion final."

## Mapa mental rapido

### Capa 1: contrato comun
- `RouteOptimizer`

### Capa 2: reglas compartidas
- `RoutePlanningSupport`

### Capa 3: algoritmos concretos
- `GeneticAlgorithm`
- `AntColonyOptimization`

### Capa 4: integracion del sistema
- `RoutePlannerService`
- `SimulationController`
- `AlgorithmRaceService`
- `BenchmarkTuningService`

## Como explicar GA en 1 minuto

1. Representa cada ruta como un individuo.
2. Genera una poblacion inicial de rutas factibles.
3. Evalua cada ruta con una funcion de fitness comun.
4. Selecciona mejores padres por torneo.
5. Recombina y muta rutas para explorar variantes.
6. Repite por generaciones.
7. Devuelve la mejor ruta encontrada.

## Como explicar ACO en 1 minuto

1. Inicializa feromonas para todos los vuelos candidatos.
2. Cada hormiga construye una ruta paso a paso.
3. La siguiente arista se elige por ruleta usando feromona + heuristica.
4. Las mejores rutas depositan mas feromona.
5. La evaporacion evita convergencia prematura.
6. Tras varias iteraciones se conserva la mejor ruta global.

## Idea clave de comparabilidad

"Ambos algoritmos usan la misma definicion de ruta factible y la misma base de scoring, por eso la comparacion es justa."

## Donde entra cada uno en el sistema

### Operacion real
- `SimulationController.configure(...)` fija `GENETIC`
- `RoutePlannerService.planShipment(...)` usa el optimizador resuelto

### Comparacion tecnica
- `RoutePlannerService.benchmarkGaVsAco(...)`
- `AlgorithmRaceService`
- `BenchmarkTuningService`

## Preguntas frecuentes y respuesta corta

### Por que GA y no ACO en produccion?
Porque GA fue el ganador oficial del benchmark y se estandarizo como algoritmo operativo.

### Entonces ACO para que sirve?
Para comparacion tecnica, benchmark y sustentacion del trabajo.

### El frontend cambia segun el algoritmo?
No. La diferencia esta en el backend de planificacion y en el benchmark comparativo.

### Cual es la pieza mas importante para entender ambos?
`RoutePlanningSupport`, porque ahi se define la factibilidad comun y el scoring comun.
