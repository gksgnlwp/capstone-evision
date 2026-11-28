import { Link, useParams } from 'react-router-dom';
import { Badge, EmptyState } from '../../components/ui';
import { config } from '../../config/env';
import { sampleStations } from '../search/searchFixtures';
import './prediction.css';

const demo = config.environment === 'DEV';

function stationContext(rawId: string | undefined) {
  const id = Number(rawId);
  if (!Number.isSafeInteger(id) || id < 1) return null;
  const station = sampleStations.find(item => item.stationId === id);
  return !demo || station ? { id, name: station?.name ?? '충전소' } : null;
}

function Unavailable({ stationId }: { stationId: number }) {
  return <EmptyState title="예측 정보를 아직 제공할 수 없어요"
    description="충전소별 예측값과 산출 시각을 조회하는 API가 연결되면 도착 시점 예측을 표시합니다."
    action={<Link className="button button--secondary" to={`/stations/${stationId}/prediction-unavailable`}>현재 충전기 상태 확인</Link>} />;
}

export function PredictionPage() {
  const { stationId } = useParams();
  const station = stationContext(stationId);
  return <div className="stack prediction-page"><div><Link className="text-link" to={`/stations/${stationId}`}>← 충전소 상세</Link><h1>도착 시점 예측</h1></div>
    {!station ? <EmptyState title="충전소를 다시 선택해 주세요" /> : !demo ? <Unavailable stationId={station.id} /> : <>
      <p className="prediction-demo-note">{station.name} · 아래 확률과 시간은 화면 시연용이며 실제 예측 결과가 아닙니다.</p>
      <section className="card stack"><div className="row between wrap"><div><p className="small muted">도착 예상 시점</p><h2>약 30분 후</h2></div><Badge tone="neutral">시연 데이터</Badge></div>
        <div className="prediction-highlight"><strong>이용 가능 확률 78%</strong><span>급속충전기 중 1대 이상 이용 가능할 시연 확률</span></div>
        <dl className="prediction-facts"><div><dt>예상 점유율</dt><dd>62%</dd></div><div><dt>만차 확률</dt><dd>18%</dd></div><div><dt>예상 대기시간</dt><dd>정보 없음</dd></div></dl>
      </section>
      <section className="card stack"><h2>예측을 읽는 방법</h2><p className="small muted">이용 가능 확률은 도착 시점에 충전할 수 있을 가능성을 뜻합니다. 현재 충전기 상태와는 다른 정보입니다.</p><div className="prediction-basis"><span>시연 기준</span><strong>최근 상태·시간대 패턴을 사용한 화면 예시</strong></div><p className="small muted">실제 서비스에서는 모델 산출 시각과 사용한 데이터 구간을 서버 응답으로 표시합니다.</p></section>
      <Link className="button button--secondary button--full" to={`/stations/${station.id}/prediction-unavailable`}>현재 충전기 상태 보기</Link>
    </>}
  </div>;
}
