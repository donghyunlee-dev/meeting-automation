import { useLayoutEffect, useRef } from 'react';
import type { Participant } from './participantsApi';
import type { ReviewSpeaker, TranscriptSegment } from './reviewApi';

type Props = {
  open: boolean;
  segments: TranscriptSegment[];
  speakers: ReviewSpeaker[];
  participants: Participant[];
  selectedParticipantId: (speaker: ReviewSpeaker) => string | null;
  onClose: () => void;
};

function formatTime(milliseconds: number): string {
  const totalSeconds = Math.max(0, Math.floor(milliseconds / 1000));
  const seconds = totalSeconds % 60;
  const totalMinutes = Math.floor(totalSeconds / 60);
  const minutes = totalMinutes % 60;
  const hours = Math.floor(totalMinutes / 60);
  const pad = (value: number) => String(value).padStart(2, '0');
  return hours > 0 ? `${pad(hours)}:${pad(minutes)}:${pad(seconds)}` : `${pad(totalMinutes)}:${pad(seconds)}`;
}

export function ReviewTranscriptDrawer({ open, segments, speakers, participants, selectedParticipantId, onClose }: Props) {
  const dialogRef = useRef<HTMLElement>(null);
  const closeButtonRef = useRef<HTMLButtonElement>(null);
  const returnFocusRef = useRef<HTMLElement | null>(null);

  useLayoutEffect(() => {
    if (!open) return;
    returnFocusRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    closeButtonRef.current?.focus();
    return () => returnFocusRef.current?.focus();
  }, [open]);

  if (!open) return null;
  const orderedSegments = [...segments].sort((left, right) => left.startMs - right.startMs || left.endMs - right.endMs);

  function handleKeyDown(event: React.KeyboardEvent<HTMLElement>) {
    if (event.key === 'Escape') {
      event.preventDefault();
      onClose();
      return;
    }
    if (event.key !== 'Tab') return;
    const focusable = dialogRef.current?.querySelectorAll<HTMLElement>(
      'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
    );
    if (!focusable?.length) return;
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    if (event.shiftKey && (document.activeElement === first || !dialogRef.current?.contains(document.activeElement))) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && (document.activeElement === last || !dialogRef.current?.contains(document.activeElement))) {
      event.preventDefault();
      first.focus();
    }
  }

  return <div className="transcript-drawer-backdrop" data-testid="transcript-drawer-backdrop"
    onMouseDown={(event) => { if (event.target === event.currentTarget) onClose(); }}>
    <section ref={dialogRef} className="transcript-drawer" role="dialog" aria-modal="true" aria-labelledby="transcript-drawer-title" onKeyDown={handleKeyDown}>
      <header className="transcript-drawer-header">
        <div><p className="eyebrow">TRANSCRIPT</p><h2 id="transcript-drawer-title">전사 원문</h2></div>
        <button ref={closeButtonRef} type="button" className="icon-button" aria-label="닫기" onClick={onClose}>×</button>
      </header>
      {orderedSegments.length === 0 ? <p className="transcript-drawer-empty" role="status">표시할 전사 원문이 없습니다.</p>
        : <div className="transcript-drawer-list" aria-label="전사 원문">
          {orderedSegments.map((segment) => {
            const speaker = speakers.find(({ speakerId }) => speakerId === segment.speakerId);
            const participantId = speaker ? selectedParticipantId(speaker) : null;
            const name = participants.find(({ id }) => id === participantId)?.name
              ?? (participantId === speaker?.participantId ? speaker.participantName : null)
              ?? null;
            const label = speaker?.label ?? segment.speakerId;
            return <article className="transcript-drawer-entry" key={segment.segmentId} aria-label={`${label} ${formatTime(segment.startMs)}`}>
              <div className="transcript-drawer-entry-heading">
                <strong>{label}</strong>{name && <span>{name}</span>}
                <time dateTime={String(segment.startMs)}>{formatTime(segment.startMs)}–{formatTime(segment.endMs)}</time>
              </div>
              <p>{segment.text}</p>
            </article>;
          })}
        </div>}
    </section>
  </div>;
}
