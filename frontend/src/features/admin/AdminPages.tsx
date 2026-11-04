import { useCallback, useMemo, useState } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { AlertTriangle, ArrowRight, Clock3, Download, FileUp, LockKeyhole, RefreshCw, ShieldCheck } from 'lucide-react';
import { Badge, Button, EmptyState, ErrorState, LoadingState } from '../../components/ui';
import { config } from '../../config/env';
import { useAsync } from '../../hooks/useAsync';
import { getCollectionStatus, kstLocalTime, type CollectionStatus } from './collectionService';
import './admin.css';

const isDemo = config.environment === 'DEV';
const number = (value: number) => new Intl.NumberFormat('ko-KR').format(value);
const percent = (value: number | null) => value === null ? '정보 없음' : `${(value * 100).toFixed(1)}%`;
const dateTime = (value: string | null) => value ? value.replace('T', ' ').slice(0, 16) : '기록 없음';

function Heading({ title, description, action }: { title: string; description: string; action?: React.ReactNode }) {
  return <div className="admin-page-head"><div><h1>{title}</h1><p>{description}</p></div>{action}</div>;
}

function Panel({ title, children, className = '' }: { title: string; children: React.ReactNode; className?: string }) {
  return <section className={`admin-panel ${className}`}><h2>{title}</h2>{children}</section>;
}

function Metric({ label, value, detail, tone }: { label: string; value: string; detail: string; tone?: 'good' | 'warning' }) {
  return <div className="admin-metric"><span>{label}</span><strong className={tone ? `admin-metric--${tone}` : ''}>{value}</strong><small>{detail}</small></div>;
}

function PendingData({ description }: { description: string }) {
  return <div className="admin-pending" role="status"><Clock3 size={20} aria-hidden="true" /><p>{description}</p></div>;
}

export function AdminLoginPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const next = (location.state as { from?: string } | null)?.from;
  return <div className="admin-login-wrap"><div className="admin-login-card">
    <div className="admin-login-icon"><LockKeyhole size={24} aria-hidden="true" /></div>
    <h1>운영자 로그인</h1><p className="muted">수집 현황과 운영 지표를 확인하는 공간입니다.</p>
    {isDemo ? <><div className="admin-callout"><ShieldCheck size={19} aria-hidden="true" /><span>DEV 환경의 화면 시연입니다. 운영자 인증은 연결되지 않았습니다.</span></div>
      <Button fullWidth onClick={() => navigate(next?.startsWith('/admin/') ? next : '/admin/collection')}>시연 화면 보기 <ArrowRight size={16} aria-hidden="true" /></Button></>
      : <><label className="admin-field">아이디<input disabled autoComplete="username" placeholder="운영자 계정" /></label>
        <label className="admin-field">비밀번호<input disabled type="password" autoComplete="current-password" placeholder="비밀번호" /></label>
        <Button fullWidth disabled>로그인</Button><p className="admin-form-note">운영자 인증 API와 접근 통제가 준비되면 로그인할 수 있습니다.</p></>}
  </div></div>;
}

function CollectionContent({ data }: { data: CollectionStatus }) {
  const runs = data.runs.byStatus;
  return <>
    {data.status === 'NO_RECORDS' ? <div className="admin-notice"><AlertTriangle size={17} />선택한 기간에 수집 회차 기록이 없습니다.</div> : null}
    {data.consecutiveFailures.length > 0 && <div className="admin-notice admin-notice--danger"><AlertTriangle size={17} />연속 실패 구간 {data.consecutiveFailures.length}개 · 최대 {data.maxConsecutiveFailures}회 연속 실패. 해당 기간의 상태가 누락됐을 수 있습니다.</div>}
    <div className="admin-metric-grid">
      <Metric label="최근 상태 수집" value={dateTime(data.lastSuccess.status)} detail="마지막 성공 회차" tone="good" />
      <Metric label="30분 구간 데이터 가용률" value={percent(data.availability.windowRate)} detail={`${data.availability.completeWindows} / ${data.availability.scheduledWindows}개 완전 구간`} />
      <Metric label="조회 기간 회차 결과" value={`${number(runs.SUCCESS ?? 0)} / ${number(runs.PARTIAL ?? 0)} / ${number(runs.FAILED ?? 0)}`} detail="성공 / 부분 성공 / 실패" />
      <Metric label="오늘 API 호출" value={`${number(data.apiCallsToday.used)} / ${number(data.apiCallsToday.limit)}`} detail="공공 API 사용량 / 일일 한도" />
    </div>
    <div className="admin-section-grid">
      <Panel title="수집 파이프라인"><div className="admin-pipeline">
        <div><span>① 기본정보 수집</span><Badge tone={data.lastSuccess.info ? 'success' : 'neutral'}>{data.lastSuccess.info ? '최근 성공' : '기록 없음'}</Badge><small>{dateTime(data.lastSuccess.info)}</small></div>
        <div><span>② 상태 수집·중복 제거</span><Badge tone={data.lastSuccess.status ? 'success' : 'neutral'}>{data.lastSuccess.status ? '최근 성공' : '기록 없음'}</Badge><small>{dateTime(data.lastSuccess.status)}</small></div>
        <div><span>③ 30분 점유율 집계</span><Badge tone={data.availability.windowRate === null ? 'neutral' : data.availability.windowRate >= .99 ? 'success' : 'warning'}>{percent(data.availability.windowRate)}</Badge><small>완전한 구간 기준</small></div>
        <div><span>④ 예측 배치·공개</span><Badge tone="neutral">연동 대기</Badge><small>별도 상태 API 필요</small></div>
      </div></Panel>
      <Panel title="데이터 품질"><dl className="admin-definitions">
        <div><dt>5분 슬롯 가용률</dt><dd>{percent(data.availability.slotRate)}</dd></div>
        <div><dt>수집 / 적재 / 중복</dt><dd>{number(data.counts.fetched)} / {number(data.counts.inserted)} / {number(data.counts.duplicate)}</dd></div>
        <div><dt>미확인 / 오류</dt><dd>{number(data.counts.unknown)} / {number(data.counts.invalid)}</dd></div>
        <div><dt>서버 디스크 사용률</dt><dd className={data.disk.warning ? 'admin-text-danger' : ''}>{percent(data.disk.usedRate)} {data.disk.warning ? '· 점검 필요' : ''}</dd></div>
      </dl></Panel>
    </div>
    <Panel title="누락된 상태 수집 구간"><div className="admin-table-wrap"><table className="admin-table"><thead><tr><th>시작</th><th>종료</th><th>5분 슬롯</th><th>판정</th></tr></thead><tbody>
      {data.missingRanges.map((range, index) => <tr key={`${range.from}-${index}`}><td>{dateTime(range.from)}</td><td>{dateTime(range.to)}</td><td>{range.slots}개</td><td><Badge tone={range.slots >= 2 ? 'danger' : 'warning'}>{range.slots >= 2 ? '연속 실패' : '단일 누락'}</Badge></td></tr>)}
      {data.missingRanges.length === 0 && <tr><td colSpan={4}>조회 기간에 누락된 슬롯이 없습니다.</td></tr>}
    </tbody></table></div><p className="admin-panel-note">API는 개별 수집 회차 목록을 제공하지 않아 누락 구간과 집계만 표시합니다.</p></Panel>
  </>;
}

export function CollectionPage() {
  const [days, setDays] = useState(1);
  const [now, setNow] = useState(() => new Date());
  const range = useMemo(() => {
    return { from: kstLocalTime(new Date(now.getTime() - days * 86400000)), to: kstLocalTime(now) };
  }, [days, now]);
  const loader = useCallback((signal: AbortSignal) => getCollectionStatus(range.from, range.to, signal), [range.from, range.to]);
  const result = useAsync(loader);
  return <div className="admin-page"><Heading title="수집 모니터링" description={`${dateTime(range.to)} 기준 · 조회 기간 최대 31일`} action={<div className="admin-actions">{[1, 7, 31].map(value => <button key={value} type="button" className={`admin-period ${days === value ? 'is-selected' : ''}`} aria-pressed={days === value} onClick={() => setDays(value)}>{value === 1 ? '최근 24시간' : `${value}일`}</button>)}<Button variant="secondary" onClick={() => setNow(new Date())} aria-label="새로고침"><RefreshCw size={16} /></Button></div>} />
    {result.status === 'loading' && <LoadingState label="수집 현황을 불러오고 있습니다." />}
    {result.status === 'error' && <ErrorState message={result.error.message} onRetry={result.retry} />}
    {result.status === 'success' && <CollectionContent data={result.data} />}
  </div>;
}

const sampleStats = [
  { place: '안성휴게소(부산)', kind: '휴게소', route: '경부고속도로', occupancy: 68.4, full: 14.2, windows: 46 },
  { place: '죽전휴게소(서울)', kind: '휴게소', route: '경부고속도로', occupancy: 43.1, full: 8.7, windows: 45 },
  { place: '서안성IC 인근', kind: 'IC 인근', route: '평택제천고속도로', occupancy: 57.8, full: 11.5, windows: 39 },
  { place: '행담도휴게소', kind: '휴게소', route: '서해안고속도로', occupancy: 72.6, full: 18.3, windows: 44 },
];

export function StatisticsPage() {
  const [kind, setKind] = useState('전체');
  const [route, setRoute] = useState('전체');
  const rows = sampleStats.filter(item => (kind === '전체' || item.kind === kind) && (route === '전체' || item.route === route));
  const download = () => {
    const csv = '\uFEFF거점,유형,노선,평균 점유율(%),만차 비율(%),관측 구간\r\n' + rows.map(item => `${item.place},${item.kind},${item.route},${item.occupancy},${item.full},${item.windows}`).join('\r\n');
    const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
    const anchor = document.createElement('a'); anchor.href = url; anchor.download = 'evision-demo-statistics.csv'; anchor.click(); URL.revokeObjectURL(url);
  };
  return <div className="admin-page"><Heading title="거점별 운영 통계" description="노선과 거점 유형을 선택해 점유 현황을 살펴봅니다." action={<Button variant="secondary" onClick={download} disabled={!isDemo || rows.length === 0}><Download size={16} /> CSV 다운로드</Button>} />
    <div className="admin-filters"><label>거점 유형<select value={kind} onChange={event => setKind(event.target.value)}><option>전체</option><option>휴게소</option><option>IC 인근</option></select></label><label>노선<select value={route} onChange={event => setRoute(event.target.value)}><option>전체</option><option>경부고속도로</option><option>평택제천고속도로</option><option>서해안고속도로</option></select></label></div>
    {isDemo ? <><div className="admin-metric-grid"><Metric label="조회 거점" value={`${rows.length}곳`} detail="현재 필터 기준" /><Metric label="평균 점유율" value={rows.length ? `${(rows.reduce((sum, item) => sum + item.occupancy, 0) / rows.length).toFixed(1)}%` : '정보 없음'} detail="시연 데이터 평균" /><Metric label="평균 만차 비율" value={rows.length ? `${(rows.reduce((sum, item) => sum + item.full, 0) / rows.length).toFixed(1)}%` : '정보 없음'} detail="시연 데이터 평균" /></div>
      <Panel title="거점별 현황"><div className="admin-table-wrap"><table className="admin-table"><thead><tr><th>거점</th><th>유형</th><th>노선</th><th>평균 점유율</th><th>만차 비율</th><th>관측 구간</th></tr></thead><tbody>{rows.map(item => <tr key={item.place}><td>{item.place}</td><td>{item.kind}</td><td>{item.route}</td><td>{item.occupancy}%</td><td>{item.full}%</td><td>{item.windows}개</td></tr>)}</tbody></table></div>{rows.length === 0 && <EmptyState title="조건에 맞는 거점이 없어요" />}</Panel></>
      : <PendingData description="거점별 점유율·만차 비율·CSV 조회 API가 연결되면 실제 통계를 표시합니다." />}
  </div>;
}

export function AccuracyPage() {
  return <div className="admin-page"><Heading title="예측 정확도" description="모델 성능을 평가 기간과 지표별로 확인합니다." />
    {isDemo ? <><div className="admin-metric-grid"><Metric label="평균 절대 오차 · MAE" value="0.12" detail="점유율 예측 · 시연값" /><Metric label="정밀도" value="0.84" detail="만차 분류 · 시연값" /><Metric label="재현율" value="0.79" detail="만차 분류 · 시연값" /><Metric label="F1 점수" value="0.81" detail="만차 분류 · 시연값" /></div>
      <Panel title="모델 평가 결과"><div className="admin-table-wrap"><table className="admin-table"><thead><tr><th>모델</th><th>평가 기간</th><th>MAE</th><th>정밀도</th><th>재현율</th><th>F1</th></tr></thead><tbody><tr><td>v1.2 · 시연</td><td>최근 7일</td><td>0.12</td><td>0.84</td><td>0.79</td><td>0.81</td></tr><tr><td>v1.1 · 시연</td><td>이전 7일</td><td>0.15</td><td>0.80</td><td>0.76</td><td>0.78</td></tr></tbody></table></div></Panel><p className="admin-form-note">위 수치는 화면 시연용입니다. 모델 평가 API가 연결되면 서버 산출값으로 바뀝니다.</p></>
      : <PendingData description="모델 평가 결과 API가 준비되면 MAE·정밀도·재현율·F1을 표시합니다." />}
  </div>;
}

export function MappingsPage() {
  const [fileName, setFileName] = useState('');
  return <div className="admin-page"><Heading title="기준정보 · 매핑" description="충전소 기준정보와 휴게소·IC 접근 매핑을 관리합니다." />
    <div className="admin-section-grid"><Panel title="기준정보 적재"><p className="muted small">검증된 기준정보 파일을 선택한 뒤 서버의 검증 결과를 확인하는 흐름입니다.</p><label className="admin-file-label"><FileUp size={18} /> CSV 파일 선택<input type="file" accept=".csv,text/csv" onChange={event => setFileName(event.target.files?.[0]?.name ?? '')} /></label><p className="admin-form-note">{fileName ? `선택한 파일: ${fileName}` : '선택한 파일 없음'}</p><Button disabled>검증 및 적재</Button><p className="admin-form-note">적재 API와 관리자 권한 확인이 준비되면 실행할 수 있습니다.</p></Panel>
      <Panel title="매핑 처리 단계"><ol className="admin-steps"><li>① 기준정보 형식 검증</li><li>② 검증된 휴게소 매핑 적용</li><li>③ IC 인근 자동 매핑</li><li>④ 결과 및 제외 대상 확인</li></ol><p className="admin-form-note">현재 단계 표시는 작업 흐름 안내이며 실행 상태가 아닙니다.</p></Panel></div>
    <PendingData description="적재 이력과 매핑 결과 API가 연결되면 처리 건수·오류·제외 대상을 표시합니다." />
  </div>;
}

export function ModelsPage() {
  return <div className="admin-page"><Heading title="모델 학습 · 반영" description="학습 결과를 검토하고 승인된 모델의 반영 상태를 확인합니다." />
    <div className="admin-section-grid"><Panel title="학습 · 평가"><ol className="admin-steps"><li>① 학습 데이터 준비</li><li>② 모델 학습</li><li>③ 평가 지표 검토</li></ol><Button disabled>학습 실행</Button></Panel><Panel title="운영 반영"><ol className="admin-steps"><li>① 후보 모델 검토</li><li>② 운영자 승인</li><li>③ 모델 반영</li></ol><Button disabled>모델 반영 승인</Button></Panel></div>
    <PendingData description="학습·평가·승인 API와 권한 검증이 준비되면 모델 이력과 실행 기능을 연결합니다." />
  </div>;
}

export function AdminAccess({ children }: { children: React.ReactNode }) {
  const location = useLocation();
  if (!isDemo) return <Navigate to="/admin/login" state={{ from: location.pathname }} replace />;
  return children;
}

export function AdminNotFound() {
  return <EmptyState title="운영 페이지를 찾을 수 없어요" action={<Link className="text-link" to="/admin/collection">수집 모니터링으로 이동</Link>} />;
}
