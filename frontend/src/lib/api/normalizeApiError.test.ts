import { describe, expect, it } from 'vitest';
import { normalizeApiError } from './normalizeApiError';

describe('normalizeApiError', () => {
  it('maps known API codes to static safe messages without retaining raw details', () => {
    const error = normalizeApiError({ error: {
      code: 'MEETING_NOT_FOUND', category: 'NOT_FOUND', retryable: true, traceId: 'trace-404',
      message: 'provider token=secret', details: { body: 'private transcript' },
    } }, 404);

    expect(error.code).toBe('MEETING_NOT_FOUND');
    expect(error.kind).toBe('not-found');
    expect(error.message).toBe('회의 기록을 찾을 수 없습니다.');
    expect(error.traceId).toBe('trace-404');
    expect(JSON.stringify(error)).not.toMatch(/secret|transcript|provider token/);
  });

  it('uses a generic safe message for unknown and malformed errors', () => {
    const unknown = normalizeApiError({ error: { code: 'PRIVATE_PROVIDER_DETAIL', message: 'secret payload' } }, 502);
    const malformed = normalizeApiError('raw network response', 503);

    expect(unknown.message).toBe('요청을 완료하지 못했습니다. 잠시 후 다시 시도해 주세요.');
    expect(unknown.kind).toBe('unavailable');
    expect(malformed.message).toBe('요청을 완료하지 못했습니다. 잠시 후 다시 시도해 주세요.');
    expect(malformed.code).toBe('UNKNOWN_ERROR');
  });

  it('does not treat an unknown HTTP 404 as a missing meeting', () => {
    const missingMeeting = normalizeApiError({ error: { code: 'MEETING_NOT_FOUND' } }, 404);
    const unknown404 = normalizeApiError({ error: { code: 'UNEXPECTED_PROXY_404', message: 'proxy internals' } }, 404);

    expect(missingMeeting.kind).toBe('not-found');
    expect(missingMeeting.category).toBe('NOT_FOUND');
    expect(unknown404.kind).toBe('unavailable');
    expect(unknown404.category).toBe('UNKNOWN');
    expect(unknown404.message).toBe(normalizeApiError({ error: { code: 'REQUEST_FAILED' } }, 502).message);
    expect(JSON.stringify(unknown404)).not.toContain('proxy internals');
  });

  it('accepts only a bounded safe trace identifier and known error categories', () => {
    const error = normalizeApiError({ error: {
      code: 'REQUEST_FAILED', category: 'arbitrary raw text', retryable: 'yes', traceId: 'trace<secret>',
    } }, 500);

    expect(error.category).toBe('UNKNOWN');
    expect(error.retryable).toBe(false);
    expect(error.traceId).toBeNull();
  });
});
