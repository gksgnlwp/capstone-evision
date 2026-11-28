import { useCallback, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { Badge, Chip, EmptyState, ErrorState, LoadingState } from '../../components/ui';
import { config } from '../../config/env';
import { useAsync } from '../../hooks/useAsync';
import { ApiError } from '../../services/api';
import { sampleStations } from '../search/searchFixtures';
import type { StationPattern } from './contracts';
import { stationService } from './stationService';
import { groupPatternSlots } from './stationUtils';
import './prediction.css';

const demo = config.environment === 'DEV';
const weekdays = ['월', '화', '수', '목', '금', '토', '일'];
const periods = ['00–03', '03–06', '06–09', '09–12', '12–15', '15–18', '18–21', '21–24'];
const metrics = { occupancy: '평균 점유율', comfort: '쾌적 비율' } as const;
type Metric = keyof typeof metrics;

export function PatternPage() {
  const { stationId } = useParams();
  const id = Number(stationId);
  const valid = Number.isSafeInteger(id) && id > 0 && (!demo || sampleStations.some(station => station.stationId === id));
  return <div className="stack prediction-page">
    <div>{valid && <Link className="text-link" to={`/stations/${id}`}>← 충전소 상세</Link>}<h1>시간대별 혼잡 패턴</h1></div>
    {valid ? <PatternResults key={id} stationId={id} /> : <EmptyState title="충전소를 다시 선택해 주세요" />}
  </div>;
}

function PatternResults({ stationId }: { stationId: number }) {
  const loader = useCallback((signal: AbortSignal) => stationService.patterns(stationId, signal), [stationId]);
  const result = useAsync(loader);
  if (result.status === 'loading') return <LoadingState label="혼잡 패턴을 불러오고 있습니다." />;
  if (result.status === 'error') {
    if (result.error instanceof ApiError && result.error.status === 404) {
      return <EmptyState title="충전소를 찾을 수 없어요" description="운영이 종료됐거나 삭제된 충전소일 수 있어요." />;
    }
    return <ErrorState message={result.error.message} onRetry={result.retry} />;
  }
  const pattern = result.data;
  const from = pattern.from.replace('T', ' ').slice(0, 16);
  const to = pattern.to.replace('T', ' ').slice(0, 16);
  return <>
    {demo && <p className="prediction-demo-note">아래 표는 시연 데이터입니다. 실제 관측값이 아닙니다.</p>}
    <section className="card stack" aria-label="혼잡 패턴 집계 정보">
      <div className="row between wrap"><h2>{pattern.stationName}</h2>{demo && <Badge tone="neutral">시연 데이터</Badge>}</div>
      <p className="small muted">최근 {pattern.weeks}주 · 한국 시간<br />{from} ~ {to}</p>
      <p className="small muted">30분 구간 {pattern.totalWindows.toLocaleString('ko-KR')}개 중 {pattern.validWindows.toLocaleString('ko-KR')}개 집계 · {pattern.excludedWindows.toLocaleString('ko-KR')}개 제외</p>
    </section>
    {pattern.validWindows > 0 ? <PatternTable pattern={pattern} />
      : <EmptyState title="집계할 혼잡 패턴이 없어요" description="이 기간에 급속충전기 사용률을 확인한 구간이 없어요." />}
    <Link className="text-link" to={`/stations/${stationId}`}>현재 충전기 상태 확인</Link>
  </>;
}

function PatternTable({ pattern }: { pattern: StationPattern }) {
  const [metric, setMetric] = useState<Metric>('occupancy');
  const days = groupPatternSlots(pattern.slots);
  const label = metrics[metric];
  const threshold = Math.round(pattern.comfortThreshold * 1000) / 10;
  return <section className="card stack">
    <h2>요일·시간대별 패턴</h2>
    <div className="row wrap" role="group" aria-label="표에 표시할 지표">
      <Chip selected={metric === 'occupancy'} onClick={() => setMetric('occupancy')}>평균 점유율</Chip>
      <Chip selected={metric === 'comfort'} onClick={() => setMetric('comfort')}>쾌적 비율</Chip>
    </div>
    <p className="small muted">{metric === 'occupancy'
      ? '급속충전기 사용률의 과거 평균입니다.'
      : `급속충전기 사용률이 ${threshold}% 미만이었던 구간의 비율입니다.`} 표를 좌우로 움직이면 모든 시간대를 볼 수 있어요.</p>
    <div className="pattern-scroll" tabIndex={0} role="region" aria-label="요일·시간대 표">
      <table className="pattern-table">
        <caption className="sr-only">요일별 시간대 {label}{demo && ' 시연값'}</caption>
        <thead><tr><th scope="col">요일</th>{periods.map(period => <th scope="col" key={period}>{period}시</th>)}</tr></thead>
        <tbody>{days.map((day, index) => <tr key={weekdays[index]}><th scope="row">{weekdays[index]}</th>
          {day.map((cell, period) => {
            const rate = cell[metric];
            return <td key={periods[period]}>{rate === null
              ? <span className="pattern-cell pattern-cell--missing">데이터 없음</span>
              : <span className="pattern-cell" style={{ backgroundColor: `rgba(23, 75, 99, ${0.1 + rate * 0.43})` }}>
                {Math.round(rate * 100)}%<small>표본 {cell.samples}개</small>
              </span>}</td>;
          })}
        </tr>)}</tbody>
      </table>
    </div>
    <div className="pattern-legend"><span>낮은 {label}</span><span className="pattern-gradient" aria-hidden="true" /><span>높은 {label}</span></div>
    <p className="small muted">각 칸은 3시간 동안 확인된 30분 구간을 모아 계산한 값입니다.</p>
  </section>;
}
