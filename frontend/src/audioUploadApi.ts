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

export async function getReceivedAudioStatus(sessionId: string, fetchImpl: typeof fetch = fetch): Promise<ReceivedAudioStatus> {
  let response: Response;
  try {
    response = await fetchImpl(`${baseUrl()}/api/v1/meeting-sessions/${encodeURIComponent(sessionId)}/audio`, {
      headers: { Accept: 'application/json' },
    });
  } catch {
    throw new AudioUploadApiError('NETWORK_ERROR', undefined, true);
  }

  const body = await readBody(response);
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

export async function putAudioChunk(chunk: StoredChunk, fetchImpl: typeof fetch = fetch): Promise<ReceivedChunkAck> {
  let digest: ArrayBuffer;
  try {
    digest = await crypto.subtle.digest('SHA-256', new Uint8Array(await chunk.blob.arrayBuffer()));
  } catch {
    throw new AudioUploadApiError('DIGEST_UNAVAILABLE');
  }
  const sha256 = [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, '0')).join('');
  let response: Response;
  try {
    response = await fetchImpl(`${baseUrl()}/api/v1/meeting-sessions/${encodeURIComponent(chunk.sessionId)}/audio/chunks/${chunk.sequence}`, {
      method: 'PUT',
      headers: {
        Accept: 'application/json',
        'Content-Type': chunk.mimeType,
        'X-Audio-SHA256': sha256,
        'X-Audio-Byte-Length': String(chunk.blob.size),
        'Idempotency-Key': chunk.chunkId,
      },
      body: chunk.blob,
    });
  } catch {
    throw new AudioUploadApiError('NETWORK_ERROR', undefined, true);
  }

  const body = await readBody(response);
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

async function readBody(response: Response): Promise<unknown> {
  try { return await response.json(); } catch { return undefined; }
}

function baseUrl(): string {
  return (import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
}
