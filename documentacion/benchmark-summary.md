# Benchmark Exhaustivo de Algoritmos

Generado: 2026-04-05T12:26:36
Ganador global: **Ant Colony Optimization**
Muestra total: 11 corridas

## Defaults aplicados
- Semillas por escenario: 2
- Tamaños de demanda: 100 / 300 / 600
- Ponderaciones: completed 30%, avgTransit 25%, deadlineMiss 20%, replanSuccess 10%, cost 10%, saturated 5%

## Perfil ganador
- Perfil: `ACO-P1`
- Familia algoritmo: `Ant Colony Optimization`
- Score compuesto: `86.12`
- Completed % promedio: `100.00`
- Avg transit hours promedio: `12.18`
- Deadline miss rate promedio: `0.00`
- Replan success % promedio: `100.00`
- IC95 winner score: `[86.09, 86.15]`
- Delta vs runner-up: `0.05`

## Fundamentos de elección
- Maximiza score multi-criterio ponderado bajo escenarios normales, picos, colapso, disrupción y recuperación.
- Prioriza cumplimiento y tiempo de tránsito sin ignorar resiliencia (replanificación) ni costo operacional.
- Mantiene desempeño estable en diferentes tamaños de demanda y múltiples semillas.

## Ganador por escenario
| Escenario | Ganador | Completed % | Avg Transit h | Deadline Miss % | Score |
|---|---|---:|---:|---:|---:|
| NORMAL | Ant Colony Optimization | 100.00 | 12.20 | 0.00 | 86.15 |
| PEAK | Ant Colony Optimization | 100.00 | 12.17 | 0.00 | 86.08 |

## Archivos de evidencia
- Datos de demanda: `documentacion/datasets/demand_scenarios.csv`
- Resultados exhaustivos tabulares: `documentacion/benchmark-results.csv`
- Resultado completo estructurado: `documentacion/benchmark-results.json`

## Notas
- Corridas registradas: 59
- El algoritmo elegido surge del score compuesto y no de una única métrica aislada.