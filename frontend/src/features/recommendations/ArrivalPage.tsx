import { Link, useSearchParams } from 'react-router-dom';
import { Chip } from '../../components/ui';

// 현재 백엔드 /api/recommendations에는 도착시각 입력이 없다. 적용되지 않는 시각을 결과에 반영한 것처럼 표시하지 않는다.
export function ArrivalPage() {
  const [params] = useSearchParams();
  return <div className="stack arrival-page">
    <div><Link className="text-link" to={`/recommendations?${params}`}>← 추천 결과</Link><h1>도착 예정시각 변경</h1></div>
    <section className="card stack"><p className="small muted">현재 추천 결과</p><strong>서버가 이동 거리를 기준으로 도착 시각을 계산합니다.</strong></section>
    <section className="card stack"><h2>언제 도착하나요?</h2><p className="small muted">시각을 직접 바꾸는 기능은 추천 API에 도착시각 입력이 추가되면 사용할 수 있습니다.</p><div className="row wrap"><Chip disabled>15분 후</Chip><Chip disabled>30분 후</Chip><Chip disabled>45분 후</Chip><Chip disabled>60분 후</Chip></div></section>
    <Link className="button button--primary button--full" to={`/recommendations?${params}`}>추천 결과로 돌아가기</Link>
  </div>;
}
