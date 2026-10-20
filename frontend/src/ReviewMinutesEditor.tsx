import type { ReactNode } from 'react';
import { Link } from 'react-router';
import type { Participant } from './participantsApi';
import type { StructuredMinutes } from './reviewApi';

export type MinutesOwnerState = 'ready' | 'loading' | 'error' | 'unavailable';
export type MinutesSaveState = { sessionId: string; status: 'idle' | 'saving' | 'saved' | 'error'; message?: string };

type Props = {
  sessionId: string;
  minutes: StructuredMinutes | null;
  baseline: StructuredMinutes | null;
  participants: Participant[];
  ownerState: MinutesOwnerState;
  saveState: MinutesSaveState;
  saveBlockedMessage?: string;
  footerContent?: ReactNode;
  actionContent?: ReactNode;
  disabled?: boolean;
  onSave?: (sessionId: string, minutes: StructuredMinutes) => void;
  onChange: (minutes: StructuredMinutes) => void;
};

const LIST_FIELDS = [
  { key: 'discussionPoints', title: '논의 내용' },
  { key: 'decisions', title: '결정 사항' },
  { key: 'followUps', title: '후속 조치' },
] as const;

function validDate(value: string | null): boolean {
  if (value === null || value === '') return true;
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const date = new Date(`${value}T00:00:00.000Z`);
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value;
}

function isValid(minutes: StructuredMinutes): boolean {
  return [...minutes.discussionPoints, ...minutes.decisions, ...minutes.followUps]
    .every((item) => item.trim().length > 0)
    && minutes.actionItems.every((item) => item.task.trim().length > 0 && validDate(item.dueDate));
}

function sameMinutes(left: StructuredMinutes | null, right: StructuredMinutes | null): boolean {
  return JSON.stringify(left) === JSON.stringify(right);
}

export function ReviewMinutesEditor({ sessionId, minutes, baseline, participants, ownerState, saveState, saveBlockedMessage, footerContent, actionContent, disabled = false, onSave, onChange }: Props) {
  const saving = saveState.sessionId === sessionId && saveState.status === 'saving';
  const failed = saveState.sessionId === sessionId && saveState.status === 'error';

  if (!minutes) {
    return <section className="review-speaker-card" aria-labelledby="minutes-heading">
      <div className="review-speaker-card-heading"><div><h2 id="minutes-heading">회의록</h2></div></div>
      <p role="status">표시할 회의록이 없습니다.</p>
      {footerContent}
      {actionContent && <div className="review-flow-dock" role="group" aria-label="회의록 저장 및 전달">{actionContent}</div>}
    </section>;
  }

  const valid = isValid(minutes);
  const canSave = Boolean(onSave && valid && !saving && !saveBlockedMessage);
  const canChooseOwner = ownerState === 'ready';
  const handleSave = () => {
    if (!onSave || !canSave || sameMinutes(minutes, baseline)) return;
    onSave(sessionId, minutes);
  };
  const updateList = (field: typeof LIST_FIELDS[number]['key'], index: number, value: string) => {
    const nextValues = [...minutes[field]];
    nextValues[index] = value;
    onChange({ ...minutes, [field]: nextValues });
  };

  return <section className="review-speaker-card review-minutes-card" aria-labelledby="minutes-heading">
    <div className="review-speaker-card-heading">
      <div>
        <h2 id="minutes-heading">회의록</h2>
        <p>생성된 회의 내용을 항목별로 검토하고 수정합니다.</p>
      </div>
    </div>

    <div className="review-minutes-fields" aria-disabled={disabled}>
        <details className="review-minutes-section">
          <summary className="review-minutes-section-heading"><h4 id="minutes-summary-heading">회의 요약</h4></summary>
          <div className="review-minutes-field">
            <label className="visually-hidden" htmlFor="minutes-summary">회의 요약 내용</label>
            <textarea id="minutes-summary" rows={5} value={minutes.summary}
              disabled={disabled} onChange={(event) => onChange({ ...minutes, summary: event.target.value })} />
          </div>
        </details>

      {LIST_FIELDS.map(({ key, title }) => <details className="review-minutes-section" key={key}>
        <summary className="review-minutes-section-heading">
          <h4 id={`minutes-section-${key}`}>{title}</h4>
        </summary>
        <div className="review-minutes-section-content">
        {minutes[key].map((value, index) => {
          const invalid = value.trim().length === 0;
          const id = `minutes-${key}-${index}`;
          return <div className="review-minutes-list-row" key={`${key}-${index}`}>
            <label className="visually-hidden" htmlFor={id}>{title} {index + 1}</label>
            <textarea id={id} rows={2} value={value} aria-invalid={invalid} disabled={disabled}
              aria-describedby={invalid ? `${id}-error` : undefined} onChange={(event) => updateList(key, index, event.target.value)} />
            {invalid && <p className="review-minutes-field-error" id={`${id}-error`}>내용을 입력해 주세요.</p>}
            <button className="minutes-item-remove" type="button" aria-label={`${title} ${index + 1} 삭제`} title="항목 삭제"
              disabled={disabled} onClick={() => onChange({ ...minutes, [key]: minutes[key].filter((_, itemIndex) => itemIndex !== index) })}>
              <svg viewBox="0 0 20 20" aria-hidden="true"><path d="m6 6 8 8M14 6l-8 8" /></svg>
            </button>
          </div>;
        })}
        <div className="review-minutes-section-actions"><button className="review-minutes-add" type="button" aria-label={`${title} 추가`} disabled={disabled}
            onClick={() => onChange({ ...minutes, [key]: [...minutes[key], ''] })}>+ 추가</button></div>
        </div>
      </details>)}

      <details className="review-minutes-section review-action-items">
        <summary className="review-minutes-section-heading">
          <h4 id="minutes-action-items-heading">실행 항목</h4>
        </summary>
        <div className="review-minutes-section-content">
        {minutes.actionItems.map((item, index) => {
          const taskId = `minutes-action-task-${index}`;
          const dateId = `minutes-action-date-${index}`;
          const taskInvalid = item.task.trim().length === 0;
          const dateInvalid = !validDate(item.dueDate);
          const ownerInRoster = participants.some(({ id }) => id === item.ownerParticipantId);
          const updateActionItem = (update: Partial<typeof item>) => {
            const actionItems = [...minutes.actionItems];
            actionItems[index] = { ...item, ...update };
            onChange({ ...minutes, actionItems });
          };
          return <article className="review-action-item" key={`action-${index}`} aria-labelledby={`minutes-action-heading-${index}`}>
            <h5 id={`minutes-action-heading-${index}`}>실행 항목 {index + 1}</h5>
            <button className="minutes-item-remove" type="button" aria-label={`실행 항목 ${index + 1} 삭제`} title="항목 삭제"
              disabled={disabled} onClick={() => onChange({ ...minutes, actionItems: minutes.actionItems.filter((_, itemIndex) => itemIndex !== index) })}>
              <svg viewBox="0 0 20 20" aria-hidden="true"><path d="m6 6 8 8M14 6l-8 8" /></svg>
            </button>
            <div className="review-minutes-field review-action-task">
              <label className="visually-hidden" htmlFor={taskId}>실행 항목 {index + 1} 내용</label>
              <textarea id={taskId} rows={2} placeholder="실행할 내용을 입력하세요" value={item.task} aria-invalid={taskInvalid} disabled={disabled}
                aria-describedby={taskInvalid ? `${taskId}-error` : undefined} onChange={(event) => updateActionItem({ task: event.target.value })} />
              {taskInvalid && <p className="review-minutes-field-error" id={`${taskId}-error`}>할 일을 입력해 주세요.</p>}
            </div>
            <div className="review-action-meta">
              <div className="meeting-select-control review-select-control"><select id={`minutes-action-owner-${index}`} value={item.ownerParticipantId ?? ''} disabled={disabled || !canChooseOwner}
                aria-label={`실행 항목 ${index + 1} 담당자`}
                onChange={(event) => updateActionItem({ ownerParticipantId: event.target.value || null })}>
                <option value="">담당자 선택</option>
                {item.ownerParticipantId && (!canChooseOwner || !ownerInRoster) && <option value={item.ownerParticipantId}>기존 담당자 유지</option>}
                {canChooseOwner && participants.map(({ id, name }) => <option key={id} value={id}>{name}</option>)}
              </select><svg className="meeting-dropdown-chevron" viewBox="0 0 20 20" aria-hidden="true"><path d="m5 7.5 5 5 5-5" /></svg></div>
              <input id={dateId} type="date" value={item.dueDate ?? ''} aria-label={`실행 항목 ${index + 1} 기한`} aria-invalid={dateInvalid} disabled={disabled}
                aria-describedby={dateInvalid ? `${dateId}-error` : undefined}
                onChange={(event) => updateActionItem({ dueDate: event.target.value || null })} />
            </div>
            {ownerState === 'unavailable' && <div className="review-owner-guidance"><p>참석자 목록을 사용할 수 없습니다. 기존 담당자는 유지됩니다.</p><Link to="/settings" state={{ from: `/meetings/${encodeURIComponent(sessionId)}/review` }}>문서 설정 열기</Link></div>}
            {ownerState === 'loading' && <p className="review-owner-guidance">참석자 목록을 불러오는 중입니다. 기존 담당자는 유지됩니다.</p>}
            {ownerState === 'error' && <p className="review-owner-guidance">참석자 목록을 불러오지 못했습니다. 기존 담당자는 유지됩니다.</p>}
            {ownerState === 'ready' && participants.length === 0 && <p className="review-owner-guidance">선택할 참석자가 없습니다. 기존 담당자는 유지됩니다.</p>}
            {dateInvalid && <p className="review-minutes-field-error review-action-date-error" id={`${dateId}-error`}>날짜를 확인해 주세요.</p>}
          </article>;
        })}
        <div className="review-minutes-section-actions"><button className="review-minutes-add" type="button" aria-label="실행 항목 추가" disabled={disabled}
            onClick={() => onChange({ ...minutes, actionItems: [...minutes.actionItems, { ownerParticipantId: null, task: '', dueDate: null }] })}>+ 추가</button></div>
        </div>
      </details>
    </div>

    {footerContent}
    <div className="review-flow-dock" role="group" aria-label="회의록 저장 및 전달">
      <div className="review-minutes-save-controls">
        <button className="button button-secondary" type="button" disabled={!canSave || disabled} aria-busy={saving} onClick={handleSave}>
          회의록 변경사항 저장
        </button>
        {saving && <p role="status">회의록 변경사항 저장 중…</p>}
        {failed && <p role="alert">{saveState.message || '회의록을 저장하지 못했습니다. 수정 내용은 유지됩니다.'}</p>}
      </div>
      {actionContent}
    </div>
  </section>;
}
