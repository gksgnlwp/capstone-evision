import { ArrowLeft, Construction } from 'lucide-react';
import { Link } from 'react-router-dom';
import type { ScreenDefinition } from '../app/screens';
import { Card } from '../components/ui';

export function PlaceholderPage({ screen }: { screen: ScreenDefinition }) {
  return <div className="stack placeholder-page">
    <h1>{screen.title}</h1>
    <Card className="placeholder-card"><span className="placeholder-icon"><Construction size={32} strokeWidth={1.5} aria-hidden="true" /></span>
      <h2>화면을 준비하고 있어요</h2>
      <p>이 기능은 아직 사용할 수 없습니다.</p>
    </Card>
    <Link className="text-link row" to="/"><ArrowLeft size={16} aria-hidden="true" />홈으로 돌아가기</Link>
  </div>;
}
