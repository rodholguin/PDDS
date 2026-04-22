# Guia tecnica de Genetic Algorithm y Ant Colony Optimization

## 1. Rol de los algoritmos dentro del sistema

En el sistema hay dos niveles distintos:

- **Operacion real**: usa solo `GeneticAlgorithm`.
- **Comparacion tecnica / benchmark / exposicion**: usa `GeneticAlgorithm` y `AntColonyOptimization`.

El punto de entrada real de planificacion es `RoutePlannerService`.

Flujo operativo normal:
1. `RoutePlannerService.planShipment(...)`
2. filtra vuelos elegibles para el envio
3. resuelve el optimizador con `resolveOptimizer(...)`
4. construye dos candidatos:
   - candidato directo (`buildDirectCandidate(...)`)
   - candidato multi-tramo (`optimizer.planRoute(...)`)
5. compara ambos con `selectBestCandidate(...)`
6. persiste las paradas elegidas y reserva carga en vuelos

Flujo de replanificacion:
1. `RoutePlannerService.replanShipment(...)`
2. identifica la primera parada pendiente o en transito
3. llama `optimizer.replanRoute(...)`
4. reemplaza las paradas pendientes por la nueva ruta

Comparacion tecnica:
1. `RoutePlannerService.benchmarkGaVsAco(...)`
2. corre ambos algoritmos con el mismo lote de envios y los mismos vuelos
3. devuelve metricas agregadas de comparacion

## 2. Contrato comun: `RouteOptimizer`

Archivo:
- `backend/src/main/java/com/tasfb2b/service/algorithm/RouteOptimizer.java`

Metodos principales:

### `String getAlgorithmName()`
Devuelve el nombre legible del algoritmo.
Se usa en:
- logs
- benchmark
- reportes comparativos

### `List<TravelStop> planRoute(Shipment shipment, List<Flight> availableFlights, List<Airport> airports)`
Construye una ruta completa factible para un envio.

Entrada:
- envio
- lista de vuelos disponibles
- aeropuertos del sistema

Salida:
- secuencia ordenada de `TravelStop`
- vacia si no hay ruta factible

### `List<TravelStop> replanRoute(Shipment shipment, TravelStop failedStop, List<Flight> availableFlights)`
Genera una nueva ruta parcial desde una parada fallida hasta el destino final.

### `OptimizationResult evaluatePerformance(...)`
Devuelve metricas agregadas del algoritmo sobre un conjunto de envios.
Este metodo sirve para comparacion y reportes, no para la simulacion visual en si.

## 3. Base comun: `RoutePlanningSupport`

Archivo:
- `backend/src/main/java/com/tasfb2b/service/algorithm/RoutePlanningSupport.java`

Esta clase existe para que `GA` y `ACO` no compitan con reglas distintas.

### `maxFlightLegs(Shipment shipment)`
Define el maximo de tramos permitidos segun el tipo de envio.

Regla actual:
- intra-continental: hasta 2 vuelos
- inter-continental: hasta 3 vuelos

### `eligibleFlights(Shipment shipment, List<Flight> flights)`
Filtra la lista de vuelos y deja solo vuelos factibles en el nivel mas basico.

Pasos:
1. descarta vuelos nulos
2. exige `status == SCHEDULED`
3. exige origen y destino definidos
4. exige fechas de salida y llegada validas
5. exige capacidad disponible suficiente para el envio
6. exige salida no anterior al registro del envio
7. ordena por salida y llegada

### `candidateFlights(Shipment shipment, List<Flight> flights)`
Reduce el espacio de busqueda para no evaluar toda la red.

Pasos:
1. parte de `eligibleFlights(...)`
2. identifica vuelos que salen del aeropuerto origen
3. arma un conjunto de hubs potenciales con esos primeros tramos
4. conserva vuelos relevantes para origen, hubs y destino
5. limita la cantidad total de candidatos

Idea:
- bajar costo computacional
- mantener rutas razonables cerca del origen-destino del envio

### `enumerateRoutes(Shipment shipment, List<Flight> flights)`
Enumera rutas candidatas factibles mediante DFS limitada.

Pasos:
1. llama `candidateFlights(...)`
2. obtiene origen, destino y fecha de registro
3. ejecuta `dfsEnumerate(...)`
4. guarda solo rutas no duplicadas
5. ordena las rutas por `routeScore(...)`

### `dfsEnumerate(...)`
Recorre recursivamente el grafo de vuelos.

Controla:
- aeropuerto actual
- destino final
- hora minima para el siguiente vuelo
- numero maximo de tramos
- aeropuertos visitados para evitar ciclos

Cuando encuentra un vuelo que llega al destino:
- guarda una copia del camino actual como ruta candidata

### `isFeasibleRoute(Shipment shipment, List<Flight> route)`
Valida que una ruta completa realmente cumpla las reglas del dominio.

Chequeos:
1. ruta no vacia
2. no excede maximo de tramos
3. origen y destino del envio definidos
4. cada vuelo sale del aeropuerto donde quedo el envio
5. cada vuelo cumple continuidad temporal
6. no hay ciclos intermedios
7. el ultimo aeropuerto coincide con el destino final

### `isFeasibleNextFlight(List<Flight> currentPath, LocalDateTime readyAt, Flight candidate)`
Valida si un vuelo puede ser el siguiente tramo.

Chequeos:
- vuelo con fechas validas
- vuelo programado
- capacidad disponible
- llegada no anterior a la salida
- salida posterior a la llegada del tramo anterior + tiempo minimo de conexion

### `toTravelStops(Shipment shipment, List<Flight> route)`
Convierte una ruta como lista de vuelos en la representacion persistible del sistema: `TravelStop`.

Construccion:
1. crea stop 0 para el aeropuerto origen, sin vuelo
2. crea un `TravelStop` por cada vuelo usando el aeropuerto destino del vuelo
3. asigna `stopOrder` secuencial

### `routeScore(Shipment shipment, List<Flight> route)`
Calcula el costo de una ruta. Menor es mejor.

Componentes del score:
- ETA total en minutos
- penalizacion por carga alta de vuelos
- penalizacion por saturacion de aeropuertos visitados
- penalizacion por numero de escalas
- penalizacion muy alta si incumple el deadline

Este score es la base objetiva comun de comparacion.

### `routeFitness(Shipment shipment, List<Flight> route)`
Transforma el costo en fitness. Mayor es mejor.

Formula actual:
- `fitness = max(1, 10000 - routeScore)`

`GA` trabaja directamente con fitness.
`ACO` usa el score y lo traduce a refuerzo de feromonas.

### `routeScoreForSingleFlight(Flight flight)`
Calcula un costo heuristico simplificado para un vuelo individual.
Se usa en `ACO` para ponderar la seleccion local.

### `registrationTime(...)`, `estimatedArrival(...)`, `normalizeRoute(...)`
Helpers utilitarios para evitar repetir logica en ambos algoritmos.

## 4. Genetic Algorithm

Archivo:
- `backend/src/main/java/com/tasfb2b/service/algorithm/GeneticAlgorithm.java`

## 4.1 Idea general

`GA` trabaja sobre una poblacion de rutas candidatas.
Cada individuo contiene:
- `route`: lista de vuelos
- `fitness`: puntaje precalculado de esa ruta

Registro interno:
- `Individual(List<Flight> route, double fitness)`

## 4.2 Parametros

### `populationSize`
Cantidad de individuos por generacion.

### `generations`
Numero de iteraciones evolutivas.

### `mutationRate`
Probabilidad de aplicar mutacion a un hijo.

## 4.3 `planRoute(...)`

Es el metodo principal del algoritmo.

Pasos:
1. llama `RoutePlanningSupport.enumerateRoutes(...)` para obtener todas las rutas candidatas factibles
2. si no hay rutas, retorna vacio
3. crea un `Random` determinista a partir del `shipmentCode`
4. crea la poblacion inicial con `initializePopulation(...)`
5. itera `generations` veces con `evolve(...)`
6. elige el individuo con mejor fitness
7. convierte la ruta ganadora a `TravelStop` con `toTravelStops(...)`

## 4.4 `initializePopulation(...)`

Objetivo:
- construir una poblacion inicial diversa sin romper factibilidad

Pasos:
1. ordena rutas candidatas por `routeScore(...)`
2. agrega las mejores rutas unicas al mapa de poblacion
3. si aun faltan individuos, toma semillas aleatorias de las candidatas
4. sobre esas semillas aplica `mutateRoute(...)`
5. sigue hasta llenar `populationSize`

Resultado:
- la poblacion inicial no es aleatoria pura
- mezcla buenas rutas con algunas variantes exploratorias

## 4.5 `evolve(...)`

Representa una generacion completa.

Pasos:
1. ordena la poblacion actual por fitness descendente
2. calcula el numero de elites
3. copia los mejores individuos sin modificarlos
4. mientras falten individuos:
   - selecciona 2 padres con `selectParent(...)`
   - genera un hijo con `crossoverRoutes(...)`
   - con cierta probabilidad muta el hijo con `mutateRoute(...)`
   - lo agrega si sigue siendo factible
5. ordena la nueva poblacion y recorta a `populationSize`

## 4.6 `selectParent(...)`

Implementa seleccion por torneo.

Pasos:
1. toma 3 individuos aleatorios
2. devuelve el de mayor fitness

Ventaja:
- simple
- estable
- introduce presion selectiva sin colapsar diversidad demasiado rapido

## 4.7 `crossoverRoutes(...)`

Implementa una recombinacion conservadora de rutas.

Idea actual:
1. arma un pool con:
   - ruta del padre A
   - ruta del padre B
   - otras rutas candidatas relacionadas con el mismo origen o hub pivote
2. elige una ruta buena dentro de ese pool mediante `bestRoute(...)`

No hace un cruce gen a gen puro, pero si recombina el espacio de rutas viables alrededor de ambos padres.

## 4.8 `mutateRoute(...)`

Explora variantes cercanas a una ruta base.

Pasos:
1. arma un pool con la ruta actual
2. toma una muestra aleatoria de rutas candidatas
3. selecciona una buena alternativa con `bestRoute(...)`

Efecto:
- evita quedarse congelado siempre en la misma ruta
- mantiene factibilidad porque muta dentro de rutas ya validadas

## 4.9 `fitness(...)`

Es un wrapper directo sobre `RoutePlanningSupport.routeFitness(...)`.

Importancia:
- garantiza comparabilidad con `ACO`
- evita que cada algoritmo use una funcion objetivo distinta

## 4.10 `bestRoute(...)`

Toma un conjunto de rutas candidatas ya viables y devuelve una de las mejores dentro de una ventana pequena.

Idea:
- no tomar siempre la mejor exacta
- conservar algo de exploracion

## 4.11 `addIndividual(...)`

Responsabilidad:
- validar factibilidad
- normalizar la ruta
- construir la clave unica de la ruta
- conservar la mejor version de esa ruta en la poblacion

## 4.12 `replanRoute(...)`

Reutiliza el mismo algoritmo sobre un envio parcial.

Pasos:
1. crea un `Shipment` artificial cuyo origen es la parada fallida
2. conserva el destino original
3. usa como fecha de reinicio la llegada real a la parada o `now`
4. llama otra vez a `planRoute(...)`
5. corrige `stopOrder` para que continúe desde la parada fallida

## 5. Ant Colony Optimization

Archivo:
- `backend/src/main/java/com/tasfb2b/service/algorithm/AntColonyOptimization.java`

## 5.1 Idea general

`ACO` no trabaja con poblaciones fijas sino con construccion repetida de rutas por hormigas.

Estado interno principal:
- `Map<Long, Double> pheromones`

Cada entrada asocia:
- `flightId -> nivel de feromona`

## 5.2 Parametros

### `numAnts`
Cantidad de hormigas por iteracion.

### `iterations`
Numero de iteraciones de construccion y refuerzo.

### `evaporationRate`
Cuanto se reduce la feromona en cada iteracion.

### `alpha`
Peso de la feromona.

### `beta`
Peso de la heuristica local.

### `initialPheromone`
Valor inicial asignado a todos los vuelos.

## 5.3 `planRoute(...)`

Es el punto principal de ACO.

Pasos:
1. reduce el espacio de vuelos con `candidateFlights(...)`
2. inicializa la matriz de feromonas con `initialize(...)`
3. crea un `Random` determinista por envio
4. repite `iterations` veces:
   - cada hormiga construye una ruta con `buildSolution(...)`
   - si la ruta es factible, se evalua con `routeScore(...)`
   - se conserva la mejor ruta global
   - se evaporan feromonas con `evaporate()`
   - se refuerzan rutas buenas con `updatePheromones(...)`
5. la mejor ruta global se convierte a `TravelStop`

## 5.4 `initialize(...)`

Responsabilidad:
- limpiar el mapa de feromonas
- asignar `initialPheromone` a cada vuelo candidato

## 5.5 `buildSolution(...)`

Construye una ruta de forma incremental.

Variables de control:
- `path`: ruta parcial actual
- `visited`: aeropuertos ya visitados
- `current`: aeropuerto actual
- `readyAt`: instante desde el cual se puede abordar el siguiente vuelo
- `maxLegs`: maximo de tramos permitidos

Pasos:
1. arranca en el aeropuerto origen
2. busca vuelos salientes factibles desde el aeropuerto actual
3. elimina candidatos que generen ciclos no permitidos
4. elige uno con `rouletteSelect(...)`
5. lo agrega a `path`
6. si llega al destino, retorna la ruta
7. si no, avanza al siguiente aeropuerto y repite

Si en algun punto no hay vuelo saliente valido:
- retorna ruta vacia

## 5.6 `rouletteSelect(...)`

Seleccion probabilistica del siguiente vuelo.

Para cada candidato calcula:
- `tau`: feromona actual del vuelo
- `eta`: heuristica local basada en `routeScoreForSingleFlight(...)`
- `weight = tau^alpha * eta^beta`

Luego:
1. suma todos los pesos
2. lanza un numero aleatorio en ese rango
3. recorre pesos acumulados
4. devuelve el vuelo donde cae la ruleta

Interpretacion:
- vuelos con mas feromona y mejor heuristica local tienen mayor probabilidad

## 5.7 `evaporate()`

Reduce el nivel de feromona de todos los vuelos.

Formula:
- `tau = max(tauMin, tau * (1 - evaporationRate))`

Objetivo:
- evitar convergencia prematura
- mantener exploracion

## 5.8 `updatePheromones(...)`

Refuerza vuelos usados en rutas buenas.

Pasos:
1. calcula `fitness` de cada ruta con la funcion comun
2. traduce ese fitness a un delta de feromona
3. suma ese delta a todos los vuelos que pertenecen a la ruta

Efecto:
- rutas de mejor calidad aumentan la probabilidad de que sus vuelos vuelvan a ser elegidos

## 5.9 `replanRoute(...)`

Funciona igual que en `GA` a nivel de idea:
1. crea un envio parcial desde la parada fallida
2. llama nuevamente `planRoute(...)`
3. ajusta `stopOrder`

## 6. Como se usan dentro del sistema

## 6.1 Uso operativo real

Archivo:
- `backend/src/main/java/com/tasfb2b/controller/SimulationController.java`

En `configure(...)` se fija:
- `primaryAlgorithm = GENETIC`
- `secondaryAlgorithm = GENETIC`

Esto significa que la simulacion operativa usa solo `GA`.

## 6.2 Uso dentro de `RoutePlannerService`

### `resolveOptimizer(String algorithmName)`
Resuelve que algoritmo usar segun el nombre recibido.

### `planShipment(...)`
Puede invocar `GA`, `ACO` o `SA` por nombre, pero la operacion normal queda fijada a `GA` por configuracion.

### `previewRoute(...)`
Sirve para comparar rutas sin persistir cambios.
Es importante para benchmark y validacion.

### `benchmarkGaVsAco(...)`
Corre ambos algoritmos sobre el mismo lote y devuelve las metricas comparativas.

## 6.3 Benchmark y reportes

Archivos:
- `AlgorithmRaceService.java`
- `BenchmarkTuningService.java`

Funcion:
- usar los mismos envios
- usar los mismos vuelos
- comparar `GA` y `ACO` de forma justa
- sostener la decision oficial de que `GA` es el ganador

## 7. Resumen conceptual corto

### GA
- representa rutas como individuos
- mejora por generaciones
- selecciona, recombina y muta rutas factibles

### ACO
- construye rutas paso a paso
- usa feromonas para reforzar buenas decisiones
- combina exploracion y explotacion probabilistica

### Sistema real
- opera con `GA`
- conserva `ACO` para comparacion tecnica y exposicion del codigo
