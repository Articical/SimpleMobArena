package cloud.mineplay.mobarena.website;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Small allowlist parser: duplicate keys and coerced numbers must not acknowledge delivery. */
final class WebsiteAcknowledgement {
    static final int MAXIMUM_BYTES = 16 * 1024;

    private WebsiteAcknowledgement() { }

    static long revision(byte[] bytes, UUID expectedEvent, long sentRevision) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > MAXIMUM_BYTES) throw invalid();
        String json;
        try {
            json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) { throw invalid(); }
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);
            reader.beginObject();
            Set<String> names = new HashSet<>();
            String event = null, result = null, revision = null;
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (!names.add(name) || reader.peek() != JsonToken.STRING) throw invalid();
                String value = reader.nextString();
                switch (name) {
                    case "event_id" -> event = value;
                    case "result" -> result = value;
                    case "stored_revision" -> revision = value;
                    default -> throw invalid();
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT || names.size() != 3
                    || !expectedEvent.toString().equals(event)
                    || !("applied".equals(result) || "duplicate".equals(result) || "stale".equals(result))
                    || revision == null || !revision.matches("[1-9][0-9]{0,18}")) throw invalid();
            long storedRevision = Long.parseLong(revision);
            if (storedRevision < sentRevision) throw invalid();
            return storedRevision;
        } catch (RuntimeException exception) { throw invalid(); }
    }

    private static IOException invalid() { return new IOException("INVALID_ACKNOWLEDGEMENT"); }
}
