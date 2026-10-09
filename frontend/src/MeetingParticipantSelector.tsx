import type { Participant } from './participantsApi';

type Props = {
  participants: Participant[];
  selectedIds: string[];
  isDisabled: boolean;
  isReady: boolean;
  pickerOpen: boolean;
  participantError?: boolean;
  onTogglePicker: () => void;
  onToggleParticipant: (id: string) => void;
};

export function MeetingParticipantSelector({
  participants,
  selectedIds,
  isDisabled,
  isReady,
  pickerOpen,
  participantError = false,
  onTogglePicker,
  onToggleParticipant,
}: Props) {
  const selectedParticipants = participants.filter(({ id }) => selectedIds.includes(id));

  return (
    <>
      {selectedParticipants.length > 0 && <ul className="selected-participants" aria-label="선택한 참석자">
        {selectedParticipants.map((participant) => <li key={participant.id}>
          <span>{participant.name}<small>{participant.email}</small></span>
          <button type="button" className="remove-participant" aria-label={`${participant.name} 선택 해제`} onClick={() => onToggleParticipant(participant.id)} disabled={isDisabled}>×</button>
        </li>)}
      </ul>}

      {participants.length > 0 && <button className="button button-secondary picker-toggle" type="button" aria-expanded={pickerOpen} aria-invalid={participantError} aria-describedby={participantError ? 'meeting-participants-error' : undefined} onClick={onTogglePicker} disabled={isDisabled}>
        {pickerOpen ? '참석자 선택 닫기' : '참석자 추가'}
      </button>}

      {pickerOpen && isReady && participants.length > 0 && <fieldset className="participant-picker" aria-invalid={participantError} aria-describedby={participantError ? 'meeting-participants-error' : undefined}>
        <legend>참석자 선택</legend>
        {participants.map((participant) => <label className="participant-option" key={participant.id}>
          <input type="checkbox" aria-label={`${participant.name} ${participant.email}`} checked={selectedIds.includes(participant.id)} onChange={() => onToggleParticipant(participant.id)} disabled={isDisabled} />
          <span>{participant.name}<small>{participant.email}</small></span>
        </label>)}
      </fieldset>}
    </>
  );
}
