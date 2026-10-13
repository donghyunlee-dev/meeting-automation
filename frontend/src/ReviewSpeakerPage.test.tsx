import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import type { ComponentProps, ComponentType } from 'react';
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

function apiResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
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

function mockApiFetch(overrides: { config?: unknown; participants?: unknown; review?: unknown } = {}) {
  return vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    void init;
    const url = new URL(String(input));
    if (url.pathname.endsWith('/meeting-sessions/review-minutes')) return apiResponse(overrides.review ?? { data: reviewData });
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

describe('Review minutes and transcript', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', mockApiFetch());
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
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

  it('keeps Minutes drafts isolated by session when the route reuses the Review page', async () => {
    const otherReview = { ...reviewData, sessionId: 'review-other', minutes: { ...reviewData.minutes, summary: '다른 회의 요약' } };
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
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
      return <>
        <button type="button" onClick={() => navigate('/meetings/review-other/review')}>다른 회의 열기</button>
        <button type="button" onClick={() => navigate('/meetings/review-minutes/review')}>첫 번째 회의 열기</button>
        <Routes>
          <Route path="/meetings/:sessionId/review" element={<ReviewPageUnderTest drafts={{}} onInitialize={() => undefined}
            onChange={() => undefined} onCommit={() => undefined} onMinutesSave={vi.fn()} />} />
        </Routes>
      </>;
    }
    render(<MemoryRouter initialEntries={['/meetings/review-minutes/review']}><SwitchableReview /></MemoryRouter>);

    const summary = await screen.findByLabelText('회의 요약') as HTMLTextAreaElement;
    fireEvent.change(summary, { target: { value: '첫 번째 회의의 임시 수정' } });
    fireEvent.click(screen.getByRole('button', { name: '다른 회의 열기' }));
    await screen.findByLabelText('회의 요약');
    expect((screen.getByLabelText('회의 요약') as HTMLTextAreaElement).value).toBe('다른 회의 요약');
    expect((screen.getByRole('button', { name: '회의록 저장' }) as HTMLButtonElement).disabled).toBe(true);

    fireEvent.change(screen.getByLabelText('회의 요약'), { target: { value: '두 번째 회의의 임시 수정' } });
    fireEvent.click(screen.getByRole('button', { name: '첫 번째 회의 열기' }));
    await screen.findByLabelText('회의 요약');
    expect((screen.getByLabelText('회의 요약') as HTMLTextAreaElement).value).toBe('첫 번째 회의의 임시 수정');
    fireEvent.click(screen.getByRole('button', { name: '다른 회의 열기' }));
    await screen.findByLabelText('회의 요약');
    expect((screen.getByLabelText('회의 요약') as HTMLTextAreaElement).value).toBe('두 번째 회의의 임시 수정');
  });
});
