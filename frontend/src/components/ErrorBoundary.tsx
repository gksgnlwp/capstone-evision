import { Component, type ErrorInfo, type ReactNode } from 'react';
import { Button } from './ui';

export class ErrorBoundary extends Component<{ children: ReactNode }, { hasError: boolean }> {
  state = { hasError: false };
  static getDerivedStateFromError() { return { hasError: true }; }
  componentDidCatch(error: Error, info: ErrorInfo) {
    if (import.meta.env.DEV) console.error(error, info);
  }
  render() {
    if (this.state.hasError) return <main className="fatal-state"><h1>화면을 표시하지 못했어요</h1><p>새로고침한 뒤 다시 시도해 주세요.</p><Button onClick={() => window.location.reload()}>새로고침</Button></main>;
    return this.props.children;
  }
}
