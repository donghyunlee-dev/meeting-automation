import { useId, type ReactNode } from 'react';
import type { SafeApiError } from '../../lib/api/normalizeApiError';

export type AsyncStateValue<T> =
  | { status: 'loading'; label: string }
  | { status: 'empty'; title: string; description?: string; action?: ReactNode }
  | { status: 'error'; title: string; error: SafeApiError; description?: string; onRetry?: () => void; retryLabel?: string; action?: ReactNode }
  | { status: 'ready'; data: T; refreshing?: boolean; warning?: SafeApiError; onRetry?: () => void; retryLabel?: string };

export function AsyncState<T>({ state, renderReady }: { state: AsyncStateValue<T>; renderReady: (data: T) => ReactNode }) {
  const titleId = `async-state-title-${useId()}`;

  if (state.status === 'loading') {
    return <div className="state-card loading" role="status"><span className="spinner" aria-hidden="true" />{state.label}</div>;
  }

  if (state.status === 'empty') {
    return <section className="state-card empty-card" aria-labelledby={titleId}>
      <h3 id={titleId}>{state.title}</h3>
      {state.description && <p>{state.description}</p>}
      {state.action}
    </section>;
  }

  if (state.status === 'error') {
    return <section className="state-card error-card" role="alert" aria-labelledby={titleId}>
      <h2 id={titleId}>{state.title}</h2>
      <p>{state.description ?? state.error.message}</p>
      {state.error.traceId && <p className="async-state-reference">참조 ID: {state.error.traceId}</p>}
      {state.onRetry && <button className="button button-secondary" type="button" onClick={state.onRetry}>{state.retryLabel ?? '다시 시도'}</button>}
      {state.action}
    </section>;
  }

  return <>
    {state.refreshing && <p className="async-state-refreshing" role="status">최신 정보를 불러오는 중입니다. 기존 내용을 유지합니다.</p>}
    {state.warning && <div className="async-state-warning" role="status">
      <p>새로고침하지 못했습니다. 기존 내용을 유지합니다.</p>
      {state.warning.traceId && <span className="async-state-reference">참조 ID: {state.warning.traceId}</span>}
      {state.onRetry && <button className="button button-secondary" type="button" onClick={state.onRetry}>{state.retryLabel ?? '다시 시도'}</button>}
    </div>}
    {renderReady(state.data)}
  </>;
}
