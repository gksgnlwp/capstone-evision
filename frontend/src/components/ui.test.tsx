import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { Button, Input, StatusBadge } from './ui';

describe('공통 UI', () => {
  it('미확인 상태를 이용 가능으로 표시하지 않는다', () => {
    render(<StatusBadge status="UNKNOWN" />);
    expect(screen.getByText('알 수 없음')).toBeInTheDocument();
    expect(screen.queryByText('이용 가능')).not.toBeInTheDocument();
  });
  it('처리 중에는 중복 클릭을 막는다', async () => {
    const onClick = vi.fn();
    render(<Button loading onClick={onClick}>저장</Button>);
    await userEvent.click(screen.getByRole('button', { name: '저장' }));
    expect(onClick).not.toHaveBeenCalled();
    expect(screen.getByRole('button')).toHaveAttribute('aria-busy', 'true');
  });
  it('입력의 라벨과 오류 설명을 연결한다', () => {
    render(<Input label="목적지" error="목적지를 확인해 주세요." />);
    const input = screen.getByLabelText('목적지');
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(input).toHaveAccessibleDescription('목적지를 확인해 주세요.');
  });
});
