export function Toast({ message }: { message: string }) {
  if (!message) return null;
  return <div className="app-toast" role="status" aria-live="polite"><span className="app-toast-check" aria-hidden="true">✓</span>{message}</div>;
}
