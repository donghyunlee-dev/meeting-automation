import type { StoredChunk } from './chunkRepository';

type ErrorEnvelope = { error?: { code?: string } };

export class AudioUploadApiError extends Error {
  constructor(readonly code: string, readonly status?: number, readonly responseUncertain = false) {
    super('오디오 업로드 요청을 완료하지 못했습니다. 연결 상태를 확인하고 다시 시도해 주세요.');
    this.name = 'AudioUploadApiError';
  }
}

export type ReceivedAudioStatus = {
  receivedSequences: number[];
  receivedChunks: number;
  totalBytes: number;
};

export type ReceivedChunkAck = { sequence: number; received: true };
export type AudioUploadRequestOptions = { timeoutMs?: number };

const DEFAULT_REQUEST_TIMEOUT_MS = 15_000;

class RequestTimeoutError extends Error {
  constructor(readonly status?: number) { super('Audio upload request timed out'); }
}

export async function getReceivedAudioStatus(sessionId: string, fetchImpl: typeof fetch = fetch, options: AudioUploadRequestOptions = {}): Promise<ReceivedAudioStatus> {
  let response: Response;
  let body: unknown;
  try {
    ({ response, body } = await requestAndRead(fetchImpl, `${baseUrl()}/api/v1/meeting-sessions/${encodeURIComponent(sessionId)}/audio`, {
      headers: { Accept: 'application/json' },
    }, options.timeoutMs ?? DEFAULT_REQUEST_TIMEOUT_MS));
  } catch (error) {
    if (error instanceof RequestTimeoutError) throw timeoutApiError(error);
    throw new AudioUploadApiError('NETWORK_ERROR', undefined, true);
  }

  if (!response.ok) throw apiError(response.status, body);
  const data = (body as { data?: Partial<ReceivedAudioStatus> } | undefined)?.data;
  if (!data || !Array.isArray(data.receivedSequences) || !data.receivedSequences.every((sequence) => Number.isInteger(sequence) && sequence >= 0)) {
    throw new AudioUploadApiError('INVALID_AUDIO_STATUS');
  }
  return {
    receivedSequences: [...new Set(data.receivedSequences)].sort((a, b) => a - b),
    receivedChunks: Number.isInteger(data.receivedChunks) ? data.receivedChunks! : data.receivedSequences.length,
    totalBytes: Number.isFinite(data.totalBytes) ? data.totalBytes! : 0,
  };
}

export async function putAudioChunk(chunk: StoredChunk, fetchImpl: typeof fetch = fetch, options: AudioUploadRequestOptions = {}): Promise<ReceivedChunkAck> {
  let digest: ArrayBuffer;
  try {
    digest = await crypto.subtle.digest('SHA-256', new Uint8Array(await chunk.blob.arrayBuffer()));
  } catch {
    throw new AudioUploadApiError('DIGEST_UNAVAILABLE');
  }
  const sha256 = [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, '0')).join('');
  let response: Response;
  let body: unknown;
  try {
    ({ response, body } = await requestAndRead(fetchImpl, `${baseUrl()}/api/v1/meeting-sessions/${encodeURIComponent(chunk.sessionId)}/audio/chunks/${chunk.sequence}`, {
      method: 'PUT',
      headers: {
        Accept: 'application/json',
        'Content-Type': chunk.mimeType,
        'X-Audio-SHA256': sha256,
        'X-Audio-Byte-Length': String(chunk.blob.size),
        'Idempotency-Key': chunk.chunkId,
      },
      body: chunk.blob,
    }, options.timeoutMs ?? DEFAULT_REQUEST_TIMEOUT_MS));
  } catch (error) {
    if (error instanceof RequestTimeoutError) throw timeoutApiError(error);
    throw new AudioUploadApiError('NETWORK_ERROR', undefined, true);
  }

  if (!response.ok) throw apiError(response.status, body);
  const data = (body as { data?: Partial<ReceivedChunkAck> } | undefined)?.data;
  if (data?.received !== true || data.sequence !== chunk.sequence) {
    throw new AudioUploadApiError('INVALID_UPLOAD_ACK', undefined, true);
  }
  return { sequence: data.sequence, received: true };
}

function apiError(status: number, body: unknown): AudioUploadApiError {
  const code = (body as ErrorEnvelope | undefined)?.error?.code ?? 'UPLOAD_REQUEST_FAILED';
  return new AudioUploadApiError(code, status, status >= 500 || status === 408 || status === 429);
}

async function readBody(response: Response, signal?: AbortSignal): Promise<unknown> {
  try { return await response.json(); } catch (error) {
    if (signal?.aborted) throw error;
    return undefined;
  }
}

async function requestAndRead(fetchImpl: typeof fetch, input: RequestInfo | URL, init: RequestInit, timeoutMs: number): Promise<{ response: Response; body: unknown }> {
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

  try {
    return await Promise.race([request, timeout]);
  } finally {
    if (timeoutHandle !== undefined) clearTimeout(timeoutHandle);
  }
}

function timeoutApiError(error: RequestTimeoutError): AudioUploadApiError {
  const status = error.status !== undefined && error.status >= 400 ? error.status : undefined;
  return new AudioUploadApiError('REQUEST_TIMEOUT', status, true);
}

function baseUrl(): string {
  return (import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
}
