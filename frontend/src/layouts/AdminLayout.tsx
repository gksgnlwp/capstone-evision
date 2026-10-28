import { Link, NavLink, Outlet } from 'react-router-dom';
import { Activity, BarChart3, BrainCircuit, Database, Gauge, LogIn, ArrowUpRight, Zap } from 'lucide-react';
import { screens } from '../app/screens';
import { EnvironmentBadge } from '../components/EnvironmentBadge';

const icons = [LogIn, Activity, BarChart3, Gauge, Database, BrainCircuit];
export function AdminLayout() {
  return <div className="admin-shell">
    <aside className="admin-sidebar">
      <Link to="/admin" className="admin-brand"><Zap size={24} aria-hidden="true" /><span>EVISION<small>OPERATIONS</small></span></Link>
      <nav aria-label="운영 메뉴">{screens.filter(s => s.audience === 'admin').map((screen, i) => {
        const Icon = icons[i];
        return <NavLink key={screen.id} to={screen.path} className={({ isActive }) => `admin-nav-item ${isActive ? 'is-active' : ''}`}><Icon size={18} aria-hidden="true" />{screen.title}</NavLink>;
      })}</nav>
      <div className="admin-sidebar__bottom"><Link to="/">사용자 화면 <ArrowUpRight size={14} aria-hidden="true" /></Link></div>
    </aside>
    <div className="admin-workspace"><header className="admin-header"><span>운영 대시보드</span><EnvironmentBadge /></header>
      <main id="main-content" tabIndex={-1} className="admin-content"><Outlet /></main>
    </div>
  </div>;
}
