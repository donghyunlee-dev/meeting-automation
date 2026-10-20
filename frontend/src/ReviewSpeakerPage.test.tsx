import { withHealthyBackend } from './testUtils/withHealthyBackend';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { useState, type ComponentProps, type ComponentType } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes, useNavigate } from 'react-router';
import { ReviewSpeakerPage } from './ReviewSpeakerPage';

const reviewData = {
  sessionId: 'review-minutes',
  version: 7,
  status: 'REVIEW',
  meeting: { participantIds: ['p1'] },
  speakers: [{ speakerId: 'speaker-a', label: 'Speaker A', participantId: 'p1' }],
  transcript: [
    { segmentId: 'later', speakerId: 'speaker-a', startMs: 65_000, endMs: 67_000, text: '두 번째 발언' },
    { segmentId: 'earlier', speakerId: 'speaker-a', startMs: 1_000, endMs: 2_500, text: '<script>첫 번째 발언</script>' },
  ],
  minutes: {
    templateId: 'weekly',
    templateVersion: '4',
    summary: '현재 요약',
    discussionPoints: ['논의 내용'],
    decisions: ['결정 내용'],
    actionItems: [{ ownerParticipantId: 'p1', task: '문서 초안 작성', dueDate: '2026-10-20' }],
    followUps: ['다음 주 확인'],
  },
  allowedActions: ['UPDATE_SPEAKER_MAPPING', 'UPDATE_MINUTES'],
};

type MinutesSaveState = { sessionId: string; status: 'idle' | 'saving' | 'saved' | 'error'; message?: string };
type ReviewTestProps = ComponentProps<typeof ReviewSpeakerPage> & {
  onMinutesSave?: (sessionId: string, minutes: typeof reviewData.minutes) => void;
  minutesSaveState?: MinutesSaveState;
};
const ReviewPageUnderTest = ReviewSpeakerPage as ComponentType<ReviewTestProps>;
const noopInitialize: ComponentProps<typeof ReviewSpeakerPage>['onInitialize'] = () => undefined;
const noopChange: ComponentProps<typeof ReviewSpeakerPage>['onChange'] = () => undefined;
const noopCommit: ComponentProps<typeof ReviewSpeakerPage>['onCommit'] = () => undefined;

function apiResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
}

function httpResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

function renderReview(fetchMock: ReturnType<typeof vi.fn>, props: Partial<ReviewTestProps> = {}) {
  vi.stubGlobal('fetch', withHealthyBackend(fetchMock));
  const page = (nextProps: Partial<ReviewTestProps>) => (
    <MemoryRouter initialEntries={['/meetings/review-minutes/review']}>
      <Routes>
        <Route path="/meetings/:sessionId/review" element={
          <ReviewPageUnderTest drafts={{}} onInitialize={() => undefined} onChange={() => undefined} onCommit={() => undefined} {...nextProps} />
        } />
      </Routes>
    </MemoryRouter>
  );
  const rendered = render(page(props));
  return { ...rendered, rerenderWithProps: (nextProps: Partial<ReviewTestProps>) => rendered.rerender(page({ ...props, ...nextProps })) };
}

function mockApiFetch(overrides: { config?: unknown; participants?: unknown; review?: unknown; templates?: unknown } = {}) {
  return vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    void init;
    const url = new URL(String(input), 'http://localhost');
    if (url.pathname.endsWith('/meeting-sessions/review-minutes')) return apiResponse(overrides.review ?? { data: reviewData });
    if (url.pathname.endsWith('/templates')) return apiResponse(overrides.templates ?? { data: { items: [
      { id: 'weekly', name: 'Weekly', version: '4' }, { id: 'project', name: 'Project', version: '2' },
    ] } });
    if (url.pathname.endsWith('/app-config')) return apiResponse(overrides.config ?? { data: {
      company: { id: 'company-1', name: 'Example', timezone: 'Asia/Seoul' },
      document: { provider: 'NOTION', configured: true, connectionVersion: 2, rootUrl: 'https://notion.example' },
    } });
    if (url.pathname.endsWith('/participants')) {
      if (overrides.participants instanceof Error) throw overrides.participants;
      return apiResponse(overrides.participants ?? { data: { items: [
        { id: 'p1', name: 'Ada Lovelace', email: 'private@example.com' },
      ] } });
    }
    throw new Error(`Unexpected request: ${url.pathname}`);
  });
}

function minutesIntegrationFetch(options: {
  reviews?: Array<unknown | Error | Response>;
  updateMinutes?: (init?: RequestInit) => Promise<Response> | Response;
  regenerate?: (init?: RequestInit) => Promise<Response> | Response;
  config?: unknown;
} = {}) {
  let reviewIndex = 0;
  return vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input), 'http://localhost');
    if (url.pathname === '/api/v1/meeting-sessions/review-minutes' && init?.method !== 'PUT') {
      const reviews = options.reviews ?? [reviewData];
      const next = reviews[Math.min(reviewIndex++, reviews.length - 1)] ?? reviewData;
      if (next instanceof Error) throw next;
      return next instanceof Response ? next : apiResponse({ data: next });
    }
    if (url.pathname === '/api/v1/meeting-sessions/review-minutes/minutes' && init?.method === 'PUT') {
      return options.updateMinutes ? options.updateMinutes(init) : apiResponse({ data: { version: 8, minutes: reviewData.minutes } });
    }
    if (url.pathname === '/api/v1/meeting-sessions/review-minutes/minutes/regenerate' && init?.method === 'POST') {
      return options.regenerate ? options.regenerate(init) : httpResponse({ data: {
        sessionId: 'review-minutes', status: 'PROCESSING', stage: 'DRAFT_REGENERATION', templateId: 'project',
      } }, 202);
    }
    if (url.pathname === '/api/v1/templates') return apiResponse({ data: { items: [
      { id: 'weekly', name: 'Weekly', version: '4' }, { id: 'project', name: 'Project', version: '2' },
    ] } });
    if (url.pathname.endsWith('/app-config')) return apiResponse(options.config ?? { data: {
      company: { id: 'company-1', name: 'Example', timezone: 'Asia/Seoul' },
      document: { provider: 'NOTION', configured: true, connectionVersion: 2, rootUrl: 'https://notion.example' },
    } });
    if (url.pathname.endsWith('/participants')) return apiResponse({ data: { items: [
      { id: 'p1', name: 'Ada Lovelace', email: 'private@example.com' },
    ] } });
    throw new Error(`Unexpected request: ${url.pathname}`);
  });
}

describe('Review minutes and transcript', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', withHealthyBackend(mockApiFetch()));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('ASYNC-GET-RETRY uses the shared safe error state and retries only the review GET', async () => {
    let fail = true;
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = new URL(String(input), 'http://localhost').pathname;
      if (path.endsWith('/meeting-sessions/review-minutes') && init?.method !== 'PUT') {
        if (fail) throw new Error('private provider diagnostic');
        return apiResponse({ data: reviewData });
      }
      if (path.endsWith('/templates')) return apiResponse({ data: { items: [{ id: 'weekly', name: 'Weekly', version: '4' }] } });
      if (path.endsWith('/app-config')) return apiResponse({ data: { company: { id: 'c1', name: 'Example', timezone: 'UTC' }, document: { provider: 'NOTION', configured: true, connectionVersion: 1 } } });
      if (path.endsWith('/participants')) return apiResponse({ data: { items: [{ id: 'p1', name: 'Ada', email: 'private@example.test' }] } });
      throw new Error(`Unexpected request: ${path}`);
    });
    renderReview(fetchMock);

    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.getByRole('heading', { name: '회의 화자 정보를 불러오지 못했습니다.' })).toBeTruthy();
    expect(document.body.textContent).not.toContain('private provider diagnostic');
    fail = false;
    fireEvent.click(screen.getByRole('button', { name: '회의 정보 다시 불러오기' }));
    expect(await screen.findByText('현재 요약')).toBeTruthy();
    expect(fetchMock.mock.calls.filter(([input, init]) => new URL(String(input), 'http://localhost').pathname.endsWith('/meeting-sessions/review-minutes') && init?.method === 'PUT')).toHaveLength(0);
  });

  it('opens a read-only transcript drawer in chronological order and returns focus after Escape', async () => {
    renderReview(vi.mocked(fetch));

    expect(await screen.findByText('화자 매핑 · 참석자 연결')).toBeTruthy();
    const openButton = await screen.findByRole('button', { name: '전사 원문 보기' });
    openButton.focus();
    fireEvent.click(openButton);

    const drawer = await screen.findByRole('dialog', { name: '전사 원문' });
    const entries = within(drawer).getAllByRole('article');
    expect(entries).toHaveLength(2);
    expect(entries[0].textContent).toContain('00:01');
    expect(entries[0].textContent).toContain('Ada Lovelace');
    expect(entries[0].textContent).toContain('<script>첫 번째 발언</script>');
    expect(entries[1].textContent).toContain('01:05');
    expect(entries[1].textContent).toContain('두 번째 발언');
    expect(within(drawer).queryByRole('textbox')).toBeNull();

    fireEvent.keyDown(drawer, { key: 'Escape' });
    expect(screen.queryByRole('dialog', { name: '전사 원문' })).toBeNull();
    expect(document.activeElement).toBe(openButton);
  });

  it('keeps the save button available while the clean Minutes form makes saving a no-op', async () => {
    const onMinutesSave = vi.fn();
    renderReview(mockApiFetch(), { onMinutesSave });

    const save = await screen.findByRole('button', { name: '회의록 변경사항 저장' });
    const actionArea = save.closest('[role="group"]');
    expect(actionArea).not.toBeNull();
    expect(within(actionArea as HTMLElement).getByRole('button', { name: '문서 저장 및 참석자 알림 발송' })).toBeTruthy();
    expect((save as HTMLButtonElement).disabled).toBe(false);
    fireEvent.change(screen.getByLabelText('회의 요약 내용'), { target: { value: '변경됨' } });
    expect((save as HTMLButtonElement).disabled).toBe(false);
    fireEvent.change(screen.getByLabelText('회의 요약 내용'), { target: { value: '현재 요약' } });
    expect((save as HTMLButtonElement).disabled).toBe(false);
    fireEvent.click(save);
    expect(onMinutesSave).not.toHaveBeenCalled();
  });

  it('keeps review completion available and blocks it with a save message for dirty Minutes', async () => {
    const fetchMock = mockApiFetch({ review: { data: { ...reviewData, allowedActions: ['UPDATE_MINUTES', 'CONFIRM'] } } });
    renderReview(fetchMock);

    const completeButton = await screen.findByRole('button', { name: '문서 저장 및 참석자 알림 발송' });
    expect((completeButton as HTMLButtonElement).disabled).toBe(false);
    fireEvent.change(await screen.findByLabelText('회의 요약 내용'), { target: { value: '저장하지 않은 수정' } });
    expect((completeButton as HTMLButtonElement).disabled).toBe(false);
    fireEvent.click(completeButton);

    const notice = await screen.findByRole('dialog', { name: '확인 필요' });
    expect(within(notice).getByText('변경된 내용이 있으니 저장하세요.')).toBeTruthy();
    expect(within(notice).getByRole('button', { name: '확인' })).toBeTruthy();
    expect(within(notice).getByRole('button', { name: '닫기' })).toBeTruthy();
    expect(fetchMock.mock.calls.some(([input]) => String(input).endsWith('/confirm'))).toBe(false);
    fireEvent.click(within(notice).getByRole('button', { name: '닫기' }));
    expect(screen.queryByRole('dialog', { name: '확인 필요' })).toBeNull();
  });

  it('accepts an empty summary and empty sections as a valid complete Minutes draft', async () => {
    const emptyMinutes = {
      templateId: 'weekly', templateVersion: '4', summary: '기존 요약', discussionPoints: [], decisions: [], actionItems: [], followUps: [],
    };
    const fetchMock = mockApiFetch({ review: { data: { ...reviewData, minutes: emptyMinutes } } });
    const onMinutesSave = vi.fn();
    renderReview(fetchMock, { onMinutesSave });

    const summary = await screen.findByLabelText('회의 요약 내용');
    expect((summary as HTMLTextAreaElement).value).toBe('기존 요약');
    fireEvent.change(summary, { target: { value: '' } });
    fireEvent.click(screen.getByRole('button', { name: '회의록 변경사항 저장' }));
    expect(onMinutesSave).toHaveBeenCalledWith('review-minutes', {
      templateId: 'weekly', templateVersion: '4', summary: '',
      discussionPoints: [], decisions: [], actionItems: [], followUps: [],
    });
  });

  it('preserves the draft when caller state changes to saving and then error', async () => {
    const fetchMock = mockApiFetch();
    const rendered = renderReview(fetchMock, { onMinutesSave: vi.fn(), minutesSaveState: { sessionId: 'review-minutes', status: 'idle' } });

    const summary = await screen.findByLabelText('회의 요약 내용');
    fireEvent.change(summary, { target: { value: '저장 오류 뒤에도 남을 요약' } });
    rendered.rerenderWithProps({ minutesSaveState: { sessionId: 'review-minutes', status: 'saving' } });
    expect((screen.getByLabelText('회의 요약 내용') as HTMLTextAreaElement).value).toBe('저장 오류 뒤에도 남을 요약');
    expect((screen.getByRole('button', { name: '회의록 변경사항 저장' }) as HTMLButtonElement).disabled).toBe(true);
    rendered.rerenderWithProps({ minutesSaveState: { sessionId: 'review-minutes', status: 'error', message: '저장 결과를 확인할 수 없습니다.' } });
    expect((screen.getByLabelText('회의 요약 내용') as HTMLTextAreaElement).value).toBe('저장 오류 뒤에도 남을 요약');
    expect(screen.getByRole('alert').textContent).toContain('저장 결과를 확인할 수 없습니다.');
  });

  it('uses a successful draft as the new baseline and marks later edits dirty', async () => {
    const onMinutesSave = vi.fn();
    const rendered = renderReview(mockApiFetch(), {
      onMinutesSave,
      minutesSaveState: { sessionId: 'review-minutes', status: 'idle' },
    });

    const summary = await screen.findByLabelText('회의 요약 내용');
    fireEvent.change(summary, { target: { value: '첫 번째 수정' } });
    rendered.rerenderWithProps({ minutesSaveState: { sessionId: 'review-minutes', status: 'saved', message: 'Saved' } });
    const save = screen.getByRole('button', { name: '회의록 변경사항 저장' }) as HTMLButtonElement;
    await waitFor(() => expect(save.disabled).toBe(false));
    const editor = within(screen.getByRole('region', { name: '회의록' }));

    fireEvent.change(screen.getByLabelText('회의 요약 내용'), { target: { value: '저장 후 새 수정' } });
    expect(save.disabled).toBe(false);
    expect(editor.queryByText('저장 완료')).toBeNull();
    fireEvent.click(save);
    expect(onMinutesSave).toHaveBeenCalledTimes(1);
  });

  it('shows an empty-state for no transcript and keeps unmapped speakers as their original labels', async () => {
    const emptyReview = { ...reviewData, transcript: [] };
    renderReview(mockApiFetch({ review: { data: emptyReview } }));
    fireEvent.click(await screen.findByRole('button', { name: '전사 원문 보기' }));
    expect(await screen.findByText('표시할 전사 원문이 없습니다.')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '닫기' }));

    cleanup();
    const unmappedReview = { ...reviewData, transcript: [
      { segmentId: 'unknown-speaker', speakerId: 'speaker-unknown', startMs: 0, endMs: 900, text: '알 수 없는 화자 발언' },
    ] };
    renderReview(mockApiFetch({ review: { data: unmappedReview } }));
    fireEvent.click(await screen.findByRole('button', { name: '전사 원문 보기' }));
    const drawer = await screen.findByRole('dialog', { name: '전사 원문' });
    expect(within(drawer).getByText('speaker-unknown')).toBeTruthy();
    expect(within(drawer).getByText('알 수 없는 화자 발언')).toBeTruthy();
  });

  it('closes the transcript drawer by button and backdrop while trapping keyboard focus', async () => {
    renderReview(mockApiFetch());
    const openButton = await screen.findByRole('button', { name: '전사 원문 보기' });
    openButton.focus();
    fireEvent.click(openButton);
    const drawer = await screen.findByRole('dialog', { name: '전사 원문' });
    expect(document.activeElement).toBe(within(drawer).getByRole('button', { name: '닫기' }));
    fireEvent.keyDown(drawer, { key: 'Tab', shiftKey: true });
    expect(document.activeElement).toBe(within(drawer).getByRole('button', { name: '닫기' }));
    fireEvent.click(within(drawer).getByRole('button', { name: '닫기' }));
    expect(document.activeElement).toBe(openButton);

    fireEvent.click(openButton);
    const backdrop = screen.getByTestId('transcript-drawer-backdrop');
    fireEvent.mouseDown(backdrop, { target: backdrop });
    expect(screen.queryByRole('dialog', { name: '전사 원문' })).toBeNull();
    expect(document.activeElement).toBe(openButton);
  });

  it('preserves original and saved Minutes baselines per session across route reuse', async () => {
    const otherReview = { ...reviewData, sessionId: 'review-other', minutes: { ...reviewData.minutes, summary: '다른 회의 요약' } };
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      void init;
      const url = new URL(String(input), 'http://localhost');
      if (url.pathname.endsWith('/meeting-sessions/review-minutes')) return apiResponse({ data: reviewData });
      if (url.pathname.endsWith('/meeting-sessions/review-other')) return apiResponse({ data: otherReview });
      if (url.pathname.endsWith('/app-config')) return apiResponse({ data: {
        company: { id: 'company-1', name: 'Example', timezone: 'Asia/Seoul' },
        document: { provider: 'NOTION', configured: true, connectionVersion: 2, rootUrl: 'https://notion.example' },
      } });
      if (url.pathname.endsWith('/participants')) return apiResponse({ data: { items: [{ id: 'p1', name: 'Ada Lovelace', email: 'private@example.com' }] } });
      throw new Error(`Unexpected request: ${url.pathname}`);
    });
    vi.stubGlobal('fetch', withHealthyBackend(fetchMock));
    function SwitchableReview() {
      const navigate = useNavigate();
      const [minutesSaveState, setMinutesSaveState] = useState<MinutesSaveState>({ sessionId: '', status: 'idle' });
      return <>
        <button type="button" onClick={() => navigate('/meetings/review-other/review')}>다른 회의 열기</button>
        <button type="button" onClick={() => navigate('/meetings/review-minutes/review')}>첫 번째 회의 열기</button>
        <Routes>
          <Route path="/meetings/:sessionId/review" element={<ReviewPageUnderTest drafts={{}} onInitialize={noopInitialize}
            onChange={noopChange} onCommit={noopCommit}
            onMinutesSave={(savedSessionId) => setMinutesSaveState({ sessionId: savedSessionId, status: 'saved', message: '저장 완료' })}
            minutesSaveState={minutesSaveState} />} />
        </Routes>
      </>;
    }
    render(<MemoryRouter initialEntries={['/meetings/review-minutes/review']}><SwitchableReview /></MemoryRouter>);

    const summary = await screen.findByLabelText('회의 요약 내용') as HTMLTextAreaElement;
    fireEvent.change(summary, { target: { value: '첫 번째 회의의 저장된 기준' } });
    fireEvent.click(screen.getByRole('button', { name: '회의록 변경사항 저장' }));
    expect((screen.getByRole('button', { name: '회의록 변경사항 저장' }) as HTMLButtonElement).disabled).toBe(false);

    fireEvent.change(screen.getByLabelText('회의 요약 내용'), { target: { value: '첫 번째 회의의 임시 수정' } });
    expect((screen.getByRole('button', { name: '회의록 변경사항 저장' }) as HTMLButtonElement).disabled).toBe(false);
    fireEvent.click(screen.getByRole('button', { name: '다른 회의 열기' }));
    await screen.findByLabelText('회의 요약 내용');
    expect((screen.getByLabelText('회의 요약 내용') as HTMLTextAreaElement).value).toBe('다른 회의 요약');
    expect((screen.getByRole('button', { name: '회의록 변경사항 저장' }) as HTMLButtonElement).disabled).toBe(false);

    fireEvent.change(screen.getByLabelText('회의 요약 내용'), { target: { value: '두 번째 회의의 임시 수정' } });
    fireEvent.click(screen.getByRole('button', { name: '첫 번째 회의 열기' }));
    await screen.findByLabelText('회의 요약 내용');
    expect((screen.getByLabelText('회의 요약 내용') as HTMLTextAreaElement).value).toBe('첫 번째 회의의 임시 수정');
    expect((screen.getByRole('button', { name: '회의록 변경사항 저장' }) as HTMLButtonElement).disabled).toBe(false);
    fireEvent.click(screen.getByRole('button', { name: '다른 회의 열기' }));
    await screen.findByLabelText('회의 요약 내용');
    expect((screen.getByLabelText('회의 요약 내용') as HTMLTextAreaElement).value).toBe('두 번째 회의의 임시 수정');
    expect((screen.getByRole('button', { name: '회의록 변경사항 저장' }) as HTMLButtonElement).disabled).toBe(false);
  });

  it('saves one complete draft with the current version and reconciles the API-010 snapshot', async () => {
    const submitted = { ...reviewData.minutes, summary: 'API-012에 저장할 전체 회의록' };
    const readAfterWrite = {
      ...reviewData,
      version: 8,
      minutes: submitted,
      speakers: [{ ...reviewData.speakers[0]!, participantId: 'p2', participantName: 'Grace Hopper' }],
      transcript: [{ segmentId: 'after-write', speakerId: 'speaker-a', startMs: 300, endMs: 900, text: 'API-010 authoritative transcript snapshot' }],
      allowedActions: ['UPDATE_SPEAKER_MAPPING'],
    };
    const fetchMock = minutesIntegrationFetch({
      reviews: [reviewData, readAfterWrite],
      updateMinutes: () => httpResponse({ data: { version: 8, minutes: submitted } }),
    });
    renderReview(fetchMock);

    fireEvent.change(await screen.findByLabelText('회의 요약 내용'), { target: { value: submitted.summary } });
    fireEvent.click(screen.getByRole('button', { name: '회의록 변경사항 저장' }));

    await waitFor(() => expect(fetchMock.mock.calls.filter(([input, init]) => String(input).endsWith('/minutes') && init?.method === 'PUT')).toHaveLength(1));
    const putCall = fetchMock.mock.calls.find(([input, init]) => String(input).endsWith('/minutes') && init?.method === 'PUT');
    expect(putCall).toBeTruthy();
    expect(new Headers(putCall?.[1]?.headers).get('If-Match')).toBe('"7"');
    expect(JSON.parse(String(putCall?.[1]?.body))).toEqual(submitted);
    expect(fetchMock.mock.calls.filter(([input]) => new URL(String(input), 'http://localhost').pathname === '/api/v1/meeting-sessions/review-minutes')).toHaveLength(2);
    expect(fetchMock.mock.calls.some(([input, init]) => String(input).endsWith('/speaker-mappings') && init?.method === 'PUT')).toBe(false);
    const savedNotice = await screen.findByRole('dialog', { name: '회의록 저장 완료' });
    expect(within(savedNotice).getByText('회의록이 저장되었습니다.')).toBeTruthy();
    expect((screen.getByLabelText('Speaker A 연결할 참석자') as HTMLSelectElement).value).toBe('p2');
    fireEvent.click(within(savedNotice).getByRole('button', { name: '확인' }));
    expect(screen.queryByRole('dialog', { name: '회의록 저장 완료' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '전사 원문 보기' }));
    const drawer = await screen.findByRole('dialog');
    expect(within(drawer).getByText('API-010 authoritative transcript snapshot')).toBeTruthy();
  });

  it('blocks clean and action-disallowed drafts without calling API-012', async () => {
    const cleanFetch = minutesIntegrationFetch();
    renderReview(cleanFetch);
    const cleanSave = await screen.findByRole('button', { name: '회의록 변경사항 저장' });
    expect((cleanSave as HTMLButtonElement).disabled).toBe(false);
    fireEvent.click(cleanSave);
    expect(cleanFetch.mock.calls.some(([input, init]) => String(input).endsWith('/minutes') && init?.method === 'PUT')).toBe(false);
    cleanup();

    const fetchMock = minutesIntegrationFetch({ reviews: [{ ...reviewData, allowedActions: ['UPDATE_SPEAKER_MAPPING'] }] });
    renderReview(fetchMock);

    const summary = await screen.findByLabelText('회의 요약 내용');
    fireEvent.change(summary, { target: { value: '수정했지만 action이 허용되지 않음' } });
    const editor = within(screen.getByRole('region', { name: '회의록' }));
    const save = editor.getByRole('button', { name: '회의록 변경사항 저장' }) as HTMLButtonElement;
    expect(save.disabled).toBe(true);
    fireEvent.click(save);
    expect(fetchMock.mock.calls.some(([input, init]) => String(input).endsWith('/minutes') && init?.method === 'PUT')).toBe(false);
  });

  it('retains the user draft and shows safe feedback for API-012 validation errors', async () => {
    const fetchMock = minutesIntegrationFetch({
      updateMinutes: () => httpResponse({ error: {
        code: 'VALIDATION_FAILED', message: 'private server error detail',
        details: { fieldErrors: [{ field: 'summary', code: 'INVALID' }] },
      } }, 400),
    });
    renderReview(fetchMock);

    const summary = await screen.findByLabelText('회의 요약 내용') as HTMLTextAreaElement;
    fireEvent.change(summary, { target: { value: '서버 검증 실패 뒤 보존할 내용' } });
    fireEvent.click(screen.getByRole('button', { name: '회의록 변경사항 저장' }));

    const alert = await screen.findByRole('alert');
    expect(summary.value).toBe('서버 검증 실패 뒤 보존할 내용');
    expect(alert.textContent).toContain('저장하지 못했습니다');
    expect(alert.textContent).not.toContain('private server error detail');
    expect((screen.getByRole('button', { name: '회의록 변경사항 저장' }) as HTMLButtonElement).disabled).toBe(false);
  });

  it('refreshes the server base after a 412 and never automatically replays the PUT', async () => {
    const latest = { ...reviewData, version: 8, minutes: { ...reviewData.minutes, summary: '다른 사용자가 저장한 내용' } };
    const fetchMock = minutesIntegrationFetch({
      reviews: [reviewData, latest],
      updateMinutes: () => httpResponse({ error: { code: 'SESSION_VERSION_CONFLICT' } }, 412),
    });
    renderReview(fetchMock);

    const summary = await screen.findByLabelText('회의 요약 내용') as HTMLTextAreaElement;
    fireEvent.change(summary, { target: { value: '내가 보존해야 하는 편집' } });
    fireEvent.click(screen.getByRole('button', { name: '회의록 변경사항 저장' }));

    expect(await screen.findByText(/최신 회의 정보를 불러왔습니다/)).toBeTruthy();
    expect(summary.value).toBe('내가 보존해야 하는 편집');
    expect((screen.getByRole('button', { name: '회의록 변경사항 저장' }) as HTMLButtonElement).disabled).toBe(false);
    expect(fetchMock.mock.calls.filter(([input, init]) => String(input).endsWith('/minutes') && init?.method === 'PUT')).toHaveLength(1);
  });

  it('preserves an uncertain draft and offers a status check when the reconciliation GET also fails', async () => {
    const fetchMock = minutesIntegrationFetch({
      reviews: [reviewData, new Error('private API-010 detail')],
      updateMinutes: async () => { throw new TypeError('private network detail'); },
    });
    renderReview(fetchMock);

    const summary = await screen.findByLabelText('회의 요약 내용') as HTMLTextAreaElement;
    fireEvent.change(summary, { target: { value: '결과 확인 전 유지할 내용' } });
    fireEvent.click(screen.getByRole('button', { name: '회의록 변경사항 저장' }));

    expect(await screen.findByText(/저장 결과를 확인하지 못했습니다/)).toBeTruthy();
    expect(summary.value).toBe('결과 확인 전 유지할 내용');
    fireEvent.click(screen.getByRole('button', { name: '저장 결과 다시 확인' }));
    await waitFor(() => expect(fetchMock.mock.calls.filter(([input]) => new URL(String(input), 'http://localhost').pathname === '/api/v1/meeting-sessions/review-minutes')).toHaveLength(3));
    expect(fetchMock.mock.calls.filter(([input, init]) => String(input).endsWith('/minutes') && init?.method === 'PUT')).toHaveLength(1);
  });

  it('does not let a lower-version read-after-write response replace the mutation result', async () => {
    const saved = { ...reviewData.minutes, summary: 'version 8에 저장된 내용' };
    const fetchMock = minutesIntegrationFetch({
      reviews: [reviewData, reviewData],
      updateMinutes: (() => {
        let saves = 0;
        return () => {
          saves += 1;
          return httpResponse({ data: { version: saves === 1 ? 8 : 9, minutes: saves === 1 ? saved : { ...saved, summary: 'version 9' } } });
        };
      })(),
    });
    renderReview(fetchMock);

    const summary = await screen.findByLabelText('회의 요약 내용') as HTMLTextAreaElement;
    fireEvent.change(summary, { target: { value: saved.summary } });
    fireEvent.click(screen.getByRole('button', { name: '회의록 변경사항 저장' }));
    await waitFor(() => expect(fetchMock.mock.calls.filter(([input, init]) => String(input).endsWith('/minutes') && init?.method === 'PUT')).toHaveLength(1));

    fireEvent.change(summary, { target: { value: 'version 8을 기준으로 한 새 편집' } });
    fireEvent.click(screen.getByRole('button', { name: '회의록 변경사항 저장' }));
    await waitFor(() => expect(fetchMock.mock.calls.filter(([input, init]) => String(input).endsWith('/minutes') && init?.method === 'PUT')).toHaveLength(2));
    const putCalls = fetchMock.mock.calls.filter(([input, init]) => String(input).endsWith('/minutes') && init?.method === 'PUT');
    expect(new Headers(putCalls[1]?.[1]?.headers).get('If-Match')).toBe('"8"');
  });

  it('routes an API-012 SESSION_NOT_FOUND response to the safe new-meeting recovery path', async () => {
    const fetchMock = minutesIntegrationFetch({
      updateMinutes: () => httpResponse({ error: { code: 'SESSION_NOT_FOUND', message: 'internal diagnostic' } }, 404),
    });
    renderReview(fetchMock);

    await waitFor(() => expect(document.querySelector('#minutes-summary')).not.toBeNull());
    const summary = document.querySelector('#minutes-summary') as HTMLTextAreaElement;
    fireEvent.change(summary, { target: { value: 'Draft submitted for a missing session' } });
    fireEvent.click(document.querySelector('.review-minutes-save-controls button')!);

    await waitFor(() => expect(document.querySelector('a[href="/meetings/new"]')).not.toBeNull());
    expect(screen.getByRole('alert')).not.toBeNull();
    expect(document.querySelector('#minutes-summary')).toBeNull();
    const putCall = fetchMock.mock.calls.find(([input, init]) => String(input).endsWith('/minutes') && init?.method === 'PUT');
    expect(JSON.parse(String(putCall?.[1]?.body)).summary).toBe('Draft submitted for a missing session');
  });
  it('retains the full draft when API-012 reports a missing action-item owner', async () => {
    const fetchMock = minutesIntegrationFetch({
      updateMinutes: () => httpResponse({ error: { code: 'PARTICIPANT_NOT_FOUND' } }, 404),
    });
    renderReview(fetchMock);

    await waitFor(() => expect(document.querySelector('#minutes-summary')).not.toBeNull());
    const summary = document.querySelector('#minutes-summary') as HTMLTextAreaElement;
    fireEvent.change(summary, { target: { value: 'Keep this draft for recovery' } });
    fireEvent.click(document.querySelector('.review-minutes-save-controls button')!);

    await waitFor(() => expect(screen.getByRole('alert')).not.toBeNull());
    expect((document.querySelector('#minutes-summary') as HTMLTextAreaElement).value).toBe('Keep this draft for recovery');
    const putCall = fetchMock.mock.calls.find(([input, init]) => String(input).endsWith('/minutes') && init?.method === 'PUT');
    expect(JSON.parse(String(putCall?.[1]?.body)).summary).toBe('Keep this draft for recovery');
  });
  it('routes an API-010 missing session to the safe new-meeting recovery path', async () => {
    const fetchMock = minutesIntegrationFetch({ reviews: [httpResponse({ error: { code: 'SESSION_NOT_FOUND' } }, 404)] });
    renderReview(fetchMock);

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('회의를 찾을 수 없습니다');
    expect(within(alert).getByRole('link', { name: '새 회의 시작' }).getAttribute('href')).toBe('/meetings/new');
    expect(screen.queryByLabelText('회의 요약')).toBeNull();
  });

  it('resumes observing DRAFT_REGENERATION after returning to Review without rendering partial Minutes', async () => {
    let statusReads = 0;
    const terminal = {
      ...reviewData, version: 8, minutes: { ...reviewData.minutes, templateId: 'project', templateVersion: '2', summary: 'Returned after completion' },
      lastOperation: { type: 'MINUTES_REGENERATION', outcome: 'SUCCEEDED', requestedTemplateId: 'project' },
    };
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      void init;
      const url = new URL(String(input), 'http://localhost');
      if (url.pathname === '/api/v1/meeting-sessions/review-minutes') {
        statusReads += 1;
        if (statusReads < 3) return apiResponse({ data: {
          sessionId: 'review-minutes', version: 7, status: 'PROCESSING',
          processing: { stage: 'DRAFT_REGENERATION', progressPercent: 45 }, allowedActions: [],
        } });
        return apiResponse({ data: terminal });
      }
      if (url.pathname === '/api/v1/app-config') return apiResponse({ data: {
        company: { id: 'company-1', name: 'Example', timezone: 'Asia/Seoul' },
        document: { provider: 'NOTION', configured: true, connectionVersion: 2, rootUrl: 'https://notion.example' },
      } });
      if (url.pathname === '/api/v1/participants') return apiResponse({ data: { items: [{ id: 'p1', name: 'Ada Lovelace', email: 'private@example.com' }] } });
      throw new Error(`Unexpected request: ${url.pathname}`);
    });
    renderReview(fetchMock);

    expect(await screen.findByLabelText('회의 요약 내용')).toHaveProperty('value', 'Returned after completion');
    expect(statusReads).toBe(3);
    expect(fetchMock.mock.calls.some(([input, init]) => String(input).endsWith('/minutes/regenerate') || init?.method === 'PUT')).toBe(false);
  });

});
