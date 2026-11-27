import { useCallback } from 'react';
import { useAsync } from '../../hooks/useAsync';
import { searchService } from '../search/searchService';
import { collectionTime, isCollectionDelayed } from '../search/searchUtils';
import type { RecommendationResponse } from './contracts';

export function RecommendationRoute({ route }: { route: NonNullable<RecommendationResponse['route']> }) {
  const { routeId, routeName, routeNo, direction } = route;
  const loader = useCallback((signal: AbortSignal) => searchService.search({ routeId, direction: direction || undefined, limit: 1 }, signal), [routeId, direction]);
  const collection = useAsync(loader);
  let message = '충전 상태 수집 시각 확인 중';
  if (collection.status === 'error') message = '충전 상태 수집 시각 확인 불가';
  if (collection.status === 'success') {
    const { statusCollectedAt, receivedAt } = collection.data;
    message = statusCollectedAt
      ? `충전 상태 마지막 수집 ${collectionTime(statusCollectedAt)}${isCollectionDelayed(statusCollectedAt, receivedAt) ? ' · 수집 지연' : ''}`
      : '충전 상태 수집 시각 정보 없음';
  }
  return <div className="recommend-route">
    <p className="small muted">추천 노선 {[routeName || (routeNo ? `${routeNo}번` : `노선 ${routeId}`), direction].filter(Boolean).join(' · ')}</p>
    <p className="small muted">{message}</p>
  </div>;
}
