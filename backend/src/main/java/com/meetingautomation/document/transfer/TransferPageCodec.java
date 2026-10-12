package com.meetingautomation.document.transfer;

import com.meetingautomation.document.*;
import java.time.LocalDate;
import java.util.*;
import static com.meetingautomation.document.transfer.TransferCodec.Code.*;

/** Readable, ordered body blocks. The machine marker never contains body content. */
public final class TransferPageCodec {
    public record Block(String type, String text) { }
    private final TransferCodec codec = new TransferCodec();

    public List<Block> encode(TransferRecord r) {
        List<Block> b = new ArrayList<>();
        if (r.participant() != null) b.add(new Block("paragraph", "Email: " + r.participant().email()));
        else {
            heading(b, "Meeting information"); paragraph(b, "Date: " + r.marker().meetingAt()); paragraph(b, "Template: " + r.marker().templateId());
            for (ParticipantSummary p : r.participants()) { paragraph(b, "Participant: " + p.id()); paragraph(b, p.name()); }
            for (TransferMarker.Speaker s : r.marker().speakers()) { paragraph(b, "Speaker: " + s.speakerId()); paragraph(b, s.label()); paragraph(b, "Participant: " + Objects.toString(s.participantId(), "")); }
            if (r.marker().failure() != null) paragraph(b, "녹음 변환 결과: 실패");
            if (r.minutes() != null) {
                var m = r.minutes(); heading(b, "Summary"); paragraph(b, "Template version: " + m.templateVersion()); paragraph(b, m.summary());
                heading(b, "Discussion points"); items(b, m.discussionPoints()); heading(b, "Decisions"); items(b, m.decisions());
                heading(b, "Action Items");
                for (var a : m.actionItems()) { paragraph(b, "Owner: " + Objects.toString(a.ownerParticipantId(), "")); paragraph(b, "Due: " + Objects.toString(a.dueDate(), "")); paragraph(b, a.task()); }
                heading(b, "Follow ups"); items(b, m.followUps());
            }
            if (r.transcript() != null) {
                heading(b, "Transcript");
                for (var s : r.transcript()) { paragraph(b, "Segment: " + s.segmentId()); paragraph(b, "Speaker: " + s.speakerId()); paragraph(b, "Start ms: " + s.startMs()); paragraph(b, "End ms: " + s.endMs()); paragraph(b, s.text()); }
            }
        }
        b.add(new Block("code", codec.encodeMarker(r.marker()))); return List.copyOf(b);
    }

    public TransferRecord decode(String id, String revision, String url, String title, List<Block> blocks) {
        try {
            TransferMarker marker = codec.parseMarker(blocks.stream().filter(b -> b.type().equals("code")).map(Block::text).toList());
            List<Block> body = blocks.stream().filter(b -> !b.type().equals("code")).toList();
            if (marker.recordType().equals("participant")) {
                Participant participant = ParticipantPageMapper.map(id, title, String.join("\n", body.stream().map(Block::text).toList()));
                TransferRecord r = new TransferRecord(id, revision, url, marker, title, null, null, List.of(), participant);
                if (!codec.canonicalDigest(r).equals(marker.canonicalDigest())) throw invalid(INVALID_RECORD);
                return r;
            }
            Reader read = new Reader(body); read.heading("Meeting information");
            if (!java.time.OffsetDateTime.parse(read.field("Date: ")).equals(marker.meetingAt()) || !read.field("Template: ").equals(marker.templateId())) throw invalid(INVALID_RECORD);
            List<ParticipantSummary> roster = new ArrayList<>();
            for (String participantId : marker.participantIds()) { if (!read.field("Participant: ").equals(participantId)) throw invalid(BROKEN_REFERENCE); roster.add(new ParticipantSummary(participantId, read.paragraph())); }
            for (var s : marker.speakers()) { if (!read.field("Speaker: ").equals(s.speakerId()) || !read.paragraph().equals(s.label()) || !read.field("Participant: ").equals(Objects.toString(s.participantId(), ""))) throw invalid(BROKEN_REFERENCE); }
            if (marker.failure() != null && !read.paragraph().equals("녹음 변환 결과: 실패")) throw invalid(INVALID_RECORD);
            StructuredMinutes minutes = null; List<TranscriptSegment> transcript = null;
            if (read.hasHeading("Summary")) {
                read.heading("Summary"); String version = read.field("Template version: "); String summary = read.paragraph();
                read.heading("Discussion points"); List<String> discussion = read.items(); read.heading("Decisions"); List<String> decisions = read.items(); read.heading("Action Items");
                List<StructuredMinutes.ActionItem> actions = new ArrayList<>();
                while (read.hasParagraph()) { String owner = read.field("Owner: "); String due = read.field("Due: "); actions.add(new StructuredMinutes.ActionItem(owner.isEmpty() ? null : owner, read.paragraph(), due.isEmpty() ? null : LocalDate.parse(due))); }
                read.heading("Follow ups"); minutes = new StructuredMinutes(marker.templateId(), version, summary, discussion, decisions, actions, read.items());
            }
            if (read.hasHeading("Transcript")) {
                read.heading("Transcript"); transcript = new ArrayList<>();
                while (read.hasParagraph()) { String segment = read.field("Segment: "); String speaker = read.field("Speaker: "); long start = Long.parseLong(read.field("Start ms: ")); long end = Long.parseLong(read.field("End ms: ")); transcript.add(new TranscriptSegment(segment, speaker, start, end, read.paragraph())); }
            }
            if (!read.done()) throw invalid(INVALID_RECORD);
            TransferRecord r = new TransferRecord(id, revision, url, marker, title, minutes, transcript, roster, null); validateReferences(r, new HashSet<>(marker.participantIds()));
            codec.canonicalDigest(r); return r;
        } catch (TransferCodec.ValidationException e) { throw e; }
        catch (RuntimeException e) { throw invalid(INVALID_RECORD); }
    }

    public static void validateReferences(TransferRecord r, Set<String> participants) {
        if (r.participant() != null) return;
        Set<String> roster = new HashSet<>(r.marker().participantIds());
        if (roster.size() != r.marker().participantIds().size() || !participants.containsAll(roster)
                || !roster.equals(new HashSet<>(r.participants().stream().map(ParticipantSummary::id).toList()))) throw invalid(BROKEN_REFERENCE);
        Set<String> speakers = new HashSet<>();
        for (var s : r.marker().speakers()) { if (!speakers.add(s.speakerId()) || s.participantId() != null && !roster.contains(s.participantId())) throw invalid(BROKEN_REFERENCE); }
        if (r.minutes() != null) for (var a : r.minutes().actionItems()) if (a.ownerParticipantId() != null && !roster.contains(a.ownerParticipantId())) throw invalid(BROKEN_REFERENCE);
        if (r.transcript() != null) for (var s : r.transcript()) if (!speakers.contains(s.speakerId()) || s.startMs() < 0 || s.endMs() < s.startMs()) throw invalid(BROKEN_REFERENCE);
    }
    private static TransferCodec.ValidationException invalid(TransferCodec.Code code) { return new TransferCodec.ValidationException(code); }
    private static void heading(List<Block> b, String text) { b.add(new Block("heading_2", text)); }
    private static void paragraph(List<Block> b, String text) { b.add(new Block("paragraph", text)); }
    private static void items(List<Block> b, List<String> items) { items.forEach(s -> b.add(new Block("bulleted_list_item", s))); }
    private static final class Reader {
        private final List<Block> blocks; private int index;
        Reader(List<Block> blocks) { this.blocks = blocks; }
        boolean done() { return index == blocks.size(); }
        boolean hasHeading(String text) { return !done() && blocks.get(index).equals(new Block("heading_2", text)); }
        boolean hasParagraph() { return !done() && blocks.get(index).type().equals("paragraph"); }
        String take(String type) { if (done() || !blocks.get(index).type().equals(type)) throw invalid(INVALID_RECORD); return blocks.get(index++).text(); }
        void heading(String text) { if (!take("heading_2").equals(text)) throw invalid(INVALID_RECORD); }
        String paragraph() { return take("paragraph"); }
        String field(String prefix) { String value = paragraph(); if (!value.startsWith(prefix)) throw invalid(INVALID_RECORD); return value.substring(prefix.length()); }
        List<String> items() { List<String> values = new ArrayList<>(); while (!done() && blocks.get(index).type().equals("bulleted_list_item")) values.add(take("bulleted_list_item")); return values; }
    }
}
