import { Home, Map, Sparkles, Star, Settings, Zap, ArrowLeft } from 'lucide-react';
import { Link, NavLink, Outlet, useLocation } from 'react-router-dom';
import { EnvironmentBadge } from '../components/EnvironmentBadge';

const navigation = [
  { path: '/', label: '홈', icon: Home, end: true },
  { path: '/map', label: '지도', icon: Map },
  { path: '/recommendations', label: '추천', icon: Sparkles },
  { path: '/favorites', label: '즐겨찾기', icon: Star },
  { path: '/settings', label: '설정', icon: Settings },
];

export function MobileLayout() {
  const { pathname } = useLocation();
  return <div className="mobile-shell">
    <header className="app-header">
      <div className="row">
        {pathname !== '/' && <Link to="/" className="icon-button back-button" aria-label="홈으로 돌아가기"><ArrowLeft size={20} /></Link>}
        <Link to="/" className="brand" aria-label="EVISION 충전예보 홈"><span className="brand__name"><Zap size={21} fill="currentColor" aria-hidden="true" /> 충전예보</span><span className="brand__subtitle">고속도로 장거리 충전 계획</span></Link>
      </div>
      <EnvironmentBadge />
    </header>
    <main id="main-content" tabIndex={-1} className="mobile-content">
      <Outlet />
    </main>
    <nav className="bottom-nav" aria-label="주요 메뉴">
      {navigation.map(({ path, label, icon: Icon, end }) => <NavLink key={path} to={path} end={end} className={({ isActive }) => `bottom-nav__item ${isActive ? 'is-active' : ''}`}><Icon size={21} strokeWidth={1.8} aria-hidden="true" /><span>{label}</span></NavLink>)}
    </nav>
  </div>;
}
