# Guion tecnico de exposicion mostrando codigo

## Orden recomendado de archivos

1. `RouteOptimizer.java`
2. `RoutePlanningSupport.java`
3. `GeneticAlgorithm.java`
4. `AntColonyOptimization.java`
5. `RoutePlannerService.java`
6. `SimulationController.java`
7. `AlgorithmRaceService.java`
8. `BenchmarkTuningService.java`

## 1. Abrir `RouteOptimizer.java`

Objetivo del bloque:
- mostrar la interfaz comun
- explicar que ambos algoritmos cumplen exactamente el mismo contrato

Que explicar:
- `getAlgorithmName()` identifica el algoritmo en reportes y logs
- `planRoute(...)` construye una ruta completa
- `replanRoute(...)` reconstruye una ruta desde una parada fallida
- `evaluatePerformance(...)` entrega metricas agregadas para comparacion

Mensaje sugerido:
"Antes de entrar a cada algoritmo, definimos un contrato comun para que ambos se puedan invocar y comparar de la misma manera dentro del sistema."

## 2. Abrir `RoutePlanningSupport.java`

Objetivo del bloque:
- mostrar la infraestructura tecnica comun
- explicar por que la comparacion entre GA y ACO es justa

Orden interno sugerido:
1. `maxFlightLegs(...)`
2. `eligibleFlights(...)`
3. `candidateFlights(...)`
4. `enumerateRoutes(...)`
5. `dfsEnumerate(...)`
6. `isFeasibleRoute(...)`
7. `isFeasibleNextFlight(...)`
8. `routeScore(...)`
9. `routeFitness(...)`
10. `toTravelStops(...)`

Que decir metodo por metodo:

### `maxFlightLegs(...)`
Define cuantas escalas maximas se permiten segun si el envio es intra o inter continental.

### `eligibleFlights(...)`
Hace el primer filtro duro: estado, capacidad, fechas y coherencia temporal del vuelo.

### `candidateFlights(...)`
Reduce el espacio de busqueda para no recorrer toda la red completa.

### `enumerateRoutes(...)`
Genera rutas candidatas factibles usando DFS limitada.

### `dfsEnumerate(...)`
Recorre el grafo evitando ciclos y respetando el numero maximo de tramos.

### `isFeasibleRoute(...)`
Valida que una ruta completa realmente sea usable por el envio.

### `isFeasibleNextFlight(...)`
Valida si un vuelo puede seguir al tramo anterior considerando tiempos de conexion.

### `routeScore(...)`
Define el costo de la ruta: tiempo, saturacion, carga, escalas y deadline.

### `routeFitness(...)`
Convierte el score en un fitness util para GA y reutilizable por ACO.

### `toTravelStops(...)`
Convierte la ruta abstracta en la estructura persistible que usa el sistema.

Mensaje sugerido:
"Esta clase es clave porque ambos algoritmos comparten exactamente las mismas reglas de factibilidad y evaluacion."

## 3. Abrir `GeneticAlgorithm.java`

Objetivo del bloque:
- explicar como funciona `GA` a nivel tecnico
- justificar por que termino siendo el algoritmo ganador

Orden interno sugerido:
1. atributos de configuracion
2. `Individual`
3. `planRoute(...)`
4. `initializePopulation(...)`
5. `evolve(...)`
6. `selectParent(...)`
7. `crossoverRoutes(...)`
8. `mutateRoute(...)`
9. `fitness(...)`
10. `replanRoute(...)`

Que decir:

### Configuracion
- `populationSize`: cuantos individuos hay por generacion
- `generations`: cuantas iteraciones evolutivas se ejecutan
- `mutationRate`: frecuencia de mutacion

### `Individual`
Cada individuo encapsula una ruta y su fitness precalculado.

### `planRoute(...)`
Es el orquestador del algoritmo:
- obtiene candidatas factibles
- crea poblacion inicial
- evoluciona por generaciones
- devuelve la mejor ruta encontrada

### `initializePopulation(...)`
Construye la primera generacion a partir de rutas ya factibles.
No parte de basura aleatoria, sino de soluciones viables mas algunas variantes.

### `evolve(...)`
Implementa una generacion completa:
- elitismo
- seleccion de padres
- cruce
- mutacion
- recorte de la siguiente poblacion

### `selectParent(...)`
Usa torneo de 3 individuos.
Es una forma simple de favorecer rutas mejores sin perder tanta diversidad.

### `crossoverRoutes(...)`
No cruza tramos arbitrariamente. Recombina dentro de rutas factibles cercanas al origen/hub de los padres.

### `mutateRoute(...)`
Explora alternativas cercanas usando una muestra aleatoria del conjunto de candidatas.

### `fitness(...)`
No inventa una metrica propia. Reutiliza la funcion comun para mantener comparabilidad con ACO.

### `replanRoute(...)`
Construye un envio parcial desde la parada fallida y relanza el algoritmo.

Mensaje sugerido:
"GA fue elegido como algoritmo operativo porque su proceso iterativo resulto mas estable y controlable para el sistema."

## 4. Abrir `AntColonyOptimization.java`

Objetivo del bloque:
- explicar ACO tecnicamente
- mostrar que esta completo aunque no sea el algoritmo operativo

Orden interno sugerido:
1. parametros del algoritmo
2. mapa `pheromones`
3. `planRoute(...)`
4. `initialize(...)`
5. `buildSolution(...)`
6. `rouletteSelect(...)`
7. `evaporate()`
8. `updatePheromones(...)`
9. `replanRoute(...)`

Que decir:

### Parametros
- `numAnts`: hormigas por iteracion
- `iterations`: numero de iteraciones
- `evaporationRate`: evaporacion global
- `alpha`: peso de feromona
- `beta`: peso de heuristica

### `pheromones`
Mapea cada vuelo a un nivel de feromona. Ese valor representa que tan prometedor fue historicamente ese tramo.

### `planRoute(...)`
Es el orquestador del algoritmo:
- filtra vuelos candidatos
- inicializa feromonas
- deja que varias hormigas construyan rutas
- actualiza el conocimiento del sistema por iteraciones
- conserva la mejor ruta hallada

### `initialize(...)`
Asigna un valor inicial de feromona a todos los vuelos considerados.

### `buildSolution(...)`
Construye la ruta paso a paso:
- parte del origen
- busca salidas validas
- evita ciclos
- elige siguiente vuelo con ruleta probabilistica
- avanza hasta llegar al destino o quedarse sin salida

### `rouletteSelect(...)`
Hace la seleccion probabilistica real.
Combina:
- feromona actual del vuelo
- heuristica local del vuelo

### `evaporate()`
Reduce feromonas para que el algoritmo no se estanque siempre en la misma solucion.

### `updatePheromones(...)`
Refuerza las rutas que tuvieron mejor fitness.

### `replanRoute(...)`
Aplica el mismo principio a una replanificacion parcial.

Mensaje sugerido:
"ACO no se usa en produccion, pero si esta implementado completamente para comparacion tecnica y para poder explicar un enfoque bioinspirado alternativo."

## 5. Abrir `RoutePlannerService.java`

Objetivo del bloque:
- mostrar donde se integran los algoritmos con el resto del sistema

Metodos clave:

### `planShipment(...)`
- resuelve el optimizador
- obtiene candidato directo y multi-tramo
- compara ambos
- persiste paradas y carga de vuelos

### `previewRoute(...)`
- genera una ruta sin persistir
- util para benchmark y comparacion

### `replanShipment(...)`
- usa el optimizador activo para rehacer la ruta desde una parada fallida

### `resolveOptimizer(...)`
- traduce nombre a implementacion concreta

### `benchmarkGaVsAco(...)`
- corre GA y ACO sobre el mismo lote de envios
- devuelve metricas comparables

Mensaje sugerido:
"Aqui se ve que los algoritmos no viven aislados; estan conectados con planificacion, persistencia, asignacion de carga y benchmark."

## 6. Abrir `SimulationController.java`

Objetivo del bloque:
- mostrar la decision operativa del sistema

Punto clave:
- en `configure(...)` se fija `GENETIC` como algoritmo operativo

Mensaje sugerido:
"Aunque ambos algoritmos existen, el sistema toma una decision clara: la operacion diaria queda fijada al GA ganador."

## 7. Abrir `AlgorithmRaceService.java` y `BenchmarkTuningService.java`

Objetivo del bloque:
- mostrar como se compara GA y ACO fuera del flujo operativo

Que decir:
- `AlgorithmRaceService` arma el reporte comparativo
- `BenchmarkTuningService` ejecuta lotes y agrega resultados
- el benchmark no depende del frontend
- la comparacion se basa en el mismo lote y mismas restricciones

## 8. Cierre recomendado

Mensaje final:

"Tecnica y arquitectonicamente dejamos ambos algoritmos implementados. Sin embargo, a nivel de sistema, GA queda como la solucion productiva y ACO como alternativa completa para comparacion y sustentacion."
