import { useId, type ButtonHTMLAttributes, type HTMLAttributes, type InputHTMLAttributes, type ReactNode } from 'react';
import { AlertCircle, Check, Inbox, LoaderCircle } from 'lucide-react';
import type { NormalizedStatus } from '../domain/charging';
import { chargerStatuses } from '../domain/charging';

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'primary' | 'secondary' | 'ghost' | 'danger';
  loading?: boolean;
  fullWidth?: boolean;
}
export function Button({ variant = 'primary', loading = false, fullWidth, className = '', disabled, children, type = 'button', ...props }: ButtonProps) {
  return <button {...props} type={type} disabled={disabled || loading} aria-busy={loading || undefined}
    className={`button button--${variant} ${fullWidth ? 'button--full' : ''} ${className}`}>
    {loading && <LoaderCircle size={18} className="spinner" aria-hidden="true" />}{children}
  </button>;
}

export function Card({ className = '', ...props }: HTMLAttributes<HTMLElement>) {
  return <section {...props} className={`card ${className}`} />;
}
export type BadgeTone = 'success' | 'warning' | 'danger' | 'info' | 'neutral';
export function Badge({ children, tone = 'neutral' }: { children: ReactNode; tone?: BadgeTone }) {
  return <span className={`badge badge--${tone}`}>{children}</span>;
}
export function StatusBadge({ status }: { status: NormalizedStatus }) {
  const { label, tone } = chargerStatuses[status];
  return <Badge tone={tone}>{label}</Badge>;
}
export function Chip({ selected, children, ...props }: ButtonHTMLAttributes<HTMLButtonElement> & { selected?: boolean }) {
  return <button {...props} type="button" aria-pressed={!!selected} className="chip">
    {selected && <Check size={14} aria-hidden="true" />}{children}
  </button>;
}

interface InputProps extends InputHTMLAttributes<HTMLInputElement> { label: string; hint?: string; error?: string }
export function Input({ label, hint, error, id, ...props }: InputProps) {
  const generated = useId();
  const inputId = id || generated;
  const description = error || hint;
  return <div className="field">
    <label htmlFor={inputId}>{label}</label>
    <input {...props} id={inputId} aria-invalid={!!error} aria-describedby={description ? `${inputId}-description` : undefined} />
    {description && <p id={`${inputId}-description`} className={`field__hint ${error ? 'field__error' : ''}`}>{description}</p>}
  </div>;
}

export function LoadingState({ label = '정보를 불러오고 있습니다.' }: { label?: string }) {
  return <div role="status" className="state"><LoaderCircle className="spinner" size={26} /><p>{label}</p></div>;
}
export function EmptyState({ title, description, action }: { title: string; description?: string; action?: ReactNode }) {
  return <div className="state"><Inbox size={28} aria-hidden="true" /><h2>{title}</h2>{description && <p>{description}</p>}{action}</div>;
}
export function ErrorState({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return <div role="alert" className="state state--error"><AlertCircle size={28} aria-hidden="true" /><h2>정보를 불러오지 못했어요</h2><p>{message}</p>
    {onRetry && <Button variant="secondary" onClick={onRetry}>다시 시도</Button>}
  </div>;
}
