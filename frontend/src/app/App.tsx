import { lazy, Suspense, useEffect } from 'react';
import { Link, Navigate, Route, Routes, matchPath, useLocation } from 'react-router-dom';
import { MobileLayout } from '../layouts/MobileLayout';
import { AdminLayout } from '../layouts/AdminLayout';
import { HomePage } from '../pages/HomePage';
import { PlaceholderPage } from '../pages/PlaceholderPage';
import { ComponentGallery } from '../pages/ComponentGallery';
import { EmptyState, LoadingState } from '../components/ui';
import { screens, settingsScreen } from './screens';
import { LocationProvider } from '../features/search/LocationContext';
import { RouteSearchPage } from '../features/search/RouteSearchPage';
import { LocationPage } from '../features/search/LocationPage';
import { RecommendationPage } from '../features/recommendations/RecommendationPage';
import { ComparePage } from '../features/recommendations/ComparePage';
import { ArrivalPage } from '../features/recommendations/ArrivalPage';
import { StationDetailPage } from '../features/stations/StationDetailPage';
import { ChargerHistoryPage } from '../features/stations/ChargerHistoryPage';
import { PredictionPage, PatternPage } from '../features/stations/PredictionPage';
import { PredictionUnavailablePage } from '../features/stations/PredictionUnavailablePage';
import { OperatorPage } from '../features/personalization/OperatorPage';
import { FavoritesPage } from '../features/personalization/FavoritesPage';
const MapSearchPage = lazy(() => import('../features/search/MapSearchPage').then(module => ({ default: module.MapSearchPage })));

function RouteEffects() {
  const { pathname } = useLocation();
  useEffect(() => {
    window.scrollTo(0, 0);
    const title = [...screens, settingsScreen].find(screen => matchPath({ path: screen.path, end: true }, pathname))?.title || '페이지를 찾을 수 없어요';
    document.title = `${title} · EVISION`;
    // SPA 페이지 이동을 키보드/스크린리더 사용자에게도 알려 줍니다.
    document.getElementById('main-content')?.focus({ preventScroll: true });
  }, [pathname]);
  return null;
}

export function App() {
  return <LocationProvider>
    <RouteEffects />
    <a href="#main-content" className="skip-link">본문으로 이동</a>
    <Routes>
      <Route element={<MobileLayout />}>
        <Route index element={<HomePage />} />
        <Route path="/route" element={<RouteSearchPage />} />
        <Route path="/map" element={<Suspense fallback={<LoadingState label="지도를 준비하고 있습니다." />}><MapSearchPage /></Suspense>} />
        <Route path="/location" element={<LocationPage />} />
        <Route path="/recommendations" element={<RecommendationPage />} />
        <Route path="/recommendations/arrival" element={<ArrivalPage />} />
        <Route path="/compare" element={<ComparePage />} />
        <Route path="/stations/:stationId" element={<StationDetailPage />} />
        <Route path="/stations/:stationId/prediction" element={<PredictionPage />} />
        <Route path="/stations/:stationId/patterns" element={<PatternPage />} />
        <Route path="/stations/:stationId/chargers/:chargerId/history" element={<ChargerHistoryPage />} />
        <Route path="/stations/:stationId/prediction-unavailable" element={<PredictionUnavailablePage />} />
        <Route path="/favorites" element={<FavoritesPage />} />
        <Route path="/settings/operators" element={<OperatorPage />} />
        {screens.filter(s => s.audience === 'mobile' && !['/', '/route', '/map', '/location', '/recommendations', '/recommendations/arrival', '/compare', '/stations/:stationId', '/stations/:stationId/prediction', '/stations/:stationId/patterns', '/stations/:stationId/chargers/:chargerId/history', '/stations/:stationId/prediction-unavailable', '/favorites', '/settings/operators'].includes(s.path)).map(screen =>
          <Route key={screen.id} path={screen.path} element={<PlaceholderPage screen={screen} />} />)}
        <Route path="/settings" element={<OperatorPage />} />
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
  </LocationProvider>;
}
