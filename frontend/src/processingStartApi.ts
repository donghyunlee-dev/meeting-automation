export type ProcessingStartRequest = {
  expectedChunks: number;
  durationMs: number;
  mimeType: string;
};

export type ProcessingStartResponse = {
  sessionId: string;
  status: 'PROCESSING';
  stage: string;
};

type ProcessingStartEnvelope = {
  data?: Partial<ProcessingStartResponse>;
  error?: { code?: string };
};

export class ProcessingStartApiError extends Error {
  constructor(readonly code: string, readonly status?: number, readonly responseUncertain = false) {
    super('처리 시작 요청을 완료하지 못했습니다.');
    this.name = 'ProcessingStartApiError';
  }
}

export type ProcessingStartOptions = { timeoutMs?: number };

const DEFAULT_REQUEST_TIMEOUT_MS = 15_000;

export async function startProcessing(
  sessionId: string,
  request: ProcessingStartRequest,
  idempotencyKey: string,
  fetchImpl: typeof fetch = fetch,
  options: ProcessingStartOptions = {},
): Promise<ProcessingStartResponse> {
  let response: Response;
  let body: ProcessingStartEnvelope | undefined;
  try {
    ({ response, body } = await requestAndRead(fetchImpl, `${baseUrl()}/api/v1/meeting-sessions/${encodeURIComponent(sessionId)}/process`, {
      method: 'POST',
      headers: { Accept: 'application/json', 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify(request),
    }, options.timeoutMs ?? DEFAULT_REQUEST_TIMEOUT_MS));
  } catch (error) {
    if (error instanceof RequestTimeoutError) {
      throw new ProcessingStartApiError('REQUEST_TIMEOUT', error.status, true);
    }
    throw new ProcessingStartApiError('NETWORK_ERROR', undefined, true);
  }

  if (response.status !== 202) {
    const status = response.status;
    throw new ProcessingStartApiError(body?.error?.code ?? 'PROCESSING_START_FAILED', status, status >= 500 || status === 408 || status === 429);
  }

  const data = body?.data;
  if (data?.sessionId !== sessionId || data.status !== 'PROCESSING' || typeof data.stage !== 'string' || !data.stage) {
    throw new ProcessingStartApiError(data?.sessionId && data.sessionId !== sessionId ? 'PROCESSING_SESSION_MISMATCH' : 'INVALID_PROCESSING_RESPONSE', 202, true);
  }
  return { sessionId: data.sessionId, status: 'PROCESSING', stage: data.stage };
}

class RequestTimeoutError extends Error {
  constructor(readonly status?: number) { super('Processing start request timed out'); }
}

async function readBody(response: Response, signal: AbortSignal): Promise<ProcessingStartEnvelope | undefined> {
  try { return await response.json() as ProcessingStartEnvelope; }
  catch (error) {
    if (signal.aborted) throw error;
    return undefined;
  }
}

async function requestAndRead(fetchImpl: typeof fetch, input: RequestInfo | URL, init: RequestInit, timeoutMs: number): Promise<{ response: Response; body?: ProcessingStartEnvelope }> {
  const controller = new AbortController();
  let response: Response | undefined;
  let timeoutTriggered = false;
  let timeoutHandle: ReturnType<typeof setTimeout> | undefined;
  const timeout = new Promise<never>((_resolve, reject) => {
    timeoutHandle = setTimeout(() => {
      timeoutTriggered = true;
      reject(new RequestTimeoutError(response?.status));
      controller.abort();
    }, timeoutMs);
  });
  const request = (async () => {
    try {
      response = await fetchImpl(input, { ...init, signal: controller.signal });
      const body = await readBody(response, controller.signal);
      return { response, body };
    } catch (error) {
      if (timeoutTriggered) throw new RequestTimeoutError(response?.status);
      throw error;
    }
  })();
  try { return await Promise.race([request, timeout]); }
  finally { if (timeoutHandle !== undefined) clearTimeout(timeoutHandle); }
}

function baseUrl(): string {
  return (import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
}
