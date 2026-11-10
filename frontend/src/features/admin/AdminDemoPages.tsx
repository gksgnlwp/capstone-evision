import { useState } from 'react';
import { CheckCircle2, FileUp } from 'lucide-react';
import { Badge, Button, EmptyState } from '../../components/ui';
import { config } from '../../config/env';
import './admin.css';

const demo = config.environment === 'DEV';
const evaluation = [
  { model: 'v1.2', period: '최근 7일', mae: 0.12, precision: 0.84, recall: 0.79, f1: 0.81, status: '후보' },
  { model: 'v1.1', period: '이전 7일', mae: 0.15, precision: 0.80, recall: 0.76, f1: 0.78, status: '운영 중' },
];

function Heading({ title, description }: { title: string; description: string }) {
  return <div className="admin-page-head"><div><h1>{title}</h1><p>{description}</p></div>{demo && <Badge tone="neutral">DEV 시연 데이터</Badge>}</div>;
}

function Panel({ title, children }: { title: string; children: React.ReactNode }) {
  return <section className="admin-panel"><h2>{title}</h2>{children}</section>;
}

function Pending({ description }: { description: string }) {
  return <EmptyState title="서버 데이터 연결 대기" description={description} />;
}

export function AccuracyPage() {
  const [period, setPeriod] = useState('전체');
  const rows = evaluation.filter(item => period === '전체' || item.period === period);
  const average = (key: 'mae' | 'precision' | 'recall' | 'f1') => (rows.reduce((sum, item) => sum + item[key], 0) / rows.length).toFixed(2);
  return <div className="admin-page"><Heading title="예측 정확도" description="모델 성능을 평가 기간과 지표별로 확인합니다." />
    <div className="admin-filters"><label>평가 기간<select value={period} onChange={event => setPeriod(event.target.value)} disabled={!demo}><option>전체</option><option>최근 7일</option><option>이전 7일</option></select></label></div>
    {!demo ? <Pending description="모델별 평가 기간과 지표 조회 API가 연결되면 결과를 표시합니다." /> : <>
      <div className="admin-metric-grid"><div className="admin-metric"><span>평균 절대 오차 · MAE</span><strong>{average('mae')}</strong><small>{period} · 시연값</small></div><div className="admin-metric"><span>정밀도</span><strong>{average('precision')}</strong><small>{period} · 시연값</small></div><div className="admin-metric"><span>재현율</span><strong>{average('recall')}</strong><small>{period} · 시연값</small></div><div className="admin-metric"><span>F1 점수</span><strong>{average('f1')}</strong><small>{period} · 시연값</small></div></div>
      <Panel title="모델별 평가 결과"><div className="admin-table-wrap"><table className="admin-table"><thead><tr><th scope="col">모델</th><th scope="col">평가 기간</th><th scope="col">MAE</th><th scope="col">정밀도</th><th scope="col">재현율</th><th scope="col">F1</th><th scope="col">상태</th></tr></thead><tbody>{rows.map(item => <tr key={item.model}><td>{item.model}</td><td>{item.period}</td><td>{item.mae.toFixed(2)}</td><td>{item.precision.toFixed(2)}</td><td>{item.recall.toFixed(2)}</td><td>{item.f1.toFixed(2)}</td><td>{item.status}</td></tr>)}</tbody></table></div></Panel>
      <Panel title="지표 해석"><dl className="admin-definitions"><div><dt>MAE</dt><dd>예측 점유율과 실제 점유율의 평균 오차</dd></div><div><dt>정밀도</dt><dd>만차로 예측한 경우 실제 만차인 비율</dd></div><div><dt>재현율</dt><dd>실제 만차 중 만차로 찾아낸 비율</dd></div><div><dt>F1</dt><dd>정밀도와 재현율의 조화 평균</dd></div></dl></Panel>
      <p className="admin-form-note">모든 모델·평가 수치는 DEV 화면 시연용이며 실제 학습 결과가 아닙니다.</p>
    </>}
  </div>;
}

interface CsvPreview { name: string; rows: string[][]; count: number }

export function MappingsPage() {
  const [preview, setPreview] = useState<CsvPreview | null>(null);
  const [error, setError] = useState('');
  async function inspect(file?: File) {
    setPreview(null);
    setError('');
    if (!file) return;
    if (!file.name.toLowerCase().endsWith('.csv')) { setError('CSV 파일을 선택해 주세요.'); return; }
    if (file.size > 2_000_000) { setError('화면 미리보기는 2MB 이하의 CSV만 지원합니다.'); return; }
    try {
      const lines = (await file.text()).replace(/^\uFEFF/, '').split(/\r?\n/).filter(line => line.trim());
      if (lines.length < 2) { setError('머리글과 데이터 행이 있는 CSV를 선택해 주세요.'); return; }
      setPreview({ name: file.name, count: lines.length - 1, rows: lines.slice(0, 6).map(line => line.split(',').map(cell => cell.trim())) });
    } catch { setError('파일을 읽지 못했습니다. 다시 선택해 주세요.'); }
  }
  return <div className="admin-page"><Heading title="기준정보 · 매핑" description="충전소 기준정보와 휴게소·IC 접근 매핑을 관리합니다." />
    <div className="admin-section-grid"><Panel title="기준정보 파일 미리보기"><p className="muted small">로컬 CSV의 첫 5개 데이터 행을 확인합니다. 선택한 파일은 서버에 전송되지 않습니다.</p>
      <label className="admin-file-label"><FileUp size={18} aria-hidden="true" /> CSV 파일 선택<input type="file" accept=".csv,text/csv" disabled={!demo} onChange={event => void inspect(event.target.files?.[0])} /></label>
      {error && <p role="alert" className="admin-input-error">{error}</p>}
      {preview && <><p role="status" className="admin-form-note">{preview.name} · 데이터 {preview.count}행 · 아래는 단순 미리보기이며 형식 검증 결과가 아닙니다.</p><div className="admin-table-wrap"><table className="admin-table"><thead><tr>{preview.rows[0].map((cell, index) => <th scope="col" key={index}>{cell || `열 ${index + 1}`}</th>)}</tr></thead><tbody>{preview.rows.slice(1).map((row, index) => <tr key={index}>{preview.rows[0].map((_, cellIndex) => <td key={cellIndex}>{row[cellIndex] || '—'}</td>)}</tr>)}</tbody></table></div></>}
      <Button disabled>검증 및 적재</Button><p className="admin-form-note">실제 검증·적재는 관리자 인증과 서버 API가 연결되면 실행할 수 있습니다.</p></Panel>
      <Panel title="매핑 처리 단계"><ol className="admin-steps"><li>① 기준정보 형식 검증</li><li>② 검증된 휴게소 매핑 적용</li><li>③ IC 인근 자동 매핑</li><li>④ 결과 및 제외 대상 확인</li></ol><p className="admin-form-note">단계는 예정된 작업 흐름입니다. 처리 완료 상태를 뜻하지 않습니다.</p></Panel></div>
    <Pending description="기준정보 적재 이력과 매핑 결과 API가 연결되면 처리 건수·오류·제외 대상을 표시합니다." />
  </div>;
}

export function ModelsPage() {
  const [selected, setSelected] = useState(evaluation[0].model);
  const [reviewed, setReviewed] = useState(false);
  const model = evaluation.find(item => item.model === selected) ?? evaluation[0];
  return <div className="admin-page"><Heading title="모델 학습 · 반영" description="학습 결과를 검토하고 승인된 모델의 반영 상태를 확인합니다." />
    {demo ? <><Panel title="후보 모델 검토"><div className="admin-model-list" role="group" aria-label="모델 선택">{evaluation.map(item => <button type="button" key={item.model} className={`admin-model-option ${selected === item.model ? 'is-selected' : ''}`} aria-pressed={selected === item.model} onClick={() => { setSelected(item.model); setReviewed(false); }}><strong>{item.model}</strong><span>{item.status}</span><small>MAE {item.mae.toFixed(2)} · F1 {item.f1.toFixed(2)}</small></button>)}</div>
      <div className="admin-model-review"><strong>{model.model} 검토 항목</strong><p className="small muted">평가 기간 {model.period} · 정밀도 {model.precision.toFixed(2)} · 재현율 {model.recall.toFixed(2)}</p><label><input type="checkbox" checked={reviewed} onChange={event => setReviewed(event.target.checked)} /> 시연용 평가 지표를 확인했습니다</label>{reviewed && <p role="status" className="small"><CheckCircle2 size={16} aria-hidden="true" /> 화면에서 검토 항목을 확인했습니다. 서버 승인이나 모델 반영은 실행되지 않았습니다.</p>}</div></Panel>
      <div className="admin-section-grid"><Panel title="학습 · 평가"><ol className="admin-steps"><li>① 학습 데이터 준비</li><li>② 모델 학습</li><li>③ 평가 지표 검토</li></ol><Button disabled>학습 실행</Button></Panel><Panel title="운영 반영"><ol className="admin-steps"><li>① 후보 모델 검토</li><li>② 운영자 승인</li><li>③ 모델 반영</li></ol><Button disabled>모델 반영 승인</Button></Panel></div></>
      : <Pending description="모델 이력·평가 결과와 학습·승인 API, 관리자 권한이 연결되면 사용할 수 있습니다." />}
    <p className="admin-form-note">학습과 승인 버튼은 서버 작업을 실행하지 않습니다. DEV 모델 정보는 화면 시연용입니다.</p>
  </div>;
}
