import { Link, useParams } from 'react-router-dom';
import { Badge, EmptyState } from '../../components/ui';
import { config } from '../../config/env';
import { sampleStations } from '../search/searchFixtures';
import './prediction.css';

const demo = config.environment === 'DEV';
const weekdays = ['월', '화', '수', '목', '금', '토', '일'];
const periods = ['06:00', '09:00', '12:00', '15:00', '18:00', '21:00'];

function stationContext(rawId: string | undefined) {
  const id = Number(rawId);
  if (!Number.isSafeInteger(id) || id < 1) return null;
  const station = sampleStations.find(item => item.stationId === id);
  return !demo || station ? { id, name: station?.name ?? '충전소' } : null;
}

function Unavailable({ stationId, pattern = false }: { stationId: number; pattern?: boolean }) {
  return <EmptyState title={pattern ? '혼잡 패턴을 아직 제공할 수 없어요' : '예측 정보를 아직 제공할 수 없어요'}
    description={pattern ? '요일·시간대별 집계 API가 연결되면 관측값과 집계 기간을 표시합니다.' : '충전소별 예측값과 산출 시각을 조회하는 API가 연결되면 도착 시점 예측을 표시합니다.'}
    action={<Link className="button button--secondary" to={pattern ? `/stations/${stationId}` : `/stations/${stationId}/prediction-unavailable`}>현재 충전기 상태 확인</Link>} />;
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

export function PatternPage() {
  const { stationId } = useParams();
  const station = stationContext(stationId);
  return <div className="stack prediction-page"><div><Link className="text-link" to={`/stations/${stationId}`}>← 충전소 상세</Link><h1>시간대별 혼잡 패턴</h1></div>
    {!station ? <EmptyState title="충전소를 다시 선택해 주세요" /> : !demo ? <Unavailable stationId={station.id} pattern /> : <>
      <p className="prediction-demo-note">{station.name} · 최근 4주·30분 집계를 가정한 시연 데이터입니다. 실제 관측값이 아닙니다.</p>
      <section className="card stack"><div className="row between wrap"><h2>요일·시간별 평균 점유율</h2><Badge tone="neutral">시연 데이터</Badge></div><p className="small muted">표를 좌우로 움직이면 모든 시간대를 볼 수 있습니다.</p>
        <div className="pattern-scroll"><table className="pattern-table"><caption className="sr-only">요일별 시간대 평균 점유율 시연값</caption><thead><tr><th scope="col">요일</th>{periods.map(period => <th scope="col" key={period}>{period}</th>)}</tr></thead><tbody>{weekdays.map((day, dayIndex) => <tr key={day}><th scope="row">{day}</th>{periods.map((period, periodIndex) => { const occupancy = Math.min(94, 28 + (dayIndex * 11 + periodIndex * 17) % 62); return <td key={period}><span className="pattern-cell" style={{ backgroundColor: `rgba(0, 108, 91, ${0.1 + occupancy / 150})` }}>{occupancy}%</span></td>; })}</tr>)}</tbody></table></div>
        <div className="pattern-legend"><span>낮은 점유율</span><span className="pattern-gradient" aria-hidden="true" /><span>높은 점유율</span></div>
      </section>
      <section className="card stack"><h2>패턴 해석</h2><p className="small muted">색이 진할수록 해당 요일·시간대에 충전기가 더 많이 사용된 것을 뜻합니다. 평균 패턴은 현재 상태나 도착 시점 예측을 보장하지 않습니다.</p><Link className="text-link" to={`/stations/${station.id}`}>현재 충전기 상태 확인</Link></section>
    </>}
  </div>;
}
