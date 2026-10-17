package cloud.mineplay.mobarena.website;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WebsiteAcknowledgementTest {
    private static final UUID EVENT = UUID.fromString("6b8ca269-160e-439a-8e2c-b66900d552ee");

    static byte[] acknowledgement(UUID event, String result, String revision) {
        return ("{\"event_id\":\"" + event + "\",\"result\":\"" + result
                + "\",\"stored_revision\":\"" + revision + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    @Test void acceptsOnlyCommittedResultsWithEqualOrNewerRevision() throws Exception {
        for (String result : new String[]{"applied", "duplicate", "stale"}) {
            assertEquals(3, WebsiteAcknowledgement.revision(acknowledgement(EVENT, result, "3"), EVENT, 3));
            assertEquals(Long.MAX_VALUE, WebsiteAcknowledgement.revision(
                    acknowledgement(EVENT, result, Long.toString(Long.MAX_VALUE)), EVENT, 3));
        }
    }

    @Test void refusesDuplicateMissingUnknownOrCoercedFieldsAndTrailingJson() {
        String valid = new String(acknowledgement(EVENT, "applied", "3"), StandardCharsets.UTF_8);
        for (String invalid : new String[]{"{}", "[]", "null", valid + "{}", valid + " garbage",
                valid.replace("\"3\"", "3"), valid.replace("\"3\"", "null"),
                valid.replace("\"3\"", "[\"3\"]"), valid.replace("\"3\"", "{\"value\":\"3\"}"),
                valid.replace("\"stored_revision\"", "\"unknown\""), valid.replace("}", ",\"extra\":\"1\"}"),
                valid.replace("}", ",\"stored_revision\":\"4\"}"), valid.replace("}", ",}"),
                valid.replace("\"applied\"", "\"queued\""), valid.replace(EVENT.toString(), UUID.randomUUID().toString()),
                valid.replace("\"3\"", "\"0\""), valid.replace("\"3\"", "\"03\""),
                valid.replace("\"3\"", "\"+3\""), valid.replace("\"3\"", "\"3e0\""),
                valid.replace("\"3\"", "\"9223372036854775808\""), valid.replace("\"3\"", "\"2\""),
                valid.replace("\"event_id\"", "event_id"), "//comment\n" + valid}) {
            assertThrows(IOException.class, () -> WebsiteAcknowledgement.revision(
                    invalid.getBytes(StandardCharsets.UTF_8), EVENT, 3), invalid);
        }
    }

    @Test void refusesOversizedOrMalformedUtf8Responses() {
        assertThrows(IOException.class, () -> WebsiteAcknowledgement.revision(new byte[16385], EVENT, 1));
        assertThrows(IOException.class, () -> WebsiteAcknowledgement.revision(new byte[]{(byte) 0xc3, 0x28}, EVENT, 1));
    }
}
