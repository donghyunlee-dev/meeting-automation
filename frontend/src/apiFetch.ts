import { getApiBaseUrl } from './apiBase';
import { recoverBackendAvailability } from './apiRecovery';

export type ApiFetchOptions = { timeoutMs?: number };

export class ApiRequestTimeoutError extends Error {
  constructor(readonly status?: number) {
    super('Backend API request timed out');
    this.name = 'ApiRequestTimeoutError';
  }
}

const DEFAULT_API_REQUEST_TIMEOUT_MS = 15_000;
const GATEWAY_FAILURES = new Set([408, 502, 503, 504]);

export async function apiFetch(
  input: RequestInfo | URL,
  init: RequestInit = {},
  options: ApiFetchOptions = {},
  fetchImpl: typeof fetch = fetch,
): Promise<Response> {
  const timeoutMs = options.timeoutMs ?? DEFAULT_API_REQUEST_TIMEOUT_MS;
  let response: Response | undefined;
  let requestError: unknown;
  let requestFailed = false;

  try {
    response = await fetchWithTimeout(input, init, timeoutMs, fetchImpl);
  } catch (error) {
    requestError = error;
    requestFailed = true;
    if (!isBackendRequest(input) || isCallerAbort(error, getCallerSignal(input, init))) throw error;
  }

  if (response && (!isBackendRequest(input) || !GATEWAY_FAILURES.has(response.status))) return response;

  const recovery = await waitForRecovery(
    recoverBackendAvailability(fetchImpl),
    getCallerSignal(input, init),
  );
  if (recovery === 'recovered' && canReplayRequest(input, init)) {
    if (init.signal?.aborted) throw abortError();
    try {
      return await fetchWithTimeout(input, init, timeoutMs, fetchImpl);
    } catch (error) {
      if (isCallerAbort(error, getCallerSignal(input, init))) throw error;
      throw error;
    }
  }

  if (requestFailed) throw requestError;
  return response!;
}

function waitForRecovery(
  recovery: Promise<'already-ready' | 'recovered' | 'dismissed'>,
  signal?: AbortSignal | null,
): Promise<'already-ready' | 'recovered' | 'dismissed'> {
  if (!signal) return recovery;
  if (signal.aborted) return Promise.reject(abortError());

  return new Promise((resolve, reject) => {
    const cleanup = () => signal.removeEventListener('abort', onAbort);
    const onAbort = () => {
      cleanup();
      reject(abortError());
    };
    signal.addEventListener('abort', onAbort, { once: true });
    recovery.then((result) => {
      cleanup();
      resolve(result);
    }, (error: unknown) => {
      cleanup();
      reject(error);
    });
  });
}

function fetchWithTimeout(input: RequestInfo | URL, init: RequestInit, timeoutMs: number, fetchImpl: typeof fetch): Promise<Response> {
  const controller = new AbortController();
  const callerSignal = init.signal ?? (typeof Request !== 'undefined' && input instanceof Request ? input.signal : undefined);
  if (callerSignal?.aborted) return Promise.reject(abortError());

  let timeoutTriggered = false;
  const abortFromCaller = () => controller.abort();
  callerSignal?.addEventListener('abort', abortFromCaller, { once: true });
  const timeout = setTimeout(() => {
    timeoutTriggered = true;
    controller.abort();
  }, timeoutMs);

  return fetchImpl(input, { ...init, signal: controller.signal }).catch((error: unknown) => {
    if (timeoutTriggered) throw new ApiRequestTimeoutError();
    throw error;
  }).finally(() => {
    clearTimeout(timeout);
    callerSignal?.removeEventListener('abort', abortFromCaller);
  });
}

function isBackendRequest(input: RequestInfo | URL): boolean {
  try {
    const requestUrl = typeof Request !== 'undefined' && input instanceof Request ? input.url : input.toString();
    const pageUrl = typeof window === 'undefined' ? 'http://localhost/' : window.location.href;
    const target = new URL(requestUrl, pageUrl);
    const apiBase = getApiBaseUrl();
    const backendOrigin = apiBase ? new URL(apiBase, pageUrl).origin : new URL(pageUrl).origin;
    return target.origin === backendOrigin && target.pathname.startsWith('/api/v1/');
  } catch {
    return false;
  }
}

function canReplayRequest(input: RequestInfo | URL, init: RequestInit): boolean {
  const request = typeof Request !== 'undefined' && input instanceof Request ? input : undefined;
  const method = (init.method ?? request?.method ?? 'GET').toUpperCase();
  const body = init.body ?? (request?.body ? undefined : null);
  if (method === 'GET' || method === 'HEAD') return body === null;
  if (request?.body && init.body === undefined) return false;
  const headers = new Headers(request?.headers);
  new Headers(init.headers).forEach((value, key) => headers.set(key, value));
  return headers.has('Idempotency-Key') && isReplayableBody(init.body);
}

function isReplayableBody(body: BodyInit | null | undefined): boolean {
  if (body == null || typeof body === 'string') return true;
  if (typeof Blob !== 'undefined' && body instanceof Blob) return true;
  if (typeof URLSearchParams !== 'undefined' && body instanceof URLSearchParams) return true;
  if (typeof FormData !== 'undefined' && body instanceof FormData) return true;
  if (body instanceof ArrayBuffer || ArrayBuffer.isView(body)) return true;
  return false;
}

function isCallerAbort(error: unknown, signal?: AbortSignal | null): boolean {
  return Boolean(signal?.aborted) || (error instanceof DOMException && error.name === 'AbortError');
}

function getCallerSignal(input: RequestInfo | URL, init: RequestInit): AbortSignal | null | undefined {
  return init.signal ?? (typeof Request !== 'undefined' && input instanceof Request ? input.signal : undefined);
}

function abortError(): DOMException {
  return new DOMException('The operation was aborted.', 'AbortError');
}
