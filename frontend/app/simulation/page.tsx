'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { simulationApi } from '@/lib/api/simulationApi';
import type { AlgorithmRaceReport, AlgorithmType, SimScenario, SimulationResults, SimulationState } from '@/lib/types';
import type { CSSProperties } from 'react';

const PARAM_LABEL_STYLE: CSSProperties = {
  color: '#9ca3bf',
  fontSize: 13,
};

type ScenarioCard = {
  value: SimScenario;
  title: string;
  description: string;
};

const SCENARIOS: ScenarioCard[] = [
  {
    value: 'DAY_TO_DAY',
    title: 'Dia a dia',
    description: 'Monitoreo en tiempo real de operaciones activas.',
  },
  {
    value: 'PERIOD_SIMULATION',
    title: 'Simulacion por periodo',
    description: 'Simula operaciones de 3, 5 o 7 dias en 30-90 minutos.',
  },
  {
    value: 'COLLAPSE_TEST',
    title: 'Simulacion de colapso',
    description: 'Prueba de estres para evaluar capacidad y replanificacion.',
  },
];

function algoLabel(algo: AlgorithmType): string {
  return algo === 'GENETIC' ? 'Genetico (GA)' : 'Colonia Hormigas (ACO)';
}

function fmtPct(value: number): string {
  return `${value.toFixed(1)}%`;
}

export default function SimulationPage() {
  const [state, setState] = useState<SimulationState | null>(null);
  const [results, setResults] = useState<SimulationResults | null>(null);
  const [raceReport, setRaceReport] = useState<AlgorithmRaceReport | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [scenario, setScenario] = useState<SimScenario>('DAY_TO_DAY');
  const [simulationDays, setSimulationDays] = useState(5);
  const [executionMinutes, setExecutionMinutes] = useState(60);
  const [initialVolumeAvg, setInitialVolumeAvg] = useState(8);
  const [initialVolumeVariance, setInitialVolumeVariance] = useState(3);
  const [flightFrequencyMultiplier, setFlightFrequencyMultiplier] = useState(1);
  const [cancellationRatePct, setCancellationRatePct] = useState(5);
  const [intraNodeCapacity, setIntraNodeCapacity] = useState(700);
  const [interNodeCapacity, setInterNodeCapacity] = useState(800);
  const [normalThresholdPct, setNormalThresholdPct] = useState(70);
  const [warningThresholdPct, setWarningThresholdPct] = useState(90);
  const [primaryAlgorithm, setPrimaryAlgorithm] = useState<AlgorithmType>('GENETIC');
  const [secondaryAlgorithm, setSecondaryAlgorithm] = useState<AlgorithmType>('ANT_COLONY');

  const [startedAtLabel, setStartedAtLabel] = useState<string>('-');

  const loadAll = useCallback(async () => {
    const [stateResult, resultResult] = await Promise.allSettled([
      simulationApi.getState(),
      simulationApi.getResults(),
    ]);

    if (stateResult.status === 'fulfilled') {
      const value = stateResult.value;
      setState(value);
      setScenario(value.scenario);
      setSimulationDays(value.simulationDays);
      setExecutionMinutes(value.executionMinutes);
      setInitialVolumeAvg(value.initialVolumeAvg);
      setInitialVolumeVariance(value.initialVolumeVariance);
      setFlightFrequencyMultiplier(value.flightFrequencyMultiplier);
      setCancellationRatePct(value.cancellationRatePct);
      setIntraNodeCapacity(value.intraNodeCapacity);
      setInterNodeCapacity(value.interNodeCapacity);
      setNormalThresholdPct(value.normalThresholdPct);
      setWarningThresholdPct(value.warningThresholdPct);
      setPrimaryAlgorithm(value.primaryAlgorithm);
      setSecondaryAlgorithm(value.secondaryAlgorithm);
      setError(null);
    }

    if (resultResult.status === 'fulfilled') {
      setResults(resultResult.value);
      setError(null);
    }

    const raceResult = await Promise.allSettled([
      simulationApi.getRaceReport(),
    ]);
    if (raceResult[0].status === 'fulfilled') {
      setRaceReport(raceResult[0].value);
    }

    if (stateResult.status === 'rejected' && resultResult.status === 'rejected') {
      setError('No se pudo cargar la simulacion desde backend.');
    }

    setLoading(false);
  }, []);

  useEffect(() => {
    const initial = setTimeout(() => {
      void loadAll();
    }, 0);
    const interval = setInterval(() => {
      void loadAll();
    }, 15_000);
    return () => {
      clearTimeout(initial);
      clearInterval(interval);
    };
  }, [loadAll]);

  useEffect(() => {
    if (!state?.startedAt) {
      setStartedAtLabel('-');
      return;
    }
    setStartedAtLabel(
      new Date(state.startedAt).toLocaleString('es-PE', {
        day: '2-digit',
        month: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
      })
    );
  }, [state?.startedAt]);

  async function refreshNow(): Promise<void> {
    await loadAll();
  }

  async function saveConfig(): Promise<void> {
    setSaving(true);
    try {
      await simulationApi.configure({
        scenario,
        simulationDays,
        executionMinutes,
        initialVolumeAvg,
        initialVolumeVariance,
        flightFrequencyMultiplier,
        cancellationRatePct,
        intraNodeCapacity,
        interNodeCapacity,
        normalThresholdPct,
        warningThresholdPct,
        primaryAlgorithm,
        secondaryAlgorithm,
      });
      await loadAll();
    } catch {
      setError('No se pudo guardar la configuracion.');
    } finally {
      setSaving(false);
    }
  }

  async function startSimulation(): Promise<void> {
    try {
      await simulationApi.start();
      await loadAll();
    } catch {
      setError('No se pudo iniciar la simulacion.');
    }
  }

  async function stopSimulation(): Promise<void> {
    try {
      await simulationApi.stop();
      await loadAll();
    } catch {
      setError('No se pudo detener la simulacion.');
    }
  }

  async function changeSpeed(speed: number): Promise<void> {
    try {
      await simulationApi.setSpeed(speed);
      await loadAll();
    } catch {
      setError('No se pudo cambiar la velocidad.');
    }
  }

  async function injectCancelEvent(): Promise<void> {
    try {
      await simulationApi.injectEvent({
        type: 'INCREASE_VOLUME',
        eventValue: 25,
        note: 'Inyeccion manual de carga',
      });
      await loadAll();
    } catch {
      setError('No se pudo inyectar el evento.');
    }
  }

  async function seedStatistical(): Promise<void> {
    try {
      await simulationApi.seedStatistical(initialVolumeAvg, initialVolumeVariance);
      await loadAll();
    } catch {
      setError('No se pudo generar volumen estadistico.');
    }
  }

  const ga = useMemo(() => results?.algorithms?.['Genetic Algorithm'], [results]);
  const aco = useMemo(() => results?.algorithms?.['Ant Colony Optimization'], [results]);

  return (
    <div className="app-page">
      <header className="page-head">
        <div>
          <h1 className="page-head-title">Simulacion</h1>
          <p className="page-head-subtitle">Configuracion y ejecucion de escenarios</p>
        </div>

        <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
          <button className="btn btn-primary" onClick={startSimulation} disabled={Boolean(state?.running && !state?.paused)}>
            {state?.running && !state?.paused ? 'En ejecucion' : 'Iniciar'}
          </button>
          <button className="btn btn-danger" onClick={stopSimulation} disabled={!state?.running && !state?.paused}>Detener</button>
          <button className="btn btn-neutral" onClick={injectCancelEvent}>Inyectar evento</button>
          <button className="chip" onClick={() => void simulationApi.exportResults('csv')}>Exportar CSV</button>
          <button className="chip" onClick={() => void simulationApi.exportResults('pdf')}>Exportar PDF</button>
          <button className="chip" onClick={refreshNow}>Actualizar</button>
        </div>
      </header>

      <div className="sim-layout">
        <section>
          <p className="sim-section-title">Seleccionar Escenario</p>
          <div className="scenario-grid">
            {SCENARIOS.map((card) => {
              const active = scenario === card.value;
              return (
                <button
                  key={card.value}
                  onClick={() => setScenario(card.value)}
                  className="surface-panel"
                  style={{
                    minHeight: 132,
                    textAlign: 'left',
                    padding: 14,
                    borderColor: active ? 'rgba(95,130,255,0.8)' : '#32364f',
                    background: active ? 'rgba(95,130,255,0.3)' : 'linear-gradient(180deg, #1f2234 0%, #1a1d2d 100%)',
                    color: '#eef1ff',
                    cursor: 'pointer',
                  }}
                >
                  <p className="scenario-title">{card.title}</p>
                  <p className="scenario-desc">{card.description}</p>
                </button>
              );
            })}
          </div>
        </section>

        <section className="sim-panels">
          <div className="surface-panel" style={{ padding: 20 }}>
            <p className="sim-panel-title">Parametros de Simulacion</p>

            <div style={{ marginTop: 16, display: 'grid', gap: 12 }}>
              <label style={{ display: 'grid', gap: 6 }}>
                <span style={PARAM_LABEL_STYLE}>Duracion simulacion (dias)</span>
                <input
                  type="number"
                  min={1}
                  max={30}
                  value={simulationDays}
                  onChange={(e) => setSimulationDays(Number(e.target.value))}
                  style={fieldStyle}
                />
              </label>

              <label style={{ display: 'grid', gap: 6 }}>
                <span style={PARAM_LABEL_STYLE}>Tiempo de ejecucion (min)</span>
                <input
                  type="number"
                  min={10}
                  max={180}
                  value={executionMinutes}
                  onChange={(e) => setExecutionMinutes(Number(e.target.value))}
                  style={fieldStyle}
                />
              </label>

              <label style={{ display: 'grid', gap: 6 }}>
                <span style={PARAM_LABEL_STYLE}>Volumen inicial promedio</span>
                <input type="number" min={1} max={500} value={initialVolumeAvg} onChange={(e) => setInitialVolumeAvg(Number(e.target.value))} style={fieldStyle} />
              </label>

              <label style={{ display: 'grid', gap: 6 }}>
                <span style={PARAM_LABEL_STYLE}>Varianza esperada</span>
                <input type="number" min={0} max={300} value={initialVolumeVariance} onChange={(e) => setInitialVolumeVariance(Number(e.target.value))} style={fieldStyle} />
              </label>

              <label style={{ display: 'grid', gap: 6 }}>
                <span style={PARAM_LABEL_STYLE}>Frecuencia de vuelos (x)</span>
                <input type="number" min={1} max={10} value={flightFrequencyMultiplier} onChange={(e) => setFlightFrequencyMultiplier(Number(e.target.value))} style={fieldStyle} />
              </label>

              <label style={{ display: 'grid', gap: 6 }}>
                <span style={PARAM_LABEL_STYLE}>Tasa cancelaciones (%)</span>
                <input type="number" min={0} max={100} value={cancellationRatePct} onChange={(e) => setCancellationRatePct(Number(e.target.value))} style={fieldStyle} />
              </label>

              <label style={{ display: 'grid', gap: 6 }}>
                <span style={PARAM_LABEL_STYLE}>Capacidad nodos intra</span>
                <input type="number" min={200} max={5000} value={intraNodeCapacity} onChange={(e) => setIntraNodeCapacity(Number(e.target.value))} style={fieldStyle} />
              </label>

              <label style={{ display: 'grid', gap: 6 }}>
                <span style={PARAM_LABEL_STYLE}>Capacidad nodos inter</span>
                <input type="number" min={200} max={5000} value={interNodeCapacity} onChange={(e) => setInterNodeCapacity(Number(e.target.value))} style={fieldStyle} />
              </label>

              <label style={{ display: 'grid', gap: 6 }}>
                <span style={PARAM_LABEL_STYLE}>Algoritmo primario</span>
                <select
                  value={primaryAlgorithm}
                  onChange={(e) => setPrimaryAlgorithm(e.target.value as AlgorithmType)}
                  style={fieldStyle}
                >
                  <option value="GENETIC">Genetico (GA)</option>
                  <option value="ANT_COLONY">Colonia Hormigas (ACO)</option>
                </select>
              </label>

              <label style={{ display: 'grid', gap: 6 }}>
                <span style={PARAM_LABEL_STYLE}>Algoritmo secundario</span>
                <select
                  value={secondaryAlgorithm}
                  onChange={(e) => setSecondaryAlgorithm(e.target.value as AlgorithmType)}
                  style={fieldStyle}
                >
                  <option value="GENETIC">Genetico (GA)</option>
                  <option value="ANT_COLONY">Colonia Hormigas (ACO)</option>
                </select>
              </label>

              <label style={{ display: 'grid', gap: 6 }}>
                <span style={PARAM_LABEL_STYLE}>Umbral normal (%)</span>
                <input
                  type="number"
                  min={50}
                  max={99}
                  value={normalThresholdPct}
                  onChange={(e) => setNormalThresholdPct(Number(e.target.value))}
                  style={fieldStyle}
                />
              </label>

              <label style={{ display: 'grid', gap: 6 }}>
                <span style={PARAM_LABEL_STYLE}>Umbral alerta (%)</span>
                <input
                  type="number"
                  min={51}
                  max={100}
                  value={warningThresholdPct}
                  onChange={(e) => setWarningThresholdPct(Number(e.target.value))}
                  style={fieldStyle}
                />
              </label>
            </div>

            <div style={{ marginTop: 16, display: 'flex', gap: 10, flexWrap: 'wrap' }}>
              <button className="btn btn-primary" onClick={saveConfig} disabled={saving}>
                {saving ? 'Guardando...' : 'Guardar'}
              </button>
              <button className="chip" onClick={seedStatistical}>Generar volumen estadistico</button>
              {[1, 5, 10].map((speedValue) => (
                <button
                  key={speedValue}
                  className={`chip${state?.speed === speedValue ? ' is-active' : ''}`}
                  onClick={() => changeSpeed(speedValue)}
                >
                  Velocidad {speedValue}x
                </button>
              ))}
            </div>
          </div>

          <div className="surface-panel" style={{ padding: 20 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <p className="sim-panel-title">Resultados</p>
              <span className="chip is-active">
                {state?.running ? 'Simulacion activa' : state?.paused ? 'Pausada' : 'Detenida'}
              </span>
            </div>

            <div style={{ marginTop: 16, overflowX: 'auto' }}>
              <table className="data-table">
                <thead>
                  <tr>
                    <th>Metrica</th>
                    <th>Genetico (GA)</th>
                    <th>Hormigas (ACO)</th>
                  </tr>
                </thead>
                <tbody>
                  <tr>
                    <td>Envios completados</td>
                    <td>{ga ? fmtPct(ga.completedPct) : '-'}</td>
                    <td>{aco ? fmtPct(aco.completedPct) : '-'}</td>
                  </tr>
                  <tr>
                    <td>Tiempo promedio</td>
                    <td>{ga ? `${ga.avgTransitHours.toFixed(1)}h` : '-'}</td>
                    <td>{aco ? `${aco.avgTransitHours.toFixed(1)}h` : '-'}</td>
                  </tr>
                  <tr>
                    <td>Replanificaciones</td>
                    <td>{ga?.totalReplanning ?? '-'}</td>
                    <td>{aco?.totalReplanning ?? '-'}</td>
                  </tr>
                  <tr>
                    <td>Costo operativo</td>
                    <td>{ga ? `$${ga.operationalCost.toLocaleString('es-PE')}` : '-'}</td>
                    <td>{aco ? `$${aco.operationalCost.toLocaleString('es-PE')}` : '-'}</td>
                  </tr>
                  <tr>
                    <td>Utilizacion vuelos</td>
                    <td>{ga ? fmtPct(ga.flightUtilizationPct) : '-'}</td>
                    <td>{aco ? fmtPct(aco.flightUtilizationPct) : '-'}</td>
                  </tr>
                  <tr>
                    <td>Aeropuertos saturados</td>
                    <td>{ga?.saturatedAirports ?? '-'}</td>
                    <td>{aco?.saturatedAirports ?? '-'}</td>
                  </tr>
                </tbody>
              </table>
            </div>

            <div style={{ marginTop: 16, display: 'grid', gap: 10, gridTemplateColumns: 'repeat(3, minmax(0, 1fr))' }}>
              <div className="surface-panel" style={{ padding: 12, background: 'rgba(67,210,157,0.18)' }}>
                <p style={{ margin: 0, color: '#aab3cf', fontSize: 12 }}>Mejor algoritmo</p>
                <p style={{ margin: '4px 0 0', fontSize: 20, fontWeight: 700 }}>
                  {ga && aco ? (ga.completedPct >= aco.completedPct ? 'GA' : 'ACO') : '-'}
                </p>
              </div>
              <div className="surface-panel" style={{ padding: 12, background: 'rgba(95,130,255,0.2)' }}>
                <p style={{ margin: 0, color: '#aab3cf', fontSize: 12 }}>Diferencia %</p>
                <p style={{ margin: '4px 0 0', fontSize: 20, fontWeight: 700 }}>
                  {ga && aco ? fmtPct(Math.abs(ga.completedPct - aco.completedPct)) : '-'}
                </p>
              </div>
              <div className="surface-panel" style={{ padding: 12, background: 'rgba(240,193,58,0.18)' }}>
                <p style={{ margin: 0, color: '#aab3cf', fontSize: 12 }}>Alertas</p>
                <p style={{ margin: '4px 0 0', fontSize: 20, fontWeight: 700 }}>
                  {results?.kpis.critical ?? 0}
                </p>
              </div>
            </div>

            <div className="surface-panel" style={{ marginTop: 12, padding: 10, background: 'rgba(95,130,255,0.14)' }}>
              <p style={{ margin: 0, fontSize: 12, color: '#aab3cf' }}>Benchmark recomendado</p>
              <p style={{ margin: '4px 0 0', fontSize: 16, fontWeight: 700 }}>
                {results?.benchmarkWinner ?? 'N/A'}
              </p>
            </div>

            <div className="surface-panel" style={{ marginTop: 10, padding: 10 }}>
              <p style={{ margin: 0, fontSize: 12, color: '#aab3cf' }}>Race report</p>
              <div style={{ marginTop: 8, overflowX: 'auto' }}>
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>Algoritmo</th>
                      <th>Completed%</th>
                      <th>Avg h</th>
                      <th>P95 h</th>
                      <th>Costo/maleta</th>
                    </tr>
                  </thead>
                  <tbody>
                    {(raceReport?.metrics ?? []).map((metric) => (
                      <tr key={metric.algorithmName}>
                        <td>{metric.algorithmName}</td>
                        <td>{metric.completedPct.toFixed(1)}%</td>
                        <td>{metric.avgTransitHours.toFixed(1)}h</td>
                        <td>{metric.p95TransitHours.toFixed(1)}h</td>
                        <td>${metric.costPerLuggage.toFixed(2)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>

            <div style={{ marginTop: 14, color: '#9ca3bf', fontSize: 13 }}>
              Estado runtime: velocidad {state?.speed ?? 1}x - replanificaciones {state?.replannings ?? 0} - eventos {state?.injectedEvents ?? 0}
            </div>

            <div style={{ marginTop: 6, color: '#6f7693', fontSize: 12 }}>
              Inicio simulado: {startedAtLabel}
            </div>
          </div>
        </section>

        {loading && (
          <div className="state-panel">
            <p className="state-panel-title">Cargando simulacion</p>
            <p className="state-panel-copy">Leyendo configuracion runtime y resultados de algoritmos.</p>
          </div>
        )}
        {error && (
          <div className="state-panel is-error">
            <p className="state-panel-title">Error de simulacion</p>
            <p className="state-panel-copy">{error}</p>
          </div>
        )}

        <div className="surface-panel" style={{ padding: 14, color: '#aab3cf', fontSize: 13 }}>
          {state ? `Escenario: ${state.scenario} | Algoritmo primario: ${algoLabel(state.primaryAlgorithm)}` : 'Sin datos de estado'}
        </div>
      </div>
    </div>
  );
}

const fieldStyle: CSSProperties = {
  height: 40,
  borderRadius: 10,
  border: '1px solid #32364f',
  background: '#20243a',
  color: '#eef1ff',
  padding: '0 12px',
  fontSize: 13,
  outline: 'none',
};
