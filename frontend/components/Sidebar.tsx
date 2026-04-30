'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';

function HomeIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M3 11.5L12 4l9 7.5" />
      <path d="M5 10.5V20h14v-9.5" />
      <path d="M10 20v-6h4v6" />
    </svg>
  );
}

function ShipmentIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M3 8h18v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8z" />
      <path d="M9 8V6a3 3 0 0 1 6 0v2" />
    </svg>
  );
}

function ReportIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M4 20h16" />
      <path d="M7 20V11" />
      <path d="M12 20V7" />
      <path d="M17 20V14" />
    </svg>
  );
}

function ImportIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
      <polyline points="7 10 12 15 17 10" />
      <line x1="12" y1="15" x2="12" y2="3" />
    </svg>
  );
}

function FlightIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M2 16l20-8-8 20-2-8-8-4z" />
    </svg>
  );
}

function BrandMark() {
  return (
    <div className="brand-mark" aria-hidden="true">
      R
    </div>
  );
}

const NAV_MAIN = [
  { href: '/', label: 'Inicio', Icon: HomeIcon },
  { href: '/shipments', label: 'Envios', Icon: ShipmentIcon },
  { href: '/import', label: 'Importar', Icon: ImportIcon },
];

const NAV_OPERATIONS = [
  { href: '/flights', label: 'Vuelos', Icon: FlightIcon },
  { href: '/reports', label: 'Reportes', Icon: ReportIcon },
];

function NavSection({
  title,
  items,
  pathname,
}: {
  title: string;
  items: ReadonlyArray<{ href: string; label: string; Icon: () => React.JSX.Element }>;
  pathname: string;
}) {
  return (
    <section className="sidebar-section">
      <p className="sidebar-section-title">{title}</p>
      <div className="sidebar-nav-group">
        {items.map(({ href, label, Icon }) => {
          const active = href === '/' ? pathname === '/' : pathname.startsWith(href);
          return (
            <Link
              key={href}
              href={href}
              className={`sidebar-nav-item${active ? ' is-active' : ''}`}
            >
              <Icon />
              <span>{label}</span>
            </Link>
          );
        })}
      </div>
    </section>
  );
}

export default function Sidebar() {
  const pathname = usePathname();

  return (
    <aside className="sidebar-shell">
      <div className="sidebar-header">
        <BrandMark />
        <div>
          <p className="sidebar-brand-title">Tasf.B2B</p>
        </div>
      </div>

      <div className="sidebar-content">
        <NavSection title="PRINCIPAL" items={NAV_MAIN} pathname={pathname} />
        <NavSection title="OPERACIONES" items={NAV_OPERATIONS} pathname={pathname} />
      </div>

      <div className="sidebar-footer">
        <div className="sidebar-avatar" aria-hidden="true">ER</div>
        <div>
          <p className="sidebar-user-name">Esteban Ramirez</p>
          <p className="sidebar-user-role">Operador Logistico</p>
        </div>
      </div>
    </aside>
  );
}
