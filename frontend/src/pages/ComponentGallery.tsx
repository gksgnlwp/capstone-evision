import { useState } from 'react';
import { Badge, Button, Card, Chip, EmptyState, ErrorState, Input, LoadingState, StatusBadge } from '../components/ui';
import { Dialog } from '../components/Dialog';
import { createMockClient } from '../services/api/mockClient';
import { useAsync } from '../hooks/useAsync';

const demoClient = createMockClient({ 'GET /ui-preview': () => ({ label: '공통 비동기 조회 구조가 동작합니다.' }) }, 600);
const loadPreview = (signal: AbortSignal) => demoClient.request<{ label: string }>('/ui-preview', { signal });

export function ComponentGallery() {
  const [selected, setSelected] = useState('전체');
  const [open, setOpen] = useState(false);
  const request = useAsync(loadPreview);
  return <div className="stack component-gallery"><span className="eyebrow">DEVELOPMENT ONLY</span><h1>공통 컴포넌트</h1><p className="muted small">개발 모드에서만 표시되는 확인용 페이지입니다. 실제 충전소 데이터가 아닙니다.</p>
    <Card className="stack"><h2>버튼과 선택</h2><Button onClick={() => setOpen(true)}>안내 열기</Button><Button variant="secondary">보조 버튼</Button><Button disabled>비활성 버튼</Button><Button loading>처리 중</Button><div className="row wrap">{['전체', '휴게소', 'IC 인근'].map(item => <Chip key={item} selected={selected === item} onClick={() => setSelected(item)}>{item}</Chip>)}</div></Card>
    <Card className="stack"><h2>상태 표현</h2><div className="row wrap"><StatusBadge status="AVAILABLE" /><StatusBadge status="CHARGING" /><StatusBadge status="UNAVAILABLE" /><StatusBadge status="UNKNOWN" /><Badge tone="warning">갱신 지연</Badge></div><p className="muted small">알 수 없음은 이용 가능 또는 0%로 표시하지 않습니다.</p></Card>
    <Card className="stack"><h2>입력</h2><Input label="목적지 예시" placeholder="목적지를 입력해 주세요" hint="컴포넌트 확인용 입력입니다." /><Input label="입력 오류 예시" error="입력한 값을 확인해 주세요." /></Card>
    <Card className="stack"><h2>조회 상태</h2>{request.status === 'loading' && <LoadingState />}{request.status === 'error' && <ErrorState message={request.error.message} onRetry={request.retry} />}{request.status === 'success' && <><p className="small">{request.data.label}</p><Button variant="secondary" onClick={request.retry}>예시 다시 조회</Button></>}<EmptyState title="표시할 항목이 없어요" description="필터를 바꾸거나 다른 조건으로 확인해 주세요." /><ErrorState message="오류 상태의 화면 예시입니다." /></Card>
    <Dialog open={open} onClose={() => setOpen(false)} title="공통 안내"><p className="small">키보드 포커스와 Escape 닫기를 지원하는 공통 대화상자입니다.</p><Button onClick={() => setOpen(false)}>확인</Button></Dialog>
  </div>;
}
