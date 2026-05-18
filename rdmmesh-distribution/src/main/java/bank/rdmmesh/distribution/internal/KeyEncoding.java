package bank.rdmmesh.distribution.internal;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Decode base64url(JSON array) ключа из path-параметра lookup-эндпоинта. Тот же формат,
 * что генерирует {@code rdmmesh-authoring} — но дублируется здесь, чтобы distribution
 * остался изолированным bounded context'ом (SPEC §3.3, ArchUnit
 * {@code modules_do_not_depend_on_each_other}).
 */
public final class KeyEncoding {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<String>> LIST = new TypeReference<>() {};

    private KeyEncoding() {}

    public static List<String> decode(String token) {
        if (token == null || token.isEmpty()) {
            throw new IllegalArgumentException("пустой key-token");
        }
        try {
            byte[] raw = Base64.getUrlDecoder().decode(token);
            return JSON.readValue(new String(raw, StandardCharsets.UTF_8), LIST);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("неверный base64url key-token: " + token, e);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "key-token должен быть base64url(JSON-array), получено: " + token, e);
        }
    }

    /** Re-serialise list → JSON-array string без пробелов — для передачи в DAO как jsonb. */
    public static String toJsonArray(List<String> keyParts) {
        try {
            return JSON.writeValueAsString(keyParts);
        } catch (Exception e) {
            throw new IllegalStateException("cannot serialise key_parts", e);
        }
    }
}
