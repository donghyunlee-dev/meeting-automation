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

export function ReviewMinutesEditor({ sessionId, minutes, baseline, participants, ownerState, saveState, saveBlockedMessage, disabled = false, onSave, onChange }: Props) {
  const saving = saveState.sessionId === sessionId && saveState.status === 'saving';
  const saved = saveState.sessionId === sessionId && saveState.status === 'saved';
  const failed = saveState.sessionId === sessionId && saveState.status === 'error';

  if (!minutes) {
    return <section className="review-speaker-card" aria-labelledby="minutes-heading">
      <div className="review-speaker-card-heading"><div><h2 id="minutes-heading">회의록</h2></div></div>
      <p role="status">표시할 회의록이 없습니다.</p>
    </section>;
  }

  const valid = isValid(minutes);
  const dirty = !sameMinutes(minutes, baseline);
  const canSave = Boolean(onSave && dirty && valid && !saving && !saveBlockedMessage);
  const canChooseOwner = ownerState === 'ready';
  const handleSave = () => {
    if (!onSave || !canSave) return;
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
        <h2 id="minutes-heading">회의록 편집</h2>
        <p>내용을 수정한 뒤 변경된 회의록 전체를 저장 요청으로 전달합니다.</p>
      </div>
      <div className="review-template-identity" aria-label="회의록 템플릿 (읽기 전용)">
        <span>Template</span><strong>{minutes.templateId}</strong><span>v{minutes.templateVersion}</span>
      </div>
    </div>

      <div className="review-minutes-fields" aria-disabled={disabled}>
      <div className="review-minutes-field">
        <label htmlFor="minutes-summary">회의 요약</label>
        <textarea id="minutes-summary" rows={5} value={minutes.summary}
          disabled={disabled} onChange={(event) => onChange({ ...minutes, summary: event.target.value })} />
      </div>

      {LIST_FIELDS.map(({ key, title }) => <fieldset className="review-minutes-section" key={key}>
        <legend>{title}</legend>
        {minutes[key].map((value, index) => {
          const invalid = value.trim().length === 0;
          const id = `minutes-${key}-${index}`;
          return <div className="review-minutes-list-row" key={`${key}-${index}`}>
            <label className="visually-hidden" htmlFor={id}>{title} {index + 1}</label>
            <textarea id={id} rows={2} value={value} aria-invalid={invalid} disabled={disabled}
              aria-describedby={invalid ? `${id}-error` : undefined} onChange={(event) => updateList(key, index, event.target.value)} />
            {invalid && <p className="review-minutes-field-error" id={`${id}-error`}>내용을 입력해 주세요.</p>}
            <button className="button button-secondary" type="button" aria-label={`${title} ${index + 1} 삭제`}
              disabled={disabled} onClick={() => onChange({ ...minutes, [key]: minutes[key].filter((_, itemIndex) => itemIndex !== index) })}>삭제</button>
          </div>;
        })}
        <button className="button button-secondary review-minutes-add" type="button" disabled={disabled} onClick={() => onChange({ ...minutes, [key]: [...minutes[key], ''] })}>
          {title} 추가
        </button>
      </fieldset>)}

      <fieldset className="review-minutes-section review-action-items">
        <legend>Action Items</legend>
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
          return <div className="review-action-item" key={`action-${index}`}>
            <div className="review-minutes-field">
              <label htmlFor={taskId}>할 일 {index + 1}</label>
              <textarea id={taskId} rows={2} value={item.task} aria-invalid={taskInvalid} disabled={disabled}
                aria-describedby={taskInvalid ? `${taskId}-error` : undefined} onChange={(event) => updateActionItem({ task: event.target.value })} />
              {taskInvalid && <p className="review-minutes-field-error" id={`${taskId}-error`}>할 일을 입력해 주세요.</p>}
            </div>
            <div className="review-minutes-field">
              <label htmlFor={`minutes-action-owner-${index}`}>담당자 {index + 1}</label>
              <select id={`minutes-action-owner-${index}`} value={item.ownerParticipantId ?? ''} disabled={disabled || !canChooseOwner}
                onChange={(event) => updateActionItem({ ownerParticipantId: event.target.value || null })}>
                <option value="">미지정</option>
                {item.ownerParticipantId && (!canChooseOwner || !ownerInRoster) && <option value={item.ownerParticipantId}>기존 담당자 유지</option>}
                {canChooseOwner && participants.map(({ id, name }) => <option key={id} value={id}>{name}</option>)}
              </select>
              {ownerState === 'unavailable' && <div className="review-owner-guidance"><p>참석자 목록을 사용할 수 없습니다. 기존 담당자는 유지됩니다.</p><Link to="/settings" state={{ from: `/meetings/${encodeURIComponent(sessionId)}/review` }}>문서 설정 열기</Link></div>}
              {ownerState === 'loading' && <p className="review-owner-guidance">참석자 목록을 불러오는 중입니다. 기존 담당자는 유지됩니다.</p>}
              {ownerState === 'error' && <p className="review-owner-guidance">참석자 목록을 불러오지 못했습니다. 기존 담당자는 유지됩니다.</p>}
              {ownerState === 'ready' && participants.length === 0 && <p className="review-owner-guidance">선택할 참석자가 없습니다. 기존 담당자는 유지됩니다.</p>}
            </div>
            <div className="review-minutes-field">
              <label htmlFor={dateId}>기한 {index + 1}</label>
              <input id={dateId} type="date" value={item.dueDate ?? ''} aria-invalid={dateInvalid} disabled={disabled}
                aria-describedby={dateInvalid ? `${dateId}-error` : undefined}
                onChange={(event) => updateActionItem({ dueDate: event.target.value || null })} />
              {dateInvalid && <p className="review-minutes-field-error" id={`${dateId}-error`}>날짜를 확인해 주세요.</p>}
            </div>
            <button className="button button-secondary review-action-remove" type="button" aria-label={`Action Item ${index + 1} 삭제`}
              disabled={disabled} onClick={() => onChange({ ...minutes, actionItems: minutes.actionItems.filter((_, itemIndex) => itemIndex !== index) })}>삭제</button>
          </div>;
        })}
        <button className="button button-secondary review-minutes-add" type="button"
          disabled={disabled} onClick={() => onChange({ ...minutes, actionItems: [...minutes.actionItems, { ownerParticipantId: null, task: '', dueDate: null }] })}>
          Action Item 추가
        </button>
      </fieldset>
    </div>

    <div className="review-minutes-save-controls">
      <button className="button button-primary" type="button" disabled={!canSave || disabled} onClick={handleSave}>
        {saving ? '저장 중…' : '회의록 저장'}
      </button>
      {!valid && <p role="status">비어 있는 항목을 확인해 주세요.</p>}
      {!dirty && <p role="status">변경된 내용이 없습니다.</p>}
      {saveBlockedMessage && <p role="status">{saveBlockedMessage}</p>}
      {dirty && valid && !onSave && !saveBlockedMessage && <p role="status">저장 연결을 기다리는 동안 수정 내용은 이 화면에 보관됩니다.</p>}
      {saved && !dirty && <p role="status">{saveState.message || '저장 완료'}</p>}
      {failed && <p role="alert">{saveState.message || '회의록을 저장하지 못했습니다. 수정 내용은 유지됩니다.'}</p>}
    </div>
  </section>;
}
