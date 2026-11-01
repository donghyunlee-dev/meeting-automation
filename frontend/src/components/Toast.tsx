export function Toast({ message, tone = 'success' }: { message: string; tone?: 'success' | 'error' }) {
  if (!message) return null;
  return <div className={`app-toast app-toast-${tone}`} role={tone === 'error' ? 'alert' : 'status'} aria-live={tone === 'error' ? 'assertive' : 'polite'}>
    <span className="app-toast-check" aria-hidden="true">{tone === 'error' ? '!' : '✓'}</span>{message}
  </div>;
}
