import { useEffect } from 'react';
import { Link, Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { MobileLayout } from '../layouts/MobileLayout';
import { AdminLayout } from '../layouts/AdminLayout';
import { HomePage } from '../pages/HomePage';
import { PlaceholderPage } from '../pages/PlaceholderPage';
import { ComponentGallery } from '../pages/ComponentGallery';
import { EmptyState } from '../components/ui';
import { screens, settingsScreen } from './screens';

function RouteEffects() {
  const { pathname } = useLocation();
  useEffect(() => {
    window.scrollTo(0, 0);
    const title = document.querySelector('h1')?.textContent || '충전예보';
    document.title = `${title} · EVISION`;
    // SPA 페이지 이동을 키보드/스크린리더 사용자에게도 알려 줍니다.
    document.getElementById('main-content')?.focus({ preventScroll: true });
  }, [pathname]);
  return null;
}

export function App() {
  return <>
    <RouteEffects />
    <a href="#main-content" className="skip-link">본문으로 이동</a>
    <Routes>
      <Route element={<MobileLayout />}>
        <Route index element={<HomePage />} />
        {screens.filter(s => s.audience === 'mobile' && s.path !== '/').map(screen =>
          <Route key={screen.id} path={screen.path} element={<PlaceholderPage screen={screen} />} />)}
        <Route path="/settings" element={<PlaceholderPage screen={settingsScreen} />} />
        {import.meta.env.DEV && <Route path="/dev/ui" element={<ComponentGallery />} />}
        <Route path="*" element={<EmptyState title="페이지를 찾을 수 없어요" description="주소를 확인하거나 홈으로 이동해 주세요." action={<Link className="button button--secondary" to="/">홈으로 이동</Link>} />} />
      </Route>
      <Route path="/admin" element={<AdminLayout />}>
        <Route index element={<Navigate to="/admin/login" replace />} />
        {screens.filter(s => s.audience === 'admin').map(screen =>
          <Route key={screen.id} path={screen.path} element={<PlaceholderPage screen={screen} />} />)}
        <Route path="*" element={<EmptyState title="운영 페이지를 찾을 수 없어요" action={<Link to="/admin/login" className="text-link">운영자 화면으로 이동</Link>} />} />
      </Route>
    </Routes>
  </>;
}
