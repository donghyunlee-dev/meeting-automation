import { useCallback, useEffect, useState } from 'react';

export function useToast(duration = 3200) {
  const [message, setMessage] = useState('');

  const showToast = useCallback((nextMessage: string) => setMessage(nextMessage), []);
  const clearToast = useCallback(() => setMessage(''), []);

  useEffect(() => {
    if (!message) return;
    const timeout = window.setTimeout(clearToast, duration);
    return () => window.clearTimeout(timeout);
  }, [clearToast, duration, message]);

  return { toastMessage: message, showToast, clearToast };
}
