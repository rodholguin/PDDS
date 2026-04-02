export default function ReportsPage() {
  return (
    <div className="max-w-4xl mx-auto">
      <div className="mb-8">
        <h1 className="text-2xl font-bold" style={{ color: '#f0f0f8' }}>Reportes</h1>
        <p className="text-sm mt-1" style={{ color: '#8484a0' }}>
          Exportación de métricas y resultados del sistema
        </p>
      </div>

      <div className="rounded-xl p-16 flex flex-col items-center justify-center text-center"
           style={{ background: '#1c1c24', border: '2px dashed #2d2d40' }}>
        <p className="text-4xl mb-4">📊</p>
        <p className="text-lg font-semibold" style={{ color: '#f0f0f8' }}>En construcción</p>
        <p className="text-sm mt-2 max-w-sm" style={{ color: '#8484a0' }}>
          Aquí se generarán reportes de rendimiento por algoritmo, envíos completados,
          aeropuertos saturados y tiempo al colapso del sistema.
        </p>
      </div>
    </div>
  );
}
