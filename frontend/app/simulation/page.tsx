export default function SimulationPage() {
  return (
    <div className="max-w-4xl mx-auto">
      <div className="mb-8">
        <h1 className="text-2xl font-bold" style={{ color: '#f0f0f8' }}>Simulación</h1>
        <p className="text-sm mt-1" style={{ color: '#8484a0' }}>
          Control del escenario de simulación y métricas anticolapso
        </p>
      </div>

      <div className="rounded-xl p-16 flex flex-col items-center justify-center text-center"
           style={{ background: '#1c1c24', border: '2px dashed #2d2d40' }}>
        <p className="text-4xl mb-4">⚙️</p>
        <p className="text-lg font-semibold" style={{ color: '#f0f0f8' }}>En construcción</p>
        <p className="text-sm mt-2 max-w-sm" style={{ color: '#8484a0' }}>
          Aquí se configurará el escenario de simulación (DAY_TO_DAY, PERIOD_SIMULATION,
          COLLAPSE_TEST) y se visualizarán los resultados comparativos de los algoritmos
          Genético y Colonia de Hormigas.
        </p>
        <div className="mt-6 grid grid-cols-3 gap-3">
          {['DAY_TO_DAY', 'PERIOD_SIMULATION', 'COLLAPSE_TEST'].map(s => (
            <span key={s} className="text-xs px-3 py-1.5 rounded-full"
                  style={{ background: '#6685ff18', color: '#8ba3ff' }}>
              {s}
            </span>
          ))}
        </div>
      </div>
    </div>
  );
}
