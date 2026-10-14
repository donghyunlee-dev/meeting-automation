export type ApiErrorCategory = 'VALIDATION' | 'NOT_FOUND' | 'CONFLICT' | 'AUTHENTICATION' | 'AUTHORIZATION' | 'UPSTREAM' | 'UNKNOWN';
export type ApiErrorKind = 'validation' | 'not-found' | 'conflict' | 'unavailable' | 'unknown';

export type SafeApiError = {
  code: string;
  category: ApiErrorCategory;
  kind: ApiErrorKind;
  retryable: boolean;
  traceId: string | null;
  status: number | null;
  message: string;
};

const SAFE_MESSAGES: Record<string, { kind: ApiErrorKind; message: string }> = {
  MEETING_NOT_FOUND: { kind: 'not-found', message: '회의 기록을 찾을 수 없습니다.' },
  SESSION_NOT_FOUND: { kind: 'not-found', message: '회의를 찾을 수 없습니다.' },
  MEETING_LIST_FAILED: { kind: 'unavailable', message: '회의 목록을 불러오지 못했습니다. 연결을 확인한 뒤 다시 시도해 주세요.' },
  MEETING_DETAIL_FAILED: { kind: 'unavailable', message: '회의 상세를 불러오지 못했습니다. 연결을 확인한 뒤 다시 시도해 주세요.' },
  VALIDATION_FAILED: { kind: 'validation', message: '입력 내용을 확인해 주세요.' },
  SESSION_VERSION_CONFLICT: { kind: 'conflict', message: '회의가 변경되었습니다. 최신 상태를 확인한 뒤 다시 시도해 주세요.' },
  SESSION_STATE_CONFLICT: { kind: 'conflict', message: '현재 회의 상태에서 요청할 수 없습니다. 최신 상태를 확인해 주세요.' },
  DOCUMENT_CHANGE_BUSY: { kind: 'conflict', message: '문서 설정 변경이 끝난 뒤 다시 시도해 주세요.' },
  NETWORK_ERROR: { kind: 'unavailable', message: '연결을 확인한 뒤 다시 시도해 주세요.' },
  PROVIDER_FAILURE: { kind: 'unavailable', message: '요청을 완료하지 못했습니다. 잠시 후 다시 시도해 주세요.' },
  REQUEST_FAILED: { kind: 'unavailable', message: '요청을 완료하지 못했습니다. 잠시 후 다시 시도해 주세요.' },
  UNKNOWN_ERROR: { kind: 'unknown', message: '요청을 완료하지 못했습니다. 잠시 후 다시 시도해 주세요.' },
};

const CATEGORIES = new Set<ApiErrorCategory>([
  'VALIDATION', 'NOT_FOUND', 'CONFLICT', 'AUTHENTICATION', 'AUTHORIZATION', 'UPSTREAM', 'UNKNOWN',
]);

function objectRecord(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null && !Array.isArray(value) ? value as Record<string, unknown> : null;
}

function safeCode(value: unknown): string {
  return typeof value === 'string' && /^[A-Z][A-Z0-9_]{0,63}$/.test(value) ? value : 'UNKNOWN_ERROR';
}

function safeCategory(value: unknown): ApiErrorCategory {
  return typeof value === 'string' && CATEGORIES.has(value as ApiErrorCategory) ? value as ApiErrorCategory : 'UNKNOWN';
}

function safeTraceId(value: unknown): string | null {
  return typeof value === 'string' && /^[A-Za-z0-9_-]{1,128}$/.test(value) ? value : null;
}

export function normalizeApiError(value: unknown, status?: number): SafeApiError {
  const envelope = objectRecord(value);
  const bodyError = objectRecord(envelope?.error);
  const code = safeCode(bodyError?.code);
  const mapped = SAFE_MESSAGES[code] ?? (status === 409 || status === 412
      ? { kind: 'conflict' as const, message: '요청한 내용을 적용하지 못했습니다. 최신 상태를 확인해 주세요.' }
      : { kind: 'unavailable' as const, message: '요청을 완료하지 못했습니다. 잠시 후 다시 시도해 주세요.' });
  const category = safeCategory(bodyError?.category);

  return {
    code,
    category: category === 'UNKNOWN' ? mapped.kind === 'not-found' ? 'NOT_FOUND' : mapped.kind === 'conflict' ? 'CONFLICT' : category : category,
    kind: mapped.kind,
    retryable: bodyError?.retryable === true,
    traceId: safeTraceId(bodyError?.traceId),
    status: typeof status === 'number' && Number.isInteger(status) && status >= 100 && status <= 599 ? status : null,
    message: mapped.message,
  };
}
