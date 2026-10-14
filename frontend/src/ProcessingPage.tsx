import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { getProcessingStatus, type ProcessingSnapshot, type ProcessingStage } from './processingApi';
import { ProcessingPoller } from './processingPoller';

const stageNames = ['오디오 준비', '음성 인식', '화자 분석', '회의록 생성'] as const;
const stageIndex: Record<ProcessingStage, number> = { AUDIO_ASSEMBLY: 0, TRANSCRIPTION: 1, DIARIZATION: 2, MINUTES_GENERATION: 3, DRAFT_READY: 3, DRAFT_REGENERATION: 3 };
const failureMessages: Record<ProcessingStage, string> = {
  AUDIO_ASSEMBLY: '오디오를 준비하는 중 문제가 발생했습니다.',
  TRANSCRIPTION: '음성을 변환하는 중 문제가 발생했습니다.',
  DIARIZATION: '화자를 분석하는 중 문제가 발생했습니다.',
  MINUTES_GENERATION: '회의록을 생성하는 중 문제가 발생했습니다.',
  DRAFT_READY: '회의록을 생성하는 중 문제가 발생했습니다.',
  DRAFT_REGENERATION: '회의록을 재생성하는 중 문제가 발생했습니다.',
};

export function ProcessingPage() {
  const { sessionId = '' } = useParams();
  const navigate = useNavigate();
  const [snapshot, setSnapshot] = useState<ProcessingSnapshot | null>(null);
  const [failure, setFailure] = useState<'network' | 'missing' | 'invalid' | 'session-mismatch' | 'unavailable' | null>(null);
  const navigated = useRef(false);

  useEffect(() => {
    let live = true;
    const poller = new ProcessingPoller({
      sessionId,
      getStatus: (id, signal) => getProcessingStatus(id, signal),
      onSnapshot: (next) => {
        if (!live) return;
        if (next.sessionId !== sessionId) { setFailure('session-mismatch'); return; }
        setSnapshot(next);
        setFailure(null);
        if (next.status === 'REVIEW' && !navigated.current) {
          navigated.current = true;
          navigate(`/meetings/${encodeURIComponent(sessionId)}/review`, { replace: true });
        }
      },
      onError: (error) => {
        if (!live) return;
        setFailure(error.code === 'SESSION_NOT_FOUND' ? 'missing' : error.code === 'INVALID_RESPONSE' ? 'invalid' : error.code === 'HTTP_ERROR' && error.status !== undefined && error.status < 500 && error.status !== 408 && error.status !== 429 ? 'unavailable' : 'network');
      },
    });
    poller.start();
    const onVisibilityChange = () => { if (document.visibilityState === 'visible') poller.refreshNow(); };
    document.addEventListener('visibilitychange', onVisibilityChange);
    return () => { live = false; document.removeEventListener('visibilitychange', onVisibilityChange); poller.stop(); };
  }, [navigate, sessionId]);

  const reportedStage = snapshot?.processing?.stage ? stageIndex[snapshot.processing.stage] : -1;
  const progress = snapshot?.processing?.progressPercent;
  const validProgress = typeof progress === 'number' && Number.isFinite(progress) && progress >= 0 && progress <= 100;
  const isProcessing = snapshot?.status === 'PROCESSING';
  const activeStage = isProcessing && reportedStage === 2 && validProgress && progress === 100 ? 3 : reportedStage;
  const stageForFailure = snapshot?.processing?.stage;

  return (
    <main className="processing-page" aria-labelledby="processing-title">
      <header className="processing-heading">
        <p className="eyebrow">MEETING AUTOMATION</p>
        <h1 id="processing-title">회의 처리</h1>
        <p>회의 내용을 정리하고 있습니다. 이 화면을 닫아도 처리는 계속됩니다.</p>
      </header>
      {failure === 'missing' && <section className="processing-message" role="alert"><h2>회의를 찾을 수 없습니다</h2><p>회의 처리 정보를 사용할 수 없습니다. 새 회의를 시작해 주세요.</p><Link className="button button-primary" to="/meetings/new">새 회의 시작</Link></section>}
      {failure === 'session-mismatch' && <p role="alert" className="processing-message">회의 처리 정보를 확인할 수 없습니다. 다시 시도해 주세요.</p>}
      {failure === 'invalid' && <p role="alert" className="processing-message">회의 처리 상태를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.</p>}
      {failure === 'unavailable' && <p role="alert" className="processing-message">회의 처리 상태를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.</p>}
      {failure === 'network' && <p role="status" className="processing-message">처리 상태를 불러오지 못했습니다. 연결을 확인하고 다시 확인하는 중입니다.</p>}
      {snapshot?.status === 'PROCESSING_FAILED' && <section className="processing-message" role="alert"><h2>처리를 완료하지 못했습니다</h2><p>{stageForFailure ? failureMessages[stageForFailure] : '회의를 처리하는 중 문제가 발생했습니다.'}</p></section>}
      {snapshot?.status === 'COMPLETED_WITH_WARNINGS' && <p className="processing-message" role="status">회의 처리가 완료되었습니다. 일부 항목을 확인해 주세요.</p>}
      {(failure === null || failure === 'network') && snapshot?.status !== 'PROCESSING_FAILED' && snapshot?.status !== 'COMPLETED_WITH_WARNINGS' && (
        <section className="processing-card" aria-label="처리 단계">
          <ol className="processing-steps">
            {stageNames.map((name, index) => {
              const current = isProcessing && activeStage === index;
              const complete = snapshot?.status === 'REVIEW' || (isProcessing && activeStage > index);
              return <li key={name} className={current ? 'is-current' : complete ? 'is-complete' : ''} aria-current={current ? 'step' : undefined}>
                <span className="processing-step-icon" aria-hidden="true">{complete ? '✓' : current ? '●' : '○'}</span><span>{name}</span>
              </li>;
            })}
          </ol>
          {validProgress && <progress aria-label="처리 진행률" value={progress} max={100}>{progress}%</progress>}
          <p className="processing-current" role="status">{activeStage >= 0 ? `${stageNames[activeStage]} 단계 처리 중입니다.` : '처리 상태를 확인하고 있습니다.'}</p>
        </section>
      )}
    </main>
  );
}
