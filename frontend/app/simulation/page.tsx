'use client';

import { useCallback, useEffect, useState } from 'react';
import { simulationApi } from '@/lib/api/simulationApi';
import type { SimScenario, SimulationKpis, SimulationResults, SimulationState } from '@/lib/types';
import type { CSSProperties } from 'react';

const PARAM_LABEL_STYLE: CSSProperties = {
  color: '#9ca3bf',
  fontSize: 13,
};

type ScenarioCard = {
  value: SimScenario;
  title: string;
  description: string;
  hint: string;
};

const SCENARIOS: ScenarioCard[] = [
  {
    value: 'DAY_TO_DAY',
    title: 'Dia a dia',
    description: 'Monitoreo en tiempo real de operaciones activas.',
    hint: 'Operacion continua y monitoreo permanente en mapa.',
  },
  {
    value: 'PERIOD_SIMULATION',
    title: 'Simulacion por periodo',
    description: 'Simula operaciones de 3, 5 o 7 dias en 30-90 minutos.',
    hint: 'Analiza backlog y cumplimiento en una ventana acelerada.',
  },
  {
    value: 'COLLAPSE_TEST',
    title: 'Simulacion de colapso',
    description: 'Prueba de estres para evaluar capacidad y replanificacion.',
    hint: 'Foco en saturacion, cuellos de botella y riesgo de colapso.',
  },
];

function scenarioName(value: SimScenario): string {
  if (value === 'PERIOD_SIMULATION') return 'Simulacion por periodo';
  if (value === 'COLLAPSE_TEST') return 'Simulacion de colapso';
  return 'Dia a dia';
}

function fmtPct(value: number): string {
  return `${value.toFixed(1)}%`;
}

export default function SimulationPage() {
  const [state, setState] = useState<SimulationState | null>(null);
  const [kpis, setKpis] = useState<SimulationKpis | null>(null);
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
  const [pendingScenario, setPendingScenario] = useState<SimScenario>('DAY_TO_DAY');

  const [startedAtLabel, setStartedAtLabel] = useState<string>('-');

  const loadAll = useCallback(async () => {
    const [stateResult, resultResult] = await Promise.allSettled([
      simulationApi.getState(),
      simulationApi.getResults(),
    ]);

    let partialError: string | null = null;

    if (stateResult.status === 'fulfilled') {
      const value = stateResult.value;
      setState(value);
      setScenario(value.scenario);
      setPendingScenario(value.scenario);
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
    } else {
      partialError = 'No se pudo cargar el estado de simulacion.';
    }

    if (resultResult.status === 'fulfilled') {
      const payload = resultResult.value as SimulationResults;
      setKpis(payload.kpis);
    } else {
      partialError = partialError
        ? partialError + ' Tampoco se pudieron cargar los KPIs.'
        : 'No se pudieron cargar los KPIs.';
    }

    setError(partialError);

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
      });
      await loadAll();
    } catch {
      setError('No se pudo guardar la configuracion. Valores restaurados.');
      await loadAll();
    } finally {
      setSaving(false);
    }
  }

  const scenarioDirty = pendingScenario !== scenario;

  async function applyScenario(nextScenario?: SimScenario, autoStart?: boolean): Promise<void> {
    const target = nextScenario ?? pendingScenario;
    setSaving(true);
    setError(null);
    try {
      await simulationApi.resetToInitial();

      const configured = await simulationApi.configure({
        scenario: target,
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
      });

      setState(configured);
      setScenario(target);
      if (autoStart !== false) {
        const started = await simulationApi.start();
        setState(started.state);
      }
      void loadAll();
    } catch {
      setError('No se pudo aplicar el escenario seleccionado.');
    } finally {
      setSaving(false);
    }
  }

  async function startSimulation(): Promise<void> {
    try {
      const res = await simulationApi.start();
      setState(res.state);
      setError(null);
      void loadAll();
    } catch {
      setError('No se pudo iniciar la simulacion.');
    }
  }

  async function stopSimulation(): Promise<void> {
    try {
      const res = await simulationApi.resetToInitial();
      setState(res.state);
      setError(null);
      void loadAll();
    } catch {
      setError('No se pudo detener la simulacion.');
    }
  }

  async function changeSpeed(speed: number): Promise<void> {
    try {
      const res = await simulationApi.setSpeed(speed);
      setState(res.state);
      setError(null);
      void loadAll();
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
          <span className="chip is-active">Escenario activo: {state ? scenarioName(state.scenario) : scenarioName(scenario)}</span>
        </div>
      </header>

      <div className="sim-layout">
        <section>
          <p className="sim-section-title">Escenario de simulacion</p>
          <div className="surface-panel" style={{ padding: 14 }}>
            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
              {SCENARIOS.map((card) => {
                const selected = pendingScenario === card.value;
                const active = state?.scenario === card.value;
                return (
                  <button
                    key={card.value}
                    className={`chip${selected ? ' is-active' : ''}`}
                    onClick={() => setPendingScenario(card.value)}
                    title={card.description}
                    style={{ borderColor: active ? '#43d29d' : undefined }}
                  >
                    {card.title}{active ? ' (activo)' : ''}
                  </button>
                );
              })}
            </div>
            <div style={{ marginTop: 10 }}>
              <p style={{ margin: 0, fontSize: 13, color: '#d9def3', fontWeight: 600 }}>
                {SCENARIOS.find((s) => s.value === pendingScenario)?.title}
              </p>
              <p style={{ margin: '4px 0 0', fontSize: 12, color: '#9ca3bf' }}>
                {SCENARIOS.find((s) => s.value === pendingScenario)?.hint}
              </p>
            </div>
            <div style={{ marginTop: 12, display: 'flex', gap: 8, flexWrap: 'wrap' }}>
              <button className="btn btn-primary" onClick={() => void applyScenario(undefined, true)} disabled={saving || !scenarioDirty}>
                {saving ? 'Aplicando...' : 'Aplicar y arrancar'}
              </button>
              <button className="chip" onClick={() => void applyScenario(undefined, false)} disabled={saving || !scenarioDirty}>
                Aplicar sin arrancar
              </button>
              {!scenarioDirty && (
                <span className="chip">Escenario ya aplicado</span>
              )}
            </div>
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
              <p className="sim-panel-title">Resumen Operativo</p>
              <span className="chip is-active">
                {state?.running ? 'Simulacion activa' : state?.paused ? 'Pausada' : 'Detenida'}
              </span>
            </div>

            <div style={{ marginTop: 16, display: 'grid', gap: 10, gridTemplateColumns: 'repeat(3, minmax(0, 1fr))' }}>
              <div className="surface-panel" style={{ padding: 12, background: 'rgba(67,210,157,0.18)' }}>
                <p style={{ margin: 0, color: '#aab3cf', fontSize: 12 }}>Cumplimiento SLA</p>
                <p style={{ margin: '4px 0 0', fontSize: 20, fontWeight: 700 }}>
                  {kpis ? fmtPct(kpis.deliveredOnTimePct) : '-'}
                </p>
              </div>
              <div className="surface-panel" style={{ padding: 12, background: 'rgba(95,130,255,0.2)' }}>
                <p style={{ margin: 0, color: '#aab3cf', fontSize: 12 }}>Envios entregados</p>
                <p style={{ margin: '4px 0 0', fontSize: 20, fontWeight: 700 }}>
                  {kpis?.delivered ?? 0}
                </p>
              </div>
              <div className="surface-panel" style={{ padding: 12, background: 'rgba(240,193,58,0.18)' }}>
                <p style={{ margin: 0, color: '#aab3cf', fontSize: 12 }}>Alertas</p>
                <p style={{ margin: '4px 0 0', fontSize: 20, fontWeight: 700 }}>
                  {kpis?.critical ?? 0}
                </p>
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
            <p className="state-panel-copy">Leyendo configuracion runtime y metricas operativas.</p>
          </div>
        )}
        {error && (
          <div className="state-panel is-error">
            <p className="state-panel-title">Error de simulacion</p>
            <p className="state-panel-copy">{error}</p>
          </div>
        )}

        <div className="surface-panel" style={{ padding: 14, color: '#aab3cf', fontSize: 13 }}>
          {state ? `Escenario: ${state.scenario}` : 'Sin datos de estado'}
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
