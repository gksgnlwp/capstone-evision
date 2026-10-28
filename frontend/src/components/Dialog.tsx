import { useEffect, useId, useRef, type ReactNode } from 'react';
import { X } from 'lucide-react';

interface DialogProps { open: boolean; onClose: () => void; title: string; children: ReactNode }
/** native dialog가 포커스 가두기, Escape, 배경 비활성화를 처리합니다. */
export function Dialog({ open, onClose, title, children }: DialogProps) {
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    if (open && !dialog.open) dialog.showModal();
    else if (!open && dialog.open) dialog.close();
  }, [open]);
  return <dialog ref={ref} aria-labelledby={titleId} className="dialog" onCancel={onClose} onClose={onClose}>
    <div className="dialog__heading"><h2 id={titleId}>{title}</h2><button type="button" onClick={onClose} className="icon-button" aria-label="닫기"><X size={20} /></button></div>
    <div className="stack">{children}</div>
  </dialog>;
}
