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
  vi.stubGlobal('fetch', fetchMock);
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
    const url = new URL(String(input));
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
    const url = new URL(String(input));
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
    vi.stubGlobal('fetch', mockApiFetch());
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('ASYNC-GET-RETRY uses the shared safe error state and retries only the review GET', async () => {
    let fail = true;
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = new URL(String(input)).pathname;
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
    expect(fetchMock.mock.calls.filter(([input, init]) => new URL(String(input)).pathname.endsWith('/meeting-sessions/review-minutes') && init?.method === 'PUT')).toHaveLength(0);
  });

  it('keeps the Share action disabled while a speaker mapping draft is unsaved', async () => {
    renderReview(mockApiFetch(), { drafts: { 'review-minutes': { 'speaker-a': null } } });

    const shareButton = await screen.findByRole('button', { name: '저장하고 공유' });

    expect((shareButton as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText('화자 매핑 또는 회의록을 저장한 뒤 공유하세요.')).toBeTruthy();
  });

  it('keeps the Share action disabled while a Minutes edit is unsaved', async () => {
    renderReview(mockApiFetch());
    fireEvent.change(await screen.findByLabelText('회의 요약'), { target: { value: 'Unsaved draft' } });

    expect((screen.getByRole('button', { name: '저장하고 공유' }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText('화자 매핑 또는 회의록을 저장한 뒤 공유하세요.')).toBeTruthy();
  });

  it('keeps the Share action disabled while a Minutes save is in flight', async () => {
    renderReview(mockApiFetch(), { minutesSaveState: { sessionId: 'review-minutes', status: 'saving', message: 'Saving edits' } });

    const shareButton = await screen.findByRole('button', { name: '저장하고 공유' });

    expect((shareButton as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText('저장 중인 변경사항이 있습니다. 저장이 끝날 때까지 기다려 주세요.')).toBeTruthy();
  });

  it('loads every Structured Minutes field into the Review editor without exposing participant email', async () => {
    renderReview(vi.mocked(fetch));

    expect((await screen.findByLabelText('회의 요약') as HTMLTextAreaElement).value).toBe('현재 요약');
    expect((screen.getByLabelText('논의 내용 1') as HTMLTextAreaElement).value).toBe('논의 내용');
    expect((screen.getByLabelText('결정 사항 1') as HTMLTextAreaElement).value).toBe('결정 내용');
    expect((screen.getByLabelText('할 일 1') as HTMLTextAreaElement).value).toBe('문서 초안 작성');
    expect((screen.getByLabelText('담당자 1') as HTMLSelectElement).value).toBe('p1');
    expect((screen.getByLabelText('기한 1') as HTMLInputElement).value).toBe('2026-10-20');
    expect((screen.getByLabelText('후속 조치 1') as HTMLTextAreaElement).value).toBe('다음 주 확인');
    expect(screen.queryByText('private@example.com')).toBeNull();
  });

  it('opens a read-only transcript drawer in chronological order and returns focus after Escape', async () => {
    renderReview(vi.mocked(fetch));

    const openButton = await screen.findByRole('button', { name: 'Transcript 원문 보기' });
    openButton.focus();
    fireEvent.click(openButton);

    const drawer = await screen.findByRole('dialog', { name: 'Transcript 원문' });
    const entries = within(drawer).getAllByRole('article');
    expect(entries).toHaveLength(2);
    expect(entries[0].textContent).toContain('00:01');
    expect(entries[0].textContent).toContain('Ada Lovelace');
    expect(entries[0].textContent).toContain('<script>첫 번째 발언</script>');
    expect(entries[1].textContent).toContain('01:05');
    expect(entries[1].textContent).toContain('두 번째 발언');
    expect(within(drawer).queryByRole('textbox')).toBeNull();

    fireEvent.keyDown(drawer, { key: 'Escape' });
    expect(screen.queryByRole('dialog', { name: 'Transcript 원문' })).toBeNull();
    expect(document.activeElement).toBe(openButton);
  });

  it('sends the exact full edited Minutes draft while preserving template metadata and speaker mapping', async () => {
    const fetchMock = mockApiFetch();
    const onMinutesSave = vi.fn();
    renderReview(fetchMock, { onMinutesSave });

    fireEvent.change(await screen.findByLabelText('회의 요약'), { target: { value: '수정된 요약' } });
    fireEvent.change(screen.getByLabelText('논의 내용 1'), { target: { value: '  새 논의  ' } });
    fireEvent.change(screen.getByLabelText('담당자 1'), { target: { value: '' } });
    fireEvent.change(screen.getByLabelText('기한 1'), { target: { value: '' } });
    fireEvent.click(screen.getByRole('button', { name: '후속 조치 추가' }));
    fireEvent.change(screen.getByLabelText('후속 조치 2'), { target: { value: '추가 확인' } });
    fireEvent.click(screen.getByRole('button', { name: '회의록 저장' }));

    expect(onMinutesSave).toHaveBeenCalledOnce();
    expect(onMinutesSave).toHaveBeenCalledWith('review-minutes', {
      templateId: 'weekly', templateVersion: '4', summary: '수정된 요약',
      discussionPoints: ['  새 논의  '], decisions: ['결정 내용'],
      actionItems: [{ ownerParticipantId: null, task: '문서 초안 작성', dueDate: null }],
      followUps: ['다음 주 확인', '추가 확인'],
    });
    expect(fetchMock.mock.calls.some(([input]) => String(input).includes('/minutes'))).toBe(false);
    expect(fetchMock.mock.calls.some(([input, init]) => String(input).includes('/speaker-mappings') && init?.method === 'PUT')).toBe(false);
  });

  it('keeps a clean Minutes form unsaveable until an editable field changes', async () => {
    const onMinutesSave = vi.fn();
    renderReview(mockApiFetch(), { onMinutesSave });

    const save = await screen.findByRole('button', { name: '회의록 저장' });
    expect((save as HTMLButtonElement).disabled).toBe(true);
    fireEvent.change(screen.getByLabelText('회의 요약'), { target: { value: '변경됨' } });
    expect((save as HTMLButtonElement).disabled).toBe(false);
    fireEvent.change(screen.getByLabelText('회의 요약'), { target: { value: '현재 요약' } });
    expect((save as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(save);
    expect(onMinutesSave).not.toHaveBeenCalled();
  });

  it('accepts an empty summary and empty sections as a valid complete Minutes draft', async () => {
    const emptyMinutes = {
      templateId: 'weekly', templateVersion: '4', summary: '기존 요약', discussionPoints: [], decisions: [], actionItems: [], followUps: [],
    };
    const fetchMock = mockApiFetch({ review: { data: { ...reviewData, minutes: emptyMinutes } } });
    const onMinutesSave = vi.fn();
    renderReview(fetchMock, { onMinutesSave });

    const summary = await screen.findByLabelText('회의 요약');
    expect((summary as HTMLTextAreaElement).value).toBe('기존 요약');
    fireEvent.change(summary, { target: { value: '' } });
    expect((screen.getByRole('button', { name: '회의록 저장' }) as HTMLButtonElement).disabled).toBe(false);
    fireEvent.click(screen.getByRole('button', { name: '회의록 저장' }));
    expect(onMinutesSave).toHaveBeenCalledWith('review-minutes', {
      templateId: 'weekly', templateVersion: '4', summary: '',
      discussionPoints: [], decisions: [], actionItems: [], followUps: [],
    });
  });

  it('flags an invalid calendar date and prevents saving even after another field changes', async () => {
    const malformedMinutes = {
      ...reviewData.minutes,
      actionItems: [{ ...reviewData.minutes.actionItems[0], dueDate: '2026-02-30' }],
    };
    const onMinutesSave = vi.fn();
    renderReview(mockApiFetch({ review: { data: { ...reviewData, minutes: malformedMinutes } } }), { onMinutesSave });

    const summary = await screen.findByLabelText('회의 요약');
    const dueDate = screen.getByLabelText('기한 1') as HTMLInputElement;
    expect(dueDate.value).toBe('');
    expect(dueDate.getAttribute('aria-invalid')).toBe('true');
    expect(screen.getByText('날짜를 확인해 주세요.')).toBeTruthy();
    fireEvent.change(summary, { target: { value: '다른 항목을 수정한 요약' } });
    const save = screen.getByRole('button', { name: '회의록 저장' }) as HTMLButtonElement;
    expect(save.disabled).toBe(true);
    fireEvent.click(save);
    expect(onMinutesSave).not.toHaveBeenCalled();
  });

  it('adds and removes list and action rows, and blocks whitespace-only item values', async () => {
    const onMinutesSave = vi.fn();
    renderReview(mockApiFetch(), { onMinutesSave });

    await screen.findByLabelText('회의 요약');
    fireEvent.click(screen.getByRole('button', { name: '논의 내용 추가' }));
    const addedPoint = screen.getByLabelText('논의 내용 2');
    expect((addedPoint as HTMLTextAreaElement).value).toBe('');
    expect(addedPoint.getAttribute('aria-invalid')).toBe('true');
    expect((screen.getByRole('button', { name: '회의록 저장' }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.change(addedPoint, { target: { value: '   ' } });
    expect(addedPoint.getAttribute('aria-invalid')).toBe('true');
    fireEvent.change(addedPoint, { target: { value: '새 논의' } });
    expect(addedPoint.getAttribute('aria-invalid')).toBe('false');
    fireEvent.click(screen.getByRole('button', { name: '논의 내용 2 삭제' }));
    expect(screen.queryByLabelText('논의 내용 2')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Action Item 추가' }));
    expect((screen.getByLabelText('할 일 2') as HTMLTextAreaElement).value).toBe('');
    expect(screen.getByLabelText('할 일 2').getAttribute('aria-invalid')).toBe('true');
    fireEvent.click(screen.getByRole('button', { name: 'Action Item 2 삭제' }));
    expect(screen.queryByLabelText('할 일 2')).toBeNull();
  });

  it('preserves the draft when caller state changes to saving and then error', async () => {
    const fetchMock = mockApiFetch();
    const rendered = renderReview(fetchMock, { onMinutesSave: vi.fn(), minutesSaveState: { sessionId: 'review-minutes', status: 'idle' } });

    const summary = await screen.findByLabelText('회의 요약');
    fireEvent.change(summary, { target: { value: '저장 오류 뒤에도 남을 요약' } });
    rendered.rerenderWithProps({ minutesSaveState: { sessionId: 'review-minutes', status: 'saving' } });
    expect((screen.getByLabelText('회의 요약') as HTMLTextAreaElement).value).toBe('저장 오류 뒤에도 남을 요약');
    expect((screen.getByRole('button', { name: '저장 중…' }) as HTMLButtonElement).disabled).toBe(true);
    rendered.rerenderWithProps({ minutesSaveState: { sessionId: 'review-minutes', status: 'error', message: '저장 결과를 확인할 수 없습니다.' } });
    expect((screen.getByLabelText('회의 요약') as HTMLTextAreaElement).value).toBe('저장 오류 뒤에도 남을 요약');
    expect(screen.getByRole('alert').textContent).toContain('저장 결과를 확인할 수 없습니다.');
    expect((screen.getByRole('button', { name: '회의록 저장' }) as HTMLButtonElement).disabled).toBe(false);
  });

  it('uses a successful draft as the new baseline and marks later edits dirty', async () => {
    const onMinutesSave = vi.fn();
    const rendered = renderReview(mockApiFetch(), {
      onMinutesSave,
      minutesSaveState: { sessionId: 'review-minutes', status: 'idle' },
    });

    const summary = await screen.findByLabelText('회의 요약');
    fireEvent.change(summary, { target: { value: '첫 번째 수정' } });
    rendered.rerenderWithProps({ minutesSaveState: { sessionId: 'review-minutes', status: 'saved', message: 'Saved' } });
    const save = screen.getByRole('button', { name: '회의록 저장' }) as HTMLButtonElement;
    await waitFor(() => expect(save.disabled).toBe(true));
    const editor = within(screen.getByRole('region', { name: '회의록 편집' }));

    fireEvent.change(screen.getByLabelText('회의 요약'), { target: { value: '저장 후 새 수정' } });
    expect(save.disabled).toBe(false);
    expect(editor.queryByText('저장 완료')).toBeNull();
    fireEvent.click(save);
    expect(onMinutesSave).toHaveBeenCalledTimes(1);
  });

  it.each([
    ['provider가 선택되지 않음', { provider: null, configured: false }],
    ['provider가 설정되지 않음', { provider: 'NOTION', configured: false }],
  ])('keeps Minutes and existing owner when %s', async (_caseName, document) => {
    const fetchMock = mockApiFetch({ config: { data: {
      company: { id: 'company-1', name: 'Example', timezone: 'Asia/Seoul' },
      document: { ...document, connectionVersion: 2, rootUrl: '' },
    } } });
    renderReview(fetchMock, { onMinutesSave: vi.fn() });

    const summary = await screen.findByLabelText('회의 요약');
    fireEvent.change(summary, { target: { value: 'provider 없어도 계속 편집' } });
    expect((summary as HTMLTextAreaElement).value).toBe('provider 없어도 계속 편집');
    expect((screen.getByLabelText('담당자 1') as HTMLSelectElement).value).toBe('p1');
    expect(within(screen.getByRole('region', { name: '회의록 편집' })).getByRole('link', { name: '문서 설정 열기' }).getAttribute('href')).toBe('/settings');
    expect(screen.queryByText('private@example.com')).toBeNull();
  });

  it('shows an empty-state for no transcript and keeps unmapped speakers as their original labels', async () => {
    const emptyReview = { ...reviewData, transcript: [] };
    renderReview(mockApiFetch({ review: { data: emptyReview } }));
    fireEvent.click(await screen.findByRole('button', { name: 'Transcript 원문 보기' }));
    expect(await screen.findByText('표시할 Transcript가 없습니다.')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '닫기' }));

    cleanup();
    const unmappedReview = { ...reviewData, transcript: [
      { segmentId: 'unknown-speaker', speakerId: 'speaker-unknown', startMs: 0, endMs: 900, text: '알 수 없는 화자 발언' },
    ] };
    renderReview(mockApiFetch({ review: { data: unmappedReview } }));
    fireEvent.click(await screen.findByRole('button', { name: 'Transcript 원문 보기' }));
    const drawer = await screen.findByRole('dialog', { name: 'Transcript 원문' });
    expect(within(drawer).getByText('speaker-unknown')).toBeTruthy();
    expect(within(drawer).getByText('알 수 없는 화자 발언')).toBeTruthy();
  });

  it('closes the transcript drawer by button and backdrop while trapping keyboard focus', async () => {
    renderReview(mockApiFetch());
    const openButton = await screen.findByRole('button', { name: 'Transcript 원문 보기' });
    openButton.focus();
    fireEvent.click(openButton);
    const drawer = await screen.findByRole('dialog', { name: 'Transcript 원문' });
    expect(document.activeElement).toBe(within(drawer).getByRole('button', { name: '닫기' }));
    fireEvent.keyDown(drawer, { key: 'Tab', shiftKey: true });
    expect(document.activeElement).toBe(within(drawer).getByRole('button', { name: '닫기' }));
    fireEvent.click(within(drawer).getByRole('button', { name: '닫기' }));
    expect(document.activeElement).toBe(openButton);

    fireEvent.click(openButton);
    const backdrop = screen.getByTestId('transcript-drawer-backdrop');
    fireEvent.mouseDown(backdrop, { target: backdrop });
    expect(screen.queryByRole('dialog', { name: 'Transcript 원문' })).toBeNull();
    expect(document.activeElement).toBe(openButton);
  });

  it('preserves original and saved Minutes baselines per session across route reuse', async () => {
    const otherReview = { ...reviewData, sessionId: 'review-other', minutes: { ...reviewData.minutes, summary: '다른 회의 요약' } };
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      void init;
      const url = new URL(String(input));
      if (url.pathname.endsWith('/meeting-sessions/review-minutes')) return apiResponse({ data: reviewData });
      if (url.pathname.endsWith('/meeting-sessions/review-other')) return apiResponse({ data: otherReview });
      if (url.pathname.endsWith('/app-config')) return apiResponse({ data: {
        company: { id: 'company-1', name: 'Example', timezone: 'Asia/Seoul' },
        document: { provider: 'NOTION', configured: true, connectionVersion: 2, rootUrl: 'https://notion.example' },
      } });
      if (url.pathname.endsWith('/participants')) return apiResponse({ data: { items: [{ id: 'p1', name: 'Ada Lovelace', email: 'private@example.com' }] } });
      throw new Error(`Unexpected request: ${url.pathname}`);
    });
    vi.stubGlobal('fetch', fetchMock);
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

    const summary = await screen.findByLabelText('회의 요약') as HTMLTextAreaElement;
    fireEvent.change(summary, { target: { value: '첫 번째 회의의 저장된 기준' } });
    fireEvent.click(screen.getByRole('button', { name: '회의록 저장' }));
    expect(await screen.findByText('저장 완료')).toBeTruthy();
    expect((screen.getByRole('button', { name: '회의록 저장' }) as HTMLButtonElement).disabled).toBe(true);

    fireEvent.change(screen.getByLabelText('회의 요약'), { target: { value: '첫 번째 회의의 임시 수정' } });
    expect((screen.getByRole('button', { name: '회의록 저장' }) as HTMLButtonElement).disabled).toBe(false);
    fireEvent.click(screen.getByRole('button', { name: '다른 회의 열기' }));
    await screen.findByLabelText('회의 요약');
    expect((screen.getByLabelText('회의 요약') as HTMLTextAreaElement).value).toBe('다른 회의 요약');
    expect((screen.getByRole('button', { name: '회의록 저장' }) as HTMLButtonElement).disabled).toBe(true);

    fireEvent.change(screen.getByLabelText('회의 요약'), { target: { value: '두 번째 회의의 임시 수정' } });
    fireEvent.click(screen.getByRole('button', { name: '첫 번째 회의 열기' }));
    await screen.findByLabelText('회의 요약');
    expect((screen.getByLabelText('회의 요약') as HTMLTextAreaElement).value).toBe('첫 번째 회의의 임시 수정');
    expect((screen.getByRole('button', { name: '회의록 저장' }) as HTMLButtonElement).disabled).toBe(false);
    fireEvent.click(screen.getByRole('button', { name: '다른 회의 열기' }));
    await screen.findByLabelText('회의 요약');
    expect((screen.getByLabelText('회의 요약') as HTMLTextAreaElement).value).toBe('두 번째 회의의 임시 수정');
    expect((screen.getByRole('button', { name: '회의록 저장' }) as HTMLButtonElement).disabled).toBe(false);
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

    fireEvent.change(await screen.findByLabelText('회의 요약'), { target: { value: submitted.summary } });
    fireEvent.click(screen.getByRole('button', { name: '회의록 저장' }));

    expect(await screen.findByText('저장 완료')).toBeTruthy();
    const putCall = fetchMock.mock.calls.find(([input, init]) => String(input).endsWith('/minutes') && init?.method === 'PUT');
    expect(putCall).toBeTruthy();
    expect(new Headers(putCall?.[1]?.headers).get('If-Match')).toBe('"7"');
    expect(JSON.parse(String(putCall?.[1]?.body))).toEqual(submitted);
    expect(fetchMock.mock.calls.filter(([input]) => new URL(String(input)).pathname === '/api/v1/meeting-sessions/review-minutes')).toHaveLength(2);
    expect(fetchMock.mock.calls.some(([input, init]) => String(input).endsWith('/speaker-mappings') && init?.method === 'PUT')).toBe(false);
    expect((screen.getByRole('button', { name: '회의록 저장' }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByLabelText('Speaker A 참가자') as HTMLSelectElement).value).toBe('p2');
    expect(within(screen.getByRole('region', { name: '회의록 편집' })).getByText('최신 Review 정보를 확인한 뒤 다시 시도해 주세요.')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Transcript 원문 보기' }));
    const drawer = await screen.findByRole('dialog');
    expect(within(drawer).getByText('API-010 authoritative transcript snapshot')).toBeTruthy();
  });

  it('keeps edits made during a single in-flight save as a new dirty draft', async () => {
    let finishUpdate!: (response: Response) => void;
    const updateResponse = new Promise<Response>((resolve) => { finishUpdate = resolve; });
    const submitted = { ...reviewData.minutes, summary: '제출한 회의록' };
    const fetchMock = minutesIntegrationFetch({
      reviews: [reviewData, { ...reviewData, version: 8, minutes: submitted }],
      updateMinutes: () => updateResponse,
    });
    renderReview(fetchMock);

    const summary = await screen.findByLabelText('회의 요약') as HTMLTextAreaElement;
    fireEvent.change(summary, { target: { value: submitted.summary } });
    fireEvent.click(screen.getByRole('button', { name: '회의록 저장' }));
    await waitFor(() => expect(fetchMock.mock.calls.filter(([input, init]) => String(input).endsWith('/minutes') && init?.method === 'PUT')).toHaveLength(1));
    expect(summary.disabled).toBe(false);
    fireEvent.change(summary, { target: { value: '요청 중 추가로 수정한 회의록' } });
    expect((screen.getByRole('button', { name: '저장 중…' }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: '저장 중…' }));
    expect(fetchMock.mock.calls.filter(([input, init]) => String(input).endsWith('/minutes') && init?.method === 'PUT')).toHaveLength(1);

    finishUpdate(httpResponse({ data: { version: 8, minutes: submitted } }));

    await waitFor(() => expect(summary.value).toBe('요청 중 추가로 수정한 회의록'));
    expect((screen.getByRole('button', { name: '회의록 저장' }) as HTMLButtonElement).disabled).toBe(false);
    expect(screen.queryByText('저장 완료')).toBeNull();
  });

  it('blocks clean and action-disallowed drafts without calling API-012', async () => {
    const cleanFetch = minutesIntegrationFetch();
    renderReview(cleanFetch);
    const cleanSave = await screen.findByRole('button', { name: '회의록 저장' });
    expect((cleanSave as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(cleanSave);
    expect(cleanFetch.mock.calls.some(([input, init]) => String(input).endsWith('/minutes') && init?.method === 'PUT')).toBe(false);
    cleanup();

    const fetchMock = minutesIntegrationFetch({ reviews: [{ ...reviewData, allowedActions: ['UPDATE_SPEAKER_MAPPING'] }] });
    renderReview(fetchMock);

    const summary = await screen.findByLabelText('회의 요약');
    fireEvent.change(summary, { target: { value: '수정했지만 action이 허용되지 않음' } });
    const editor = within(screen.getByRole('region', { name: '회의록 편집' }));
    const save = editor.getByRole('button', { name: '회의록 저장' }) as HTMLButtonElement;
    expect(save.disabled).toBe(true);
    expect(editor.getByText('최신 Review 정보를 확인한 뒤 다시 시도해 주세요.')).toBeTruthy();
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

    const summary = await screen.findByLabelText('회의 요약') as HTMLTextAreaElement;
    fireEvent.change(summary, { target: { value: '서버 검증 실패 뒤 보존할 내용' } });
    fireEvent.click(screen.getByRole('button', { name: '회의록 저장' }));

    const alert = await screen.findByRole('alert');
    expect(summary.value).toBe('서버 검증 실패 뒤 보존할 내용');
    expect(alert.textContent).toContain('저장하지 못했습니다');
    expect(alert.textContent).not.toContain('private server error detail');
    expect((screen.getByRole('button', { name: '회의록 저장' }) as HTMLButtonElement).disabled).toBe(false);
  });

  it('refreshes the server base after a 412 and never automatically replays the PUT', async () => {
    const latest = { ...reviewData, version: 8, minutes: { ...reviewData.minutes, summary: '다른 사용자가 저장한 내용' } };
    const fetchMock = minutesIntegrationFetch({
      reviews: [reviewData, latest],
      updateMinutes: () => httpResponse({ error: { code: 'SESSION_VERSION_CONFLICT' } }, 412),
    });
    renderReview(fetchMock);

    const summary = await screen.findByLabelText('회의 요약') as HTMLTextAreaElement;
    fireEvent.change(summary, { target: { value: '내가 보존해야 하는 편집' } });
    fireEvent.click(screen.getByRole('button', { name: '회의록 저장' }));

    expect(await screen.findByText(/최신 회의 정보를 불러왔습니다/)).toBeTruthy();
    expect(summary.value).toBe('내가 보존해야 하는 편집');
    expect((screen.getByRole('button', { name: '회의록 저장' }) as HTMLButtonElement).disabled).toBe(false);
    expect(fetchMock.mock.calls.filter(([input, init]) => String(input).endsWith('/minutes') && init?.method === 'PUT')).toHaveLength(1);
  });

  it('reconciles a lost response as saved when API-010 contains the submitted snapshot', async () => {
    const submitted = { ...reviewData.minutes, summary: '응답은 유실됐지만 서버에 반영된 내용' };
    const fetchMock = minutesIntegrationFetch({
      reviews: [reviewData, { ...reviewData, version: 8, minutes: submitted }],
      updateMinutes: async () => { throw new TypeError('private network detail'); },
    });
    renderReview(fetchMock);

    fireEvent.change(await screen.findByLabelText('회의 요약'), { target: { value: submitted.summary } });
    fireEvent.click(screen.getByRole('button', { name: '회의록 저장' }));

    expect(await screen.findByText(/저장 결과가 확인되었습니다/)).toBeTruthy();
    expect((screen.getByRole('button', { name: '회의록 저장' }) as HTMLButtonElement).disabled).toBe(true);
    expect(fetchMock.mock.calls.filter(([input, init]) => String(input).endsWith('/minutes') && init?.method === 'PUT')).toHaveLength(1);
  });

  it('does not promote the partial API-012 response when the API-010 read-after-write fails', async () => {
    const submitted = { ...reviewData.minutes, summary: 'API-012 partial response only' };
    const onInitialize = vi.fn();
    const fetchMock = minutesIntegrationFetch({
      reviews: [reviewData, new Error('API-010 temporarily unavailable')],
      updateMinutes: () => httpResponse({ data: { version: 8, minutes: submitted } }),
    });
    renderReview(fetchMock, { onInitialize });

    fireEvent.change(await screen.findByLabelText('회의 요약'), { target: { value: submitted.summary } });
    fireEvent.click(screen.getByRole('button', { name: '회의록 저장' }));

    expect(await screen.findByText(/최신 회의 정보를 확인하지 못했습니다/)).toBeTruthy();
    expect((screen.getByLabelText('회의 요약') as HTMLTextAreaElement).value).toBe(submitted.summary);
    expect((screen.getByRole('button', { name: '회의록 저장' }) as HTMLButtonElement).disabled).toBe(true);
    expect(onInitialize).toHaveBeenCalledOnce();
    expect(onInitialize).toHaveBeenCalledWith('review-minutes', reviewData.speakers);
    expect(screen.getByLabelText('Speaker A 참가자')).toBeTruthy();
    expect(screen.getByText('두 번째 발언')).toBeTruthy();
  });

  it('preserves an uncertain draft and offers a status check when the reconciliation GET also fails', async () => {
    const fetchMock = minutesIntegrationFetch({
      reviews: [reviewData, new Error('private API-010 detail')],
      updateMinutes: async () => { throw new TypeError('private network detail'); },
    });
    renderReview(fetchMock);

    const summary = await screen.findByLabelText('회의 요약') as HTMLTextAreaElement;
    fireEvent.change(summary, { target: { value: '결과 확인 전 유지할 내용' } });
    fireEvent.click(screen.getByRole('button', { name: '회의록 저장' }));

    expect(await screen.findByText(/저장 결과를 확인하지 못했습니다/)).toBeTruthy();
    expect(summary.value).toBe('결과 확인 전 유지할 내용');
    fireEvent.click(screen.getByRole('button', { name: '저장 결과 다시 확인' }));
    await waitFor(() => expect(fetchMock.mock.calls.filter(([input]) => new URL(String(input)).pathname === '/api/v1/meeting-sessions/review-minutes')).toHaveLength(3));
    expect(fetchMock.mock.calls.filter(([input, init]) => String(input).endsWith('/minutes') && init?.method === 'PUT')).toHaveLength(1);
  });

  it('allows Minutes saving without a document provider and preserves an existing owner', async () => {
    const submitted = { ...reviewData.minutes, summary: 'provider 없이 저장' };
    let sentBody: unknown;
    const fetchMock = minutesIntegrationFetch({
      config: { data: { company: { id: 'company-1', name: 'Example', timezone: 'Asia/Seoul' }, document: { provider: null, configured: false, connectionVersion: 2, rootUrl: '' } } },
      updateMinutes: (init) => {
        sentBody = JSON.parse(String(init?.body));
        return httpResponse({ data: { version: 8, minutes: submitted } });
      },
    });
    renderReview(fetchMock);

    fireEvent.change(await screen.findByLabelText('회의 요약'), { target: { value: submitted.summary } });
    expect((screen.getByLabelText('담당자 1') as HTMLSelectElement).value).toBe('p1');
    fireEvent.click(screen.getByRole('button', { name: '회의록 저장' }));

    expect(await screen.findByText('저장 완료')).toBeTruthy();
    expect(sentBody).toEqual(submitted);
    expect(screen.queryByText('private@example.com')).toBeNull();
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

    const summary = await screen.findByLabelText('회의 요약') as HTMLTextAreaElement;
    fireEvent.change(summary, { target: { value: saved.summary } });
    fireEvent.click(screen.getByRole('button', { name: '회의록 저장' }));
    expect(await screen.findByText('저장 완료')).toBeTruthy();

    fireEvent.change(summary, { target: { value: 'version 8을 기준으로 한 새 편집' } });
    fireEvent.click(screen.getByRole('button', { name: '회의록 저장' }));
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

  it('cancels Template regeneration without mutations and applies one correlated terminal snapshot after explicit confirmation', async () => {
    const api010 = vi.fn();
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(input));
      if (url.pathname === '/api/v1/meeting-sessions/review-minutes' && init?.method !== 'POST') {
        const count = api010.mock.calls.length;
        api010();
        if (count < 2) return apiResponse({ data: { ...reviewData, allowedActions: [...reviewData.allowedActions, 'REGENERATE_MINUTES'] } });
        return apiResponse({ data: {
          ...reviewData, version: 8, minutes: { ...reviewData.minutes, templateId: 'project', templateVersion: '2', summary: 'Regenerated minutes' },
          speakers: [{ ...reviewData.speakers[0], participantId: 'p1' }],
          transcript: [{ segmentId: 'terminal-segment', speakerId: 'speaker-a', startMs: 0, endMs: 1_000, text: 'Authoritative transcript' }],
          allowedActions: ['UPDATE_MINUTES'],
          lastOperation: { type: 'MINUTES_REGENERATION', outcome: 'SUCCEEDED', requestedTemplateId: 'project' },
        } });
      }
      if (url.pathname === '/api/v1/meeting-sessions/review-minutes/minutes/regenerate' && init?.method === 'POST') {
        return httpResponse({ data: { sessionId: 'review-minutes', status: 'PROCESSING', stage: 'DRAFT_REGENERATION', templateId: 'project' } }, 202);
      }
      if (url.pathname === '/api/v1/templates') return apiResponse({ data: { items: [
        { id: 'weekly', name: 'Weekly', version: '4' }, { id: 'project', name: 'Project', version: '2' },
      ] } });
      if (url.pathname.endsWith('/app-config')) return apiResponse({ data: {
        company: { id: 'company-1', name: 'Example', timezone: 'Asia/Seoul' },
        document: { provider: 'NOTION', configured: true, connectionVersion: 2, rootUrl: 'https://notion.example' },
      } });
      if (url.pathname.endsWith('/participants')) return apiResponse({ data: { items: [{ id: 'p1', name: 'Ada Lovelace', email: 'private@example.com' }] } });
      throw new Error(`Unexpected request: ${url.pathname}`);
    });
    renderReview(fetchMock);

    const selector = await screen.findByLabelText('새 Template');
    fireEvent.change(selector, { target: { value: 'project' } });
    fireEvent.click(screen.getByRole('button', { name: '선택한 Template으로 재생성' }));
    const dialog = await screen.findByRole('dialog', { name: 'Minutes를 재생성할까요?' });
    fireEvent.keyDown(dialog, { key: 'Escape' });
    expect(screen.queryByRole('dialog')).toBeNull();
    await waitFor(() => expect(document.activeElement).toBe(selector));
    expect((screen.getByLabelText('새 Template') as HTMLSelectElement).value).toBe('weekly');
    expect(fetchMock.mock.calls.some(([input, init]) => String(input).endsWith('/minutes/regenerate') || init?.method === 'PUT')).toBe(false);

    fireEvent.change(await screen.findByLabelText('새 Template'), { target: { value: 'project' } });
    fireEvent.click(screen.getByRole('button', { name: '선택한 Template으로 재생성' }));
    fireEvent.click(await screen.findByRole('button', { name: '재생성 시작' }));
    expect(await screen.findByText('Minutes를 선택한 Template으로 재생성했습니다.')).toBeTruthy();
    const post = fetchMock.mock.calls.find(([input, init]) => String(input).endsWith('/minutes/regenerate') && init?.method === 'POST');
    expect(post).toBeTruthy();
    expect(new Headers(post?.[1]?.headers).get('If-Match')).toBe('"7"');
    expect(new Headers(post?.[1]?.headers).get('Idempotency-Key')).toMatch(/.+/);
    expect(JSON.parse(String(post?.[1]?.body))).toEqual({ templateId: 'project' });
    expect(fetchMock.mock.calls.filter(([input, init]) => String(input).endsWith('/minutes/regenerate') && init?.method === 'POST')).toHaveLength(1);
    expect((screen.getByLabelText('회의 요약') as HTMLTextAreaElement).value).toBe('Regenerated minutes');
    fireEvent.click(screen.getByRole('button', { name: 'Transcript 원문 보기' }));
    const drawer = await screen.findByRole('dialog', { name: 'Transcript 원문' });
    expect(within(drawer).getByText('Authoritative transcript')).toBeTruthy();
  });

  it('saves a dirty Minutes draft and re-reads API-010 before regenerating with the confirmed latest version', async () => {
    const initial = { ...reviewData, allowedActions: [...reviewData.allowedActions, 'REGENERATE_MINUTES'] };
    const savedMinutes = { ...reviewData.minutes, summary: 'Draft saved before regeneration' };
    const afterSave = { ...initial, version: 8, minutes: savedMinutes };
    const terminal = {
      ...afterSave, version: 9,
      minutes: { ...savedMinutes, templateId: 'project', templateVersion: '2', summary: 'Regenerated from saved draft' },
      lastOperation: { type: 'MINUTES_REGENERATION', outcome: 'SUCCEEDED', requestedTemplateId: 'project' },
    };
    const fetchMock = minutesIntegrationFetch({
      reviews: [initial, afterSave, afterSave, terminal],
      updateMinutes: () => httpResponse({ data: { version: 8, minutes: savedMinutes } }),
    });
    renderReview(fetchMock);

    fireEvent.change(await screen.findByLabelText('회의 요약'), { target: { value: savedMinutes.summary } });
    fireEvent.change(await screen.findByLabelText('새 Template'), { target: { value: 'project' } });
    fireEvent.click(screen.getByRole('button', { name: '선택한 Template으로 재생성' }));
    fireEvent.click(await screen.findByRole('button', { name: '재생성 시작' }));

    expect(await screen.findByText('Minutes를 선택한 Template으로 재생성했습니다.')).toBeTruthy();
    const calls = fetchMock.mock.calls;
    const putIndex = calls.findIndex(([input, init]) => String(input).endsWith('/minutes') && init?.method === 'PUT');
    const postIndex = calls.findIndex(([input, init]) => String(input).endsWith('/minutes/regenerate') && init?.method === 'POST');
    expect(putIndex).toBeGreaterThan(-1);
    expect(postIndex).toBeGreaterThan(putIndex);
    expect(new Headers(calls[putIndex]?.[1]?.headers).get('If-Match')).toBe('"7"');
    expect(new Headers(calls[postIndex]?.[1]?.headers).get('If-Match')).toBe('"8"');
    expect(JSON.parse(String(calls[postIndex]?.[1]?.body))).toEqual({ templateId: 'project' });
    expect((screen.getByLabelText('회의 요약') as HTMLTextAreaElement).value).toBe('Regenerated from saved draft');
  });

  it('reconciles an API-010 rollback after regeneration failure and does not show the requested Template as current', async () => {
    const initial = { ...reviewData, allowedActions: [...reviewData.allowedActions, 'REGENERATE_MINUTES'] };
    const rollback = { ...initial, version: 8, lastOperation: {
      type: 'MINUTES_REGENERATION', outcome: 'FAILED', requestedTemplateId: 'project', errorCode: 'MINUTES_REGENERATION_FAILED',
    } };
    const fetchMock = minutesIntegrationFetch({ reviews: [initial, initial, rollback] });
    renderReview(fetchMock);

    fireEvent.change(await screen.findByLabelText('새 Template'), { target: { value: 'project' } });
    fireEvent.click(screen.getByRole('button', { name: '선택한 Template으로 재생성' }));
    fireEvent.click(await screen.findByRole('button', { name: '재생성 시작' }));

    expect(await screen.findByText('Minutes를 재생성하지 못해 이전 회의록을 복구했습니다. 기존 회의록을 확인해 주세요.')).toBeTruthy();
    expect((screen.getByLabelText('회의 요약') as HTMLTextAreaElement).value).toBe(reviewData.minutes.summary);
    expect(screen.getByText('weekly', { selector: '.review-template-identity strong' })).toBeTruthy();
    expect(fetchMock.mock.calls.filter(([input, init]) => String(input).endsWith('/minutes/regenerate') && init?.method === 'POST')).toHaveLength(1);
  });

  it('checks API-010 after an uncertain API-013 response without retrying the POST', async () => {
    let reviewReads = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(input));
      if (url.pathname === '/api/v1/meeting-sessions/review-minutes' && init?.method !== 'POST') {
        const read = reviewReads++;
        if (read < 2) return apiResponse({ data: { ...reviewData, allowedActions: [...reviewData.allowedActions, 'REGENERATE_MINUTES'] } });
        return apiResponse({ data: {
          ...reviewData, version: 8, minutes: { ...reviewData.minutes, templateId: 'project', templateVersion: '2' },
          lastOperation: { type: 'MINUTES_REGENERATION', outcome: 'SUCCEEDED', requestedTemplateId: 'project' },
        } });
      }
      if (url.pathname === '/api/v1/meeting-sessions/review-minutes/minutes/regenerate' && init?.method === 'POST') throw new Error('connection lost after request');
      if (url.pathname === '/api/v1/templates') return apiResponse({ data: { items: [
        { id: 'weekly', name: 'Weekly', version: '4' }, { id: 'project', name: 'Project', version: '2' },
      ] } });
      if (url.pathname.endsWith('/app-config')) return apiResponse({ data: {
        company: { id: 'company-1', name: 'Example', timezone: 'Asia/Seoul' },
        document: { provider: 'NOTION', configured: true, connectionVersion: 2, rootUrl: 'https://notion.example' },
      } });
      if (url.pathname.endsWith('/participants')) return apiResponse({ data: { items: [{ id: 'p1', name: 'Ada Lovelace', email: 'private@example.com' }] } });
      throw new Error(`Unexpected request: ${url.pathname}`);
    });
    renderReview(fetchMock);

    fireEvent.change(await screen.findByLabelText('새 Template'), { target: { value: 'project' } });
    fireEvent.click(screen.getByRole('button', { name: '선택한 Template으로 재생성' }));
    fireEvent.click(await screen.findByRole('button', { name: '재생성 시작' }));

    expect(await screen.findByText('Minutes를 선택한 Template으로 재생성했습니다.')).toBeTruthy();
    const calls = fetchMock.mock.calls;
    expect(calls.filter(([input, init]) => String(input).endsWith('/minutes/regenerate') && init?.method === 'POST')).toHaveLength(1);
    expect(calls.filter(([input]) => new URL(String(input)).pathname === '/api/v1/meeting-sessions/review-minutes')).toHaveLength(3);
  });

  it('resumes observing DRAFT_REGENERATION after returning to Review without rendering partial Minutes', async () => {
    let statusReads = 0;
    const terminal = {
      ...reviewData, version: 8, minutes: { ...reviewData.minutes, templateId: 'project', templateVersion: '2', summary: 'Returned after completion' },
      lastOperation: { type: 'MINUTES_REGENERATION', outcome: 'SUCCEEDED', requestedTemplateId: 'project' },
    };
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      void init;
      const url = new URL(String(input));
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

    expect(await screen.findByLabelText('회의 요약')).toHaveProperty('value', 'Returned after completion');
    expect(statusReads).toBe(3);
    expect(fetchMock.mock.calls.some(([input, init]) => String(input).endsWith('/minutes/regenerate') || init?.method === 'PUT')).toBe(false);
  });

  it('applies a correlated terminal API-010 REVIEW snapshot when the initial Review read races with completion', async () => {
    const terminal = {
      ...reviewData,
      version: 8,
      speakers: [{ speakerId: 'speaker-b', label: 'Speaker B', participantId: 'p1' }],
      transcript: [{ segmentId: 'terminal-segment', speakerId: 'speaker-b', startMs: 3000, endMs: 5000, text: 'Terminal transcript' }],
      minutes: { ...reviewData.minutes, templateId: 'project', templateVersion: '2', summary: 'Terminal regenerated Minutes' },
      allowedActions: ['UPDATE_SPEAKER_MAPPING'],
      lastOperation: { type: 'MINUTES_REGENERATION', outcome: 'SUCCEEDED', requestedTemplateId: 'project' },
    };
    let reviewReads = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = new URL(String(input));
      if (url.pathname === '/api/v1/meeting-sessions/review-minutes') {
        reviewReads += 1;
        if (reviewReads === 1) return apiResponse({ data: {
          sessionId: 'review-minutes', version: 7, status: 'PROCESSING',
          processing: { stage: 'DRAFT_REGENERATION', progressPercent: 100 }, allowedActions: [],
        } });
        return apiResponse({ data: { ...terminal, status: 'REVIEW' } });
      }
      if (url.pathname === '/api/v1/app-config') return apiResponse({ data: {
        company: { id: 'company-1', name: 'Example', timezone: 'Asia/Seoul' },
        document: { provider: 'NOTION', configured: true, connectionVersion: 2, rootUrl: 'https://notion.example' },
      } });
      if (url.pathname === '/api/v1/participants') return apiResponse({ data: { items: [{ id: 'p1', name: 'Ada Lovelace', email: 'private@example.com' }] } });
      throw new Error(`Unexpected request: ${url.pathname}`);
    });
    renderReview(fetchMock);

    expect(await screen.findByLabelText('회의 요약')).toHaveProperty('value', 'Terminal regenerated Minutes');
    expect(screen.getByText('Terminal transcript')).toBeTruthy();
    expect(screen.getByLabelText('Speaker B 참가자')).toBeTruthy();
    expect(screen.queryByRole('alert')).toBeNull();
    expect(reviewReads).toBe(2);
  });
});
