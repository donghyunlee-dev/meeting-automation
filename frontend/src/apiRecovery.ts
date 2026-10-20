import { getApiBaseUrl, getApiHealthUrl } from './apiBase';

export type ApiRecoveryState =
  | { status: 'idle' }
  | { status: 'recovering'; visible: boolean }
  | { status: 'ready'; visible: boolean };

export type ApiRecoveryResult = 'already-ready' | 'recovered' | 'dismissed';

const HEALTH_TIMEOUT_MS = 3_000;
const HEALTH_POLL_INTERVAL_MS = 4_000;
const READY_ANIMATION_MS = 1_050;
const DISMISS_COOLDOWN_MS = 15_000;

let state: ApiRecoveryState = { status: 'idle' };
let startingRecovery: Promise<ApiRecoveryResult> | null = null;
let dismissedUntil = 0;
let activeRecovery: {
  promise: Promise<ApiRecoveryResult>;
  finish: (result: ApiRecoveryResult) => void;
  wake: () => void;
  dismiss: () => void;
} | null = null;
const listeners = new Set<() => void>();

export function getApiRecoveryState(): ApiRecoveryState {
  return state;
}

export function subscribeApiRecovery(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function recoverBackendAvailability(fetchImpl: typeof fetch = fetch): Promise<ApiRecoveryResult> {
  if (activeRecovery) return activeRecovery.promise;
  if (startingRecovery) return startingRecovery;
  if (Date.now() < dismissedUntil) return Promise.resolve('dismissed');

  startingRecovery = probeBackend(fetchImpl).then((ready) => {
    if (ready) return 'already-ready' as const;
    return startRecovery(fetchImpl);
  }).finally(() => {
    startingRecovery = null;
  });

  return startingRecovery;
}

export function retryApiRecovery(): void {
  activeRecovery?.wake();
}

export function dismissApiRecovery(): void {
  activeRecovery?.dismiss();
}

async function probeBackend(fetchImpl: typeof fetch): Promise<boolean> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), HEALTH_TIMEOUT_MS);
  try {
    let response = await fetchImpl(getApiHealthUrl(), {
      method: 'GET',
      headers: { Accept: 'application/json' },
      signal: controller.signal,
      cache: 'no-store',
    });
    if (response.status === 404) {
      response = await fetchImpl(`${getApiBaseUrl()}/actuator/health`, {
        method: 'GET',
        headers: { Accept: 'application/json' },
        signal: controller.signal,
        cache: 'no-store',
      });
    }
    if (!response.ok) return false;
    const body: unknown = await response.json();
    if (typeof body !== 'object' || body === null) return false;
    if ('data' in body && typeof body.data === 'object' && body.data !== null
      && 'status' in body.data && body.data.status === 'UP') return true;
    return 'status' in body && body.status === 'UP';
  } catch {
    return false;
  } finally {
    clearTimeout(timeout);
  }
}

function startRecovery(fetchImpl: typeof fetch): Promise<ApiRecoveryResult> {
  let resolvePromise!: (result: ApiRecoveryResult) => void;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let wakePoll: (() => void) | undefined;
  let retryRequested = false;
  let settled = false;
  const promise = new Promise<ApiRecoveryResult>((resolve) => { resolvePromise = resolve; });

  const finish = (result: ApiRecoveryResult) => {
    if (settled) return;
    settled = true;
    if (timer !== undefined) clearTimeout(timer);
    wakePoll?.();
    wakePoll = undefined;
    if (activeRecovery?.promise === promise) activeRecovery = null;
    setState({ status: 'idle' });
    resolvePromise(result);
  };

  const wake = () => {
    retryRequested = true;
    if (timer !== undefined) clearTimeout(timer);
    wakePoll?.();
  };

  const recovery = {
    promise,
    finish,
    wake,
    dismiss: () => {
      dismissedUntil = Date.now() + DISMISS_COOLDOWN_MS;
      finish('dismissed');
    },
  };
  activeRecovery = recovery;
  setState({ status: 'recovering', visible: true });

  const poll = async () => {
    while (!settled) {
      const ready = await probeBackend(fetchImpl);
      if (settled) return;
      if (ready) {
        const visible = state.status === 'recovering' && state.visible;
        setState({ status: 'ready', visible });
        await new Promise<void>((resolve) => {
          wakePoll = resolve;
          timer = setTimeout(resolve, READY_ANIMATION_MS);
        });
        finish('recovered');
        return;
      }

      if (retryRequested) {
        retryRequested = false;
        continue;
      }
      await new Promise<void>((resolve) => {
        wakePoll = resolve;
        timer = setTimeout(resolve, HEALTH_POLL_INTERVAL_MS);
      });
      wakePoll = undefined;
      retryRequested = false;
    }
  };

  void poll().catch(() => finish('dismissed'));
  return promise;
}

function setState(next: ApiRecoveryState): void {
  state = next;
  for (const listener of listeners) listener();
}
