import type { Metadata } from 'next';
import { Geist, Geist_Mono } from 'next/font/google';
import Sidebar from '@/components/Sidebar';
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
      <body className="h-full flex" style={{ background: '#121217', color: '#f0f0f8' }}>

        {/* Fixed sidebar */}
        <Sidebar />

        {/* Scrollable content area, offset by sidebar width */}
        <div className="flex-1 min-h-screen overflow-auto" style={{ marginLeft: '240px' }}>
          <main className="min-h-screen p-8">
            {children}
          </main>
        </div>

      </body>
    </html>
  );
}
