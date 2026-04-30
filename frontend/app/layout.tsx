import type { Metadata } from 'next';
import { Geist, Geist_Mono } from 'next/font/google';
import Sidebar from '@/components/Sidebar';
import { SimulationProvider } from '@/lib/SimulationContext';
import 'maplibre-gl/dist/maplibre-gl.css';
import './globals.css';

const geistSans = Geist({ variable: '--font-geist-sans', subsets: ['latin'] });
const geistMono = Geist_Mono({ variable: '--font-geist-mono', subsets: ['latin'] });

export const metadata: Metadata = {
  title: 'Tasf.B2B – Sistema de Traslado de Maletas',
  description: 'Gestión logística aérea · PUCP',
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="es" className={`${geistSans.variable} ${geistMono.variable} h-full`}>
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
