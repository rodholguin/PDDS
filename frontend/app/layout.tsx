import type { Metadata } from 'next';
import Sidebar from '@/components/Sidebar';
import { SimulationProvider } from '@/lib/SimulationContext';
import 'maplibre-gl/dist/maplibre-gl.css';
import './globals.css';

export const metadata: Metadata = {
  title: 'Tasf.B2B – Sistema de Traslado de Maletas',
  description: 'Gestión logística aérea · PUCP',
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="es" className="h-full">
      <body className="app-shell">
        <Sidebar />

        <SimulationProvider>
          <div className="content-shell">
            <main className="content-main">
              {children}
            </main>
          </div>
        </SimulationProvider>
      </body>
    </html>
  );
}
