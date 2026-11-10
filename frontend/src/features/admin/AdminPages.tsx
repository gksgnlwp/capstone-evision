import { useCallback, useMemo, useState } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { AlertTriangle, ArrowRight, Clock3, Download, LockKeyhole, RefreshCw, ShieldCheck } from 'lucide-react';
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
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [loginMessage, setLoginMessage] = useState('');
  return <div className="admin-login-wrap"><div className="admin-login-card">
    <div className="admin-login-icon"><LockKeyhole size={24} aria-hidden="true" /></div>
    <h1>운영자 로그인</h1><p className="muted">수집 현황과 운영 지표를 확인하는 공간입니다.</p>
    {isDemo ? <><div className="admin-callout"><ShieldCheck size={19} aria-hidden="true" /><span>DEV 환경의 화면 시연입니다. 운영자 인증은 연결되지 않았습니다.</span></div>
      <Button fullWidth onClick={() => navigate(next?.startsWith('/admin/') ? next : '/admin/collection')}>시연 화면 보기 <ArrowRight size={16} aria-hidden="true" /></Button></>
      : <form className="admin-login-form" onSubmit={event => { event.preventDefault(); setLoginMessage('운영자 로그인을 현재 사용할 수 없습니다.'); }}>
        <label className="admin-field">아이디<input required autoComplete="username" placeholder="운영자 계정" value={username} onChange={event => setUsername(event.target.value)} /></label>
        <label className="admin-field">비밀번호<input required type="password" autoComplete="current-password" placeholder="비밀번호" value={password} onChange={event => setPassword(event.target.value)} /></label>
        <Button type="submit" fullWidth>로그인</Button>{loginMessage && <p role="alert" className="admin-input-error">{loginMessage}</p>}
        <p className="admin-form-note">인증 기능 준비 중입니다. 입력한 계정 정보는 서버에 전송하거나 저장하지 않습니다.</p>
      </form>}
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

export function AdminAccess({ children }: { children: React.ReactNode }) {
  const location = useLocation();
  if (!isDemo) return <Navigate to="/admin/login" state={{ from: location.pathname }} replace />;
  return children;
}

export function AdminNotFound() {
  return <EmptyState title="운영 페이지를 찾을 수 없어요" action={<Link className="text-link" to="/admin/collection">수집 모니터링으로 이동</Link>} />;
}
