# Benchmark Exhaustivo de Algoritmos

Generado: 2026-04-04T23:39:00
Ganador global: **Genetic Algorithm**
Muestra total: 75 corridas

## Defaults aplicados
- Semillas por escenario: 5
- Tamaños de demanda: small/medium/large
- Ponderaciones: completed 30%, avgTransit 25%, deadlineMiss 20%, replanSuccess 10%, cost 10%, saturated 5%

## Perfil ganador
- Perfil: `GA-P1`
- Familia algoritmo: `Genetic Algorithm`
- Score compuesto: `85.68`
- Completed % promedio: `100.00`
- Avg transit hours promedio: `13.55`
- Deadline miss rate promedio: `0.00`
- Replan success % promedio: `100.00`

## Fundamentos de elección
- Maximiza score multi-criterio ponderado bajo escenarios normales, picos, colapso, disrupción y recuperación.
- Prioriza cumplimiento y tiempo de tránsito sin ignorar resiliencia (replanificación) ni costo operacional.
- Mantiene desempeño estable en diferentes tamaños de demanda y múltiples semillas.

## Ganador por escenario
| Escenario | Ganador | Completed % | Avg Transit h | Deadline Miss % | Score |
|---|---|---:|---:|---:|---:|
| NORMAL | Genetic Algorithm | 100.00 | 13.61 | 0.00 | 85.65 |
| PEAK | Genetic Algorithm | 100.00 | 13.54 | 0.00 | 85.68 |
| COLLAPSE | Genetic Algorithm | 100.00 | 13.53 | 0.00 | 85.65 |
| DISRUPTION | Genetic Algorithm | 100.00 | 13.47 | 0.00 | 85.74 |
| RECOVERY | Genetic Algorithm | 100.00 | 13.61 | 0.00 | 85.66 |

## Archivos de evidencia
- Datos de demanda: `documentacion/datasets/demand_scenarios.csv`
- Resultados exhaustivos tabulares: `documentacion/benchmark-results.csv`
- Resultado completo estructurado: `documentacion/benchmark-results.json`

## Notas
- Corridas registradas: 300
- El algoritmo elegido surge del score compuesto y no de una única métrica aislada.