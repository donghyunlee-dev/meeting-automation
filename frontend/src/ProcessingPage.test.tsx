import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router';
import { ProcessingPage } from './ProcessingPage';

afterEach(() => { cleanup(); vi.unstubAllGlobals(); sessionStorage.clear(); });

function apiSnapshot(status: string, actions: string[] = [], stage = 'TRANSCRIPTION', version = 4) {
  return { data: { sessionId: 's-1', version, status, processing: { stage, retryable: true, audioAvailable: true, audioExpiresAt: '2026-10-15T00:00:00Z', errorCode: 'PRIVATE_PROVIDER_FAILURE' }, allowedActions: actions } };
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
  it('shows safe Korean stage copy and only actions allowed by API-010', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify(apiSnapshot('PROCESSING_FAILED', ['DOWNLOAD_AUDIO', 'FINALIZE_PROCESSING_FAILURE'])), { status: 200 })));
    renderPage();
    expect(await screen.findByRole('heading', { name: '처리를 완료하지 못했습니다' })).toBeTruthy();
    expect(screen.getByRole('region').querySelector('p:not(.visually-hidden)')?.textContent).toContain('음성을 변환하는 중 문제가 발생했습니다');
    expect(screen.queryByRole('link', { name: '원본 오디오 다운로드' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '오디오 다운로드 준비' }));
    expect((await screen.findByRole('link', { name: '원본 오디오 다운로드' })).getAttribute('href')).toContain('/audio/download');
    expect(screen.queryByRole('button', { name: '다시 변환' })).toBeNull();
    expect(screen.queryByText(/PRIVATE_PROVIDER_FAILURE/)).toBeNull();
  });

  it('keeps actions hidden when API-010 metadata says retry or assembled audio is unavailable', async () => {
    const body = apiSnapshot('PROCESSING_FAILED', ['RETRY_PROCESSING', 'DOWNLOAD_AUDIO']);
    body.data.processing.retryable = false;
    body.data.processing.audioAvailable = false;
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify(body), { status: 200 })));
    renderPage();
    await screen.findByRole('heading', { name: '처리를 완료하지 못했습니다' });
    expect(screen.queryByRole('button', { name: '다시 변환' })).toBeNull();
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
    vi.stubGlobal('fetch', fetchMock);
    renderPage();
    expect(await screen.findByRole('button', { name: '오디오 다운로드 준비' })).toBeTruthy();
    fireEvent.focus(window);
    await waitFor(() => expect(screen.queryByRole('button', { name: '오디오 다운로드 준비' })).toBeNull());
    expect(statusReads).toBeGreaterThan(1);
  });

  it('submits one retry with current version, disables repeat clicks, and reconciles to PROCESSING', async () => {
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === 'POST') return new Response(JSON.stringify({ data: { sessionId: 's-1', status: 'PROCESSING', stage: 'TRANSCRIPTION' } }), { status: 202 });
      return new Response(JSON.stringify(apiSnapshot(fetchMock.mock.calls.some(([, request]) => request?.method === 'POST') ? 'PROCESSING' : 'PROCESSING_FAILED', ['RETRY_PROCESSING'], 'TRANSCRIPTION', 7)), { status: 200 });
    });
    vi.stubGlobal('fetch', fetchMock);
    renderPage();
    const retry = await screen.findByRole('button', { name: '다시 변환' });
    fireEvent.click(retry);
    await screen.findByText('음성 인식 단계 처리 중입니다.');
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
    vi.stubGlobal('fetch', fetchMock);
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: '다시 변환' }));
    expect(await screen.findByText('음성 인식 단계 처리 중입니다.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: '다시 변환' })).toBeNull();
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1);
  });

  it('requires explicit discard confirmation and finalizes only with DISCARDED', async () => {
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => init?.method === 'POST'
      ? new Response('', { status: 202 })
      : new Response(JSON.stringify(apiSnapshot(fetchMock.mock.calls.some(([, request]) => request?.method === 'POST') ? 'COMPLETED_WITH_WARNINGS' : 'PROCESSING_FAILED', ['FINALIZE_PROCESSING_FAILURE'], 'DIARIZATION', 3)), { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);
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
    vi.stubGlobal('fetch', fetchMock);
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: '오디오 다운로드 준비' }));
    const link = await screen.findByRole('link', { name: '원본 오디오 다운로드' });
    expect(link.getAttribute('href')).toContain('/audio/download');
    expect(link.getAttribute('target')).toBe('_blank');
    expect(link.getAttribute('rel')).toContain('noopener');
    fireEvent.click(link);
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(0);
    fireEvent.focus(window);
    const savedButton = await screen.findByRole('button', { name: '저장했어요' });
    expect(document.activeElement).toBe(savedButton);
    fireEvent.click(savedButton);
    expect(await screen.findByRole('heading', { name: '실패 기록을 저장했습니다' })).toBeTruthy();
    const post = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST');
    expect(JSON.parse(String(post?.[1]?.body))).toEqual({ audioDisposition: 'DOWNLOADED' });
  });

  it('does not confirm an audio download when the refreshed API-010 removes DOWNLOAD_AUDIO', async () => {
    let statusReads = 0;
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === 'POST') return new Response('', { status: 202 });
      statusReads += 1;
      const actions = statusReads <= 2 ? ['DOWNLOAD_AUDIO', 'FINALIZE_PROCESSING_FAILURE'] : ['FINALIZE_PROCESSING_FAILURE'];
      const body = apiSnapshot('PROCESSING_FAILED', actions);
      if (statusReads > 2) body.data.processing.audioAvailable = false;
      return new Response(JSON.stringify(body), { status: 200 });
    });
    vi.stubGlobal('fetch', fetchMock);
    renderPage();
    fireEvent.click((await screen.findAllByRole('button'))[0]);
    await waitFor(() => expect(document.querySelector('a[download]')).not.toBeNull());
    const link = document.querySelector<HTMLAnchorElement>('a[download]')!;
    fireEvent.click(link);
    fireEvent.focus(window);
    await waitFor(() => expect(screen.getAllByRole('alert').length).toBeGreaterThan(1));
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(0);
  });

  it('does not offer recovery after the Session disappears', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status: 404 })));
    renderPage();
    expect(await screen.findByRole('heading', { name: '회의를 찾을 수 없습니다' })).toBeTruthy();
    expect(screen.getByRole('link', { name: '새 회의 시작' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: '다시 변환' })).toBeNull();
  });
});
