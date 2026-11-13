import { useState } from 'react';
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
  const [search, setSearch] = useState('');
  const query = search.trim().toLocaleLowerCase();
  const filteredParticipants = participants.filter(({ name, email, department }) => `${name} ${email} ${department ?? ''}`.toLocaleLowerCase().includes(query));
  if (participants.length === 0) return null;

  return (
    <div className={`participant-combo${pickerOpen ? ' is-open' : ''}`}>
      <button className="participant-combo-toggle" type="button" aria-label="참석자 선택" aria-expanded={pickerOpen} aria-controls="meeting-participant-options" aria-invalid={participantError} aria-describedby={participantError ? 'meeting-participants-error' : undefined} onClick={onTogglePicker} disabled={isDisabled}>
        <span>{selectedIds.length > 0 ? `${selectedIds.length}명 선택됨` : '참석자를 선택해 주세요'}</span>
        <svg className="meeting-dropdown-chevron" viewBox="0 0 20 20" aria-hidden="true"><path d="m5 7.5 5 5 5-5" /></svg>
      </button>
      <div className="participant-combo-options" id="meeting-participant-options" hidden={!pickerOpen}>
        {pickerOpen && isReady && <div role="group" aria-label="참석자 목록">
          <input type="search" aria-label="참석자 검색" placeholder="이름, 이메일 또는 부서 검색" value={search} onChange={(event) => setSearch(event.target.value)} disabled={isDisabled} />
          {filteredParticipants.length === 0 && <p>검색 결과가 없습니다.</p>}
          {filteredParticipants.map((participant) => <label className="participant-option" key={participant.id}>
            <input type="checkbox" aria-label={`${participant.name} ${participant.email}${participant.department ? ` ${participant.department}` : ''}`} checked={selectedIds.includes(participant.id)} onChange={() => onToggleParticipant(participant.id)} disabled={isDisabled} />
            <span className="participant-identity"><strong>{participant.name}</strong><small>{participant.email}</small>{participant.department && <small>{participant.department}</small>}</span>
          </label>)}
        </div>}
      </div>
    </div>
  );
}
