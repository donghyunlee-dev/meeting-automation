import { useEffect } from 'react';
import { useLocation } from 'react-router';

const LEAVE_WARNING = '진행 중이거나 저장되지 않은 내용이 있습니다. 화면을 벗어나면 작업이나 수정 내용이 사라질 수 있습니다. 이동하시겠습니까?';

export function NavigationLeaveGuard({ active }: { active: boolean }) {
  const location = useLocation();

  useEffect(() => {
    if (!active) return undefined;

    let restoringHistory = false;
    const confirmLeave = () => window.confirm(LEAVE_WARNING);
    const onClick = (event: MouseEvent) => {
      if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
      const target = event.target;
      if (!(target instanceof Element)) return;
      const anchor = target.closest('a[href]');
      if (!(anchor instanceof HTMLAnchorElement) || anchor.target === '_blank' || anchor.hasAttribute('download')) return;
      const destination = new URL(anchor.href, window.location.href);
      if (destination.origin !== window.location.origin || destination.pathname === location.pathname) return;
      if (!confirmLeave()) {
        event.preventDefault();
        event.stopPropagation();
      }
    };
    const onPopState = (event: PopStateEvent) => {
      if (restoringHistory) {
        restoringHistory = false;
        return;
      }
      if (confirmLeave()) return;
      event.stopImmediatePropagation();
      restoringHistory = true;
      window.history.forward();
    };
    const onBeforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = '';
    };

    document.addEventListener('click', onClick, true);
    window.addEventListener('popstate', onPopState, true);
    window.addEventListener('beforeunload', onBeforeUnload);
    return () => {
      document.removeEventListener('click', onClick, true);
      window.removeEventListener('popstate', onPopState, true);
      window.removeEventListener('beforeunload', onBeforeUnload);
    };
  }, [active, location.pathname]);

  return null;
}
