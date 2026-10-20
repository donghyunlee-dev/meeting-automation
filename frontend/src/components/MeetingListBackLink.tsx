import { Link } from 'react-router';

export function MeetingListBackLink() {
  return <Link className="back-link" to="/meetings" aria-label="회의록 목록으로"><span aria-hidden="true">←</span> 회의록 목록</Link>;
}
