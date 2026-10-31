import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router';
import { ProcessingApiError, retryProcessing } from './processingApi';
import { listProcessingFailures, type ProcessingFailureSummary } from './meetingListApi';

const stageLabels: Record<string, string> = {
  AUDIO_ASSEMBLY: '오디오 준비', TRANSCRIPTION: '음성 인식', DIARIZATION: '화자 분석',
  MINUTES_GENERATION: '회의록 생성', DRAFT_READY: '회의록 생성', DRAFT_REGENERATION: '회의록 재생성',
};

function newIdempotencyKey(): string {
  return globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

function formatExpiry(value: string | null): string {
  if (!value) return '오디오 보관 기간을 확인할 수 없습니다.';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '오디오 보관 기간을 확인할 수 없습니다.'
    : `오디오 만료 ${new Intl.DateTimeFormat('ko-KR', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(date)}`;
}

export function ProcessingFailureList() {
  const navigate = useNavigate();
  const [items, setItems] = useState<ProcessingFailureSummary[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [activeSession, setActiveSession] = useState<string | null>(null);
  const [error, setError] = useState('');

  const refresh = useCallback(() => {
    void listProcessingFailures().then((nextItems) => {
      setItems(nextItems);
      setError('');
    }).catch(() => {
      setError('실패한 회의 목록을 불러오지 못했습니다.');
    }).finally(() => setLoaded(true));
  }, []);

  useEffect(() => { refresh(); }, [refresh]);

  const retry = async (item: ProcessingFailureSummary) => {
    if (!item.allowedActions.includes('RETRY_PROCESSING')) return;
    setActiveSession(item.sessionId);
    setError('');
    try {
      await retryProcessing(item.sessionId, item.version, newIdempotencyKey());
      navigate(`/meetings/${encodeURIComponent(item.sessionId)}/processing`);
    } catch (reason) {
      setError(reason instanceof ProcessingApiError && reason.code === 'AUDIO_NOT_AVAILABLE'
        ? '오디오 보관 기간이 지났거나 파일을 찾을 수 없어 재처리하지 못했습니다.'
        : '회의를 다시 처리하지 못했습니다. 목록을 새로고침한 뒤 다시 시도해 주세요.');
    } finally {
      setActiveSession(null);
    }
  };

  if (!loaded || (items.length === 0 && !error)) return null;
  return <section className="processing-failure-list" aria-labelledby="processing-failure-list-title">
    <h2 id="processing-failure-list-title">처리 실패 내역</h2>
    {error && <p className="processing-failure-list-error" role="alert">{error} <button type="button" onClick={refresh}>새로고침</button></p>}
    {items.length > 0 && <ul className="meeting-summary-list">
      {items.map((item) => {
        const canRetry = item.allowedActions.includes('RETRY_PROCESSING');
        const busy = activeSession === item.sessionId;
        return <li className="meeting-summary-row processing-failure-row" key={item.sessionId}>
          <div className="processing-failure-copy">
            <div className="processing-failure-title"><h3>{item.title}</h3><span className="processing-failure-badge">실패</span></div>
            <span className="meeting-summary-meta"><time>{new Intl.DateTimeFormat('ko-KR', { year: 'numeric', month: 'long', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(new Date(item.meetingAt))}</time><span aria-hidden="true">·</span><span>{stageLabels[item.stage] ?? '회의 처리'} 실패</span></span>
            <span className="processing-failure-expiry">{canRetry ? formatExpiry(item.audioExpiresAt) : '보관된 오디오가 없어 재처리할 수 없습니다.'}</span>
          </div>
          {canRetry && <button className="button button-secondary processing-failure-retry" type="button" disabled={activeSession !== null} onClick={() => void retry(item)}>{busy ? '시작 중…' : '회의 다시 처리'}</button>}
        </li>;
      })}
    </ul>}
  </section>;
}
