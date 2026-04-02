'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';

// ── Icons (inline SVG) ────────────────────────────────────────────────────

function IconDashboard() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <rect x="3" y="3" width="7" height="7" rx="1.5"/>
      <rect x="14" y="3" width="7" height="7" rx="1.5"/>
      <rect x="3" y="14" width="7" height="7" rx="1.5"/>
      <rect x="14" y="14" width="7" height="7" rx="1.5"/>
    </svg>
  );
}
function IconImport() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4"/>
      <polyline points="17 8 12 3 7 8"/>
      <line x1="12" y1="3" x2="12" y2="15"/>
    </svg>
  );
}
function IconSimulation() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="12" cy="12" r="9"/>
      <polygon points="10 8 16 12 10 16 10 8" fill="currentColor" stroke="none"/>
    </svg>
  );
}
function IconReports() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <line x1="18" y1="20" x2="18" y2="10"/>
      <line x1="12" y1="20" x2="12" y2="4"/>
      <line x1="6"  y1="20" x2="6"  y2="14"/>
    </svg>
  );
}
function IconPlane() {
  return (
    <svg width="22" height="22" viewBox="0 0 24 24" fill="currentColor">
      <path d="M21 16v-2l-8-5V3.5A1.5 1.5 0 0011 2a1.5 1.5 0 00-1.5 1.5V9l-8 5v2l8-2.5V19l-2 1.5V22l3.5-1 3.5 1v-1.5L13 19v-5.5l8 2.5z"/>
    </svg>
  );
}

// ── Nav items ─────────────────────────────────────────────────────────────

const NAV = [
  { href: '/',           label: 'Panel de Control', Icon: IconDashboard },
  { href: '/import',     label: 'Importar Datos',   Icon: IconImport    },
  { href: '/simulation', label: 'Simulación',        Icon: IconSimulation },
  { href: '/reports',    label: 'Reportes',          Icon: IconReports   },
];

// ── Component ─────────────────────────────────────────────────────────────

export default function Sidebar() {
  const pathname = usePathname();

  return (
    <aside className="fixed top-0 left-0 h-screen w-60 flex flex-col z-40"
           style={{ background: '#1c1c24', borderRight: '1px solid #2d2d40' }}>

      {/* Logo */}
      <div className="px-6 py-5 flex items-center gap-3"
           style={{ borderBottom: '1px solid #2d2d40' }}>
        <div className="w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0"
             style={{ background: 'rgba(102,133,255,0.18)' }}>
          <span style={{ color: '#6685ff' }}><IconPlane /></span>
        </div>
        <div>
          <p className="text-sm font-bold leading-none" style={{ color: '#f0f0f8' }}>
            Tasf.B2B
          </p>
          <p className="text-[10px] mt-0.5 leading-none" style={{ color: '#8484a0' }}>
            Traslado de Maletas
          </p>
        </div>
      </div>

      {/* Navigation */}
      <nav className="flex-1 px-3 py-4 flex flex-col gap-0.5">
        <p className="px-3 pb-2 text-[10px] font-semibold uppercase tracking-widest"
           style={{ color: '#4a4a60' }}>
          Menú Principal
        </p>

        {NAV.map(({ href, label, Icon }) => {
          const active = href === '/' ? pathname === '/' : pathname.startsWith(href);
          return (
            <Link key={href} href={href}
                  className="flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm transition-colors"
                  style={{
                    color:      active ? '#6685ff' : '#8484a0',
                    background: active ? 'rgba(102,133,255,0.12)' : 'transparent',
                    fontWeight: active ? 600 : 400,
                  }}
                  onMouseEnter={e => {
                    if (!active) {
                      (e.currentTarget as HTMLElement).style.color = '#f0f0f8';
                      (e.currentTarget as HTMLElement).style.background = 'rgba(255,255,255,0.05)';
                    }
                  }}
                  onMouseLeave={e => {
                    if (!active) {
                      (e.currentTarget as HTMLElement).style.color = '#8484a0';
                      (e.currentTarget as HTMLElement).style.background = 'transparent';
                    }
                  }}>
              <Icon />
              <span>{label}</span>
              {active && (
                <span className="ml-auto w-1.5 h-1.5 rounded-full flex-shrink-0"
                      style={{ background: '#6685ff' }} />
              )}
            </Link>
          );
        })}
      </nav>

      {/* Footer */}
      <div className="px-6 py-4" style={{ borderTop: '1px solid #2d2d40' }}>
        <p className="text-[10px]" style={{ color: '#4a4a60' }}>
          PUCP · Logística Aérea
        </p>
        <p className="text-[10px]" style={{ color: '#4a4a60' }}>
          v0.1.0 · Spring Boot 3 + Next.js 15
        </p>
      </div>
    </aside>
  );
}
