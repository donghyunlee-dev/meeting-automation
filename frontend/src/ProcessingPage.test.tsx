import { withHealthyBackend } from './testUtils/withHealthyBackend';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router';
import { ProcessingPage } from './ProcessingPage';

afterEach(() => { cleanup(); vi.unstubAllGlobals(); sessionStorage.clear(); });

function returnFromAudioDownload() {
  Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' });
  fireEvent(window, new Event('focus'));
  fireEvent(document, new Event('visibilitychange'));
}

function apiSnapshot(status: string, actions: string[] = [], stage = 'TRANSCRIPTION', version = 4) {
  return { data: { sessionId: 's-1', version, status, processing: { stage, retryable: true, audioAvailable: true, audioExpiresAt: '2026-10-30T00:00:00Z', errorCode: 'PRIVATE_PROVIDER_FAILURE' }, allowedActions: actions } };
}

function renderPage() {
  return render(<MemoryRouter initialEntries={['/meetings/s-1/processing']}><Routes>
    <Route path="/meetings/:sessionId/processing" element={<ProcessingPage />} />
    <Route path="/meetings/new" element={<h1>새 회의 시작</h1>} />
    <Route path="/meetings/:sessionId/complete" element={<h1>실패 기록 완료</h1>} />
    <Route path="/meetings/:sessionId/review" element={<h1>회의 검토</h1>} />
  </Routes></MemoryRouter>);
}

describe('ProcessingPage failure recovery', () => {
  it('ASYNC-GET-RETRY shows the shared safe network error and retries API-010 with GET', async () => {
    let available = false;
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      if (!available) throw new TypeError('private proxy diagnostic');
      expect(!init?.method || init.method === 'GET').toBe(true);
      return new Response(JSON.stringify(apiSnapshot('PROCESSING', [], 'TRANSCRIPTION')), { status: 200 });
    });
    vi.stubGlobal('fetch', withHealthyBackend(fetchMock));
    renderPage();

    expect(await screen.findByRole('heading', { name: '처리 상태를 불러오지 못했습니다.' })).toBeTruthy();
    expect(document.body.textContent).not.toContain('private proxy diagnostic');
    available = true;
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    expect(await screen.findByText('전체 4단계 중 2단계 · 음성 인식 진행 중')).toBeTruthy();
    expect(fetchMock.mock.calls.filter(([, init]) => !init?.method || init.method === 'GET').length).toBe(fetchMock.mock.calls.length);
  });

  it('shows safe Korean stage copy and only actions allowed by API-010', async () => {
    const body = apiSnapshot('PROCESSING_FAILED', ['RETRY_PROCESSING', 'DOWNLOAD_AUDIO', 'FINALIZE_PROCESSING_FAILURE']);
    body.data.processing.retryable = false;
    body.data.processing.errorCode = 'TRANSCRIPTION_AUTHENTICATION_FAILED';
    vi.stubGlobal('fetch', withHealthyBackend(vi.fn(async () => new Response(JSON.stringify(body), { status: 200 }))));
    renderPage();
    expect(await screen.findByRole('heading', { name: '처리를 완료하지 못했습니다' })).toBeTruthy();
    expect(screen.getByText('음성 인식 서비스 연결 설정을 확인해 주세요.', { selector: '.processing-failure-detail' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeTruthy();
    expect(screen.queryByRole('link', { name: '원본 오디오 다운로드' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '오디오 다운로드 준비' }));
    expect((await screen.findByRole('link', { name: '원본 오디오 다운로드' })).getAttribute('href')).toContain('/audio/download');
    expect(screen.queryByText(/TRANSCRIPTION_AUTHENTICATION_FAILED/)).toBeNull();
  });

  it('offers an API-authorized retry for recoverable audio even when retryable is false', async () => {
    const body = apiSnapshot('PROCESSING_FAILED', ['RETRY_PROCESSING', 'DOWNLOAD_AUDIO']);
    body.data.processing.retryable = false;
    body.data.processing.audioAvailable = false;
    vi.stubGlobal('fetch', withHealthyBackend(vi.fn(async () => new Response(JSON.stringify(body), { status: 200 }))));
    renderPage();
    await screen.findByRole('heading', { name: '처리를 완료하지 못했습니다' });
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeTruthy();
    expect(screen.queryByRole('link', { name: '원본 오디오 다운로드' })).toBeNull();
  });

  it('refreshes API-010 on foreground and removes recovery actions no longer allowed', async () => {
    let statusReads = 0;
    const fetchMock = vi.fn(async () => {
      statusReads += 1;
      const actions = statusReads === 1 ? ['DOWNLOAD_AUDIO', 'FINALIZE_PROCESSING_FAILURE'] : ['FINALIZE_PROCESSING_FAILURE'];
      const body = apiSnapshot('PROCESSING_FAILED', actions);
      if (statusReads > 1) body.data.processing.audioAvailable = false;
      return new Response(JSON.stringify(body), { status: 200 });
    });
    vi.stubGlobal('fetch', withHealthyBackend(fetchMock));
    renderPage();
    expect(await screen.findByRole('button', { name: '오디오 다운로드 준비' })).toBeTruthy();
    returnFromAudioDownload();
    await waitFor(() => expect(screen.queryByRole('button', { name: '오디오 다운로드 준비' })).toBeNull());
    expect(statusReads).toBeGreaterThan(1);
  });

  it('submits one retry with current version, disables repeat clicks, and reconciles to PROCESSING', async () => {
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === 'POST') return new Response(JSON.stringify({ data: { sessionId: 's-1', status: 'PROCESSING', stage: 'TRANSCRIPTION' } }), { status: 202 });
      return new Response(JSON.stringify(apiSnapshot(fetchMock.mock.calls.some(([, request]) => request?.method === 'POST') ? 'PROCESSING' : 'PROCESSING_FAILED', ['RETRY_PROCESSING'], 'TRANSCRIPTION', 7)), { status: 200 });
    });
    vi.stubGlobal('fetch', withHealthyBackend(fetchMock));
    renderPage();
    const retry = await screen.findByRole('button', { name: '다시 시도' });
    fireEvent.click(retry);
    await screen.findByText('전체 4단계 중 2단계 · 음성 인식 진행 중');
    const post = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST');
    expect(post?.[0]).toContain('/processing/retry');
    expect(new Headers(post?.[1]?.headers).get('If-Match')).toBe('"7"');
    expect(new Headers(post?.[1]?.headers).get('Idempotency-Key')).toBeTruthy();
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1);
  });

  it('reconciles a timed-out retry through API-010 instead of exposing another retry', async () => {
    let statusReads = 0;
    const fetchMock = vi.fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>(async (_input, init) => {
      if (init?.method === 'POST') throw new TypeError('connection dropped after request');
      statusReads += 1;
      return new Response(JSON.stringify(apiSnapshot(statusReads === 1 ? 'PROCESSING_FAILED' : 'PROCESSING', ['RETRY_PROCESSING'], 'TRANSCRIPTION', 5)), { status: 200 });
    });
    vi.stubGlobal('fetch', withHealthyBackend(fetchMock));
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: '다시 시도' }));
    expect(await screen.findByText('전체 4단계 중 2단계 · 음성 인식 진행 중')).toBeTruthy();
    expect(screen.queryByRole('button', { name: '다시 시도' })).toBeNull();
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1);
  });

  it('requires explicit discard confirmation and finalizes only with DISCARDED', async () => {
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => init?.method === 'POST'
      ? new Response('', { status: 202 })
      : new Response(JSON.stringify(apiSnapshot(fetchMock.mock.calls.some(([, request]) => request?.method === 'POST') ? 'COMPLETED_WITH_WARNINGS' : 'PROCESSING_FAILED', ['FINALIZE_PROCESSING_FAILURE'], 'DIARIZATION', 3)), { status: 200 }));
    vi.stubGlobal('fetch', withHealthyBackend(fetchMock));
    renderPage();
    const discardTrigger = await screen.findByRole('button', { name: '오디오 없이 실패 마무리' });
    fireEvent.click(discardTrigger);
    expect(screen.getByRole('dialog', { name: '오디오 없이 마무리' })).toBeTruthy();
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '실패 마무리' }));
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByRole('dialog', { name: '오디오 없이 마무리' })).toBeNull();
    expect(document.activeElement).toBe(discardTrigger);
    fireEvent.click(discardTrigger);
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(0);
    fireEvent.click(screen.getByRole('button', { name: '실패 마무리' }));
    expect(await screen.findByRole('heading', { name: '실패 기록을 저장했습니다' })).toBeTruthy();
    const post = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST');
    expect(JSON.parse(String(post?.[1]?.body))).toEqual({ audioDisposition: 'DISCARDED' });
  });

  it('waits for the user to confirm a native attachment download before DOWNLOADED finalization', async () => {
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => init?.method === 'POST'
      ? new Response('', { status: 202 })
      : new Response(JSON.stringify(apiSnapshot(fetchMock.mock.calls.some(([, request]) => request?.method === 'POST') ? 'COMPLETED_WITH_WARNINGS' : 'PROCESSING_FAILED', ['DOWNLOAD_AUDIO', 'FINALIZE_PROCESSING_FAILURE'])), { status: 200 }));
    vi.stubGlobal('fetch', withHealthyBackend(fetchMock));
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: '오디오 다운로드 준비' }));
    const link = await screen.findByRole('link', { name: '원본 오디오 다운로드' });
    expect(link.getAttribute('href')).toContain('/audio/download');
    expect(link.getAttribute('target')).toBe('_blank');
    expect(link.getAttribute('rel')).toContain('noopener');
    fireEvent.click(link);
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(0);
    const readsBeforeReturn = fetchMock.mock.calls.length;
    returnFromAudioDownload();
    await waitFor(() => expect(fetchMock.mock.calls.length).toBeGreaterThan(readsBeforeReturn));
    const savedButton = await screen.findByRole('button', { name: '저장했어요' });
    expect(document.activeElement).toBe(savedButton);
    fireEvent.click(savedButton);
    expect(await screen.findByRole('heading', { name: '실패 기록을 저장했습니다' })).toBeTruthy();
    const post = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST');
    expect(JSON.parse(String(post?.[1]?.body))).toEqual({ audioDisposition: 'DOWNLOADED' });
  });

  it('does not confirm an audio download when the refreshed API-010 removes DOWNLOAD_AUDIO', async () => {
    let statusReads = 0;
    let returnedFromDownload = false;
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === 'POST') return new Response('', { status: 202 });
      statusReads += 1;
      const downloadReturned = returnedFromDownload;
      const actions = downloadReturned ? ['FINALIZE_PROCESSING_FAILURE'] : ['DOWNLOAD_AUDIO', 'FINALIZE_PROCESSING_FAILURE'];
      const body = apiSnapshot('PROCESSING_FAILED', actions, 'TRANSCRIPTION', downloadReturned ? 5 : 4);
      if (downloadReturned) body.data.processing.audioAvailable = false;
      return new Response(JSON.stringify(body), { status: 200 });
    });
    vi.stubGlobal('fetch', withHealthyBackend(fetchMock));
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: '오디오 다운로드 준비' }));
    await waitFor(() => expect(document.querySelector('a[download]')).not.toBeNull());
    const link = document.querySelector<HTMLAnchorElement>('a[download]')!;
    fireEvent.click(link);
    const readsBeforeReturn = statusReads;
    returnedFromDownload = true;
    returnFromAudioDownload();
    await waitFor(() => expect(statusReads).toBeGreaterThan(readsBeforeReturn));
    expect(await screen.findByText('오디오를 사용할 수 없거나 보관 기한이 지났습니다. 최신 상태를 확인해 주세요.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: '저장했어요' })).toBeNull();
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(0);
  });

  it('does not offer recovery after the Session disappears', async () => {
    vi.stubGlobal('fetch', withHealthyBackend(vi.fn(async () => new Response('', { status: 404 }))));
    renderPage();
    expect(await screen.findByRole('heading', { name: '회의를 찾을 수 없습니다' })).toBeTruthy();
    expect(screen.getByRole('link', { name: '새 회의 시작' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: '다시 시도' })).toBeNull();
  });
});
