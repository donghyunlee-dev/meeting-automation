package com.meetingautomation.document;
import static org.junit.jupiter.api.Assertions.*;
import com.meetingautomation.api.participant.ParticipantUpdateRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
class ParticipantDepartmentTests {
 @Test void legacyPagesHaveEmptyDepartment() { assertEquals("", new JsonMapper().valueToTree(ParticipantPageMapper.map("1", "Ada", "Email: ada@example.test")).path("department").asString("missing")); }
 @Test void readsDepartmentFromPage() { assertEquals("Research", new JsonMapper().valueToTree(ParticipantPageMapper.map("1", "Ada", "Email: ada@example.test\nDepartment: Research")).path("department").asString("missing")); }
 @Test void acceptsDepartmentOnlyPatch() { assertDoesNotThrow(() -> ParticipantUpdateRequest.parse(new JsonMapper().readTree("{\"department\":\"\"}"))); }
}
