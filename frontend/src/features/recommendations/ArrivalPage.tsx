import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Badge, Button } from '../../components/ui';
import { config } from '../../config/env';

const options = [15, 30, 45, 60];

export function ArrivalPage() {
  const [params] = useSearchParams();
  const [selected, setSelected] = useState<number | null>(() => {
    const value = Number(params.get('arrivalOffsetMin'));
    return options.includes(value) ? value : null;
  });
  const demo = config.environment === 'DEV';
  const back = new URLSearchParams(params);
  if (demo && selected) back.set('arrivalOffsetMin', String(selected));
  else back.delete('arrivalOffsetMin');

  return <div className="stack arrival-page">
    <div><Link className="text-link" to={`/recommendations?${params}`}>← 추천 결과</Link><h1>도착 예정시각 변경</h1></div>
    <section className="card stack"><p className="small muted">현재 추천 결과</p><strong>서버가 이동 거리를 기준으로 도착 시각을 계산합니다.</strong><p className="small muted">이 화면에서 선택한 시각은 현재 추천 순위와 확률에 적용되지 않습니다.</p></section>
    <section className="card stack"><div className="row between wrap"><h2>언제 도착하나요?</h2>{demo && <Badge tone="neutral">UI 시연</Badge>}</div>
      <p className="small muted">{demo ? '도착 예정 시간을 선택해 화면 흐름을 확인할 수 있습니다. 추천 결과는 다시 계산되지 않습니다.' : '추천 API에 도착 시각 입력이 추가되면 선택한 시각으로 다시 조회할 수 있습니다.'}</p>
      <div className="arrival-options" role="group" aria-label="도착 예정 시간">{options.map(minutes => <Button key={minutes} type="button" variant={selected === minutes ? 'primary' : 'secondary'} disabled={!demo} aria-pressed={selected === minutes} onClick={() => setSelected(minutes)}>{minutes}분 후</Button>)}</div>
      {demo && selected && <p role="status" className="arrival-selection">선택한 시간: {selected}분 후 · 추천 결과에는 미적용</p>}
    </section>
    <Link className="button button--primary button--full" to={`/recommendations?${back}`}>추천 결과로 돌아가기</Link>
  </div>;
}
