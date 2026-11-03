import { Link, useParams } from 'react-router-dom';
import { EmptyState } from '../../components/ui';

export function PredictionPage() {
  const { stationId } = useParams();
  return <div className="stack"><div><Link className="text-link" to={`/stations/${stationId}`}>← 충전소 상세</Link><h1>도착 시점 예측</h1></div>
    <EmptyState title="예측 정보를 아직 제공할 수 없어요" description="현재 백엔드에는 충전소별 예측값과 산출 시각을 조회하는 API가 없습니다. 정보가 없는 확률을 0%로 표시하지 않습니다." action={<Link className="button button--secondary" to={`/stations/${stationId}/prediction-unavailable`}>현재 충전기 상태 확인</Link>} />
  </div>;
}

export function PatternPage() {
  const { stationId } = useParams();
  return <div className="stack"><div><Link className="text-link" to={`/stations/${stationId}`}>← 충전소 상세</Link><h1>시간대별 혼잡 패턴</h1><p className="small muted">최근 4주 · 30분 집계 기준</p></div>
    <EmptyState title="혼잡 패턴을 아직 제공할 수 없어요" description="요일·시간대별 집계 조회 API가 준비되면 실제 데이터로 패턴을 표시합니다. 근거 없는 그래프는 표시하지 않습니다." action={<Link className="button button--secondary" to={`/stations/${stationId}`}>현재 상태 확인</Link>} />
  </div>;
}
