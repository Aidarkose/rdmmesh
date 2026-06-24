package bank.rdmmesh.authoring.internal;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Композитный ключ — это {@code List<String>}. В путях REST-ресурсов
 * {@code /versions/{id}/items/{key}} нужно передать его строкой; вариант
 * «джойн через дефис» небезопасен, потому что в значениях ключей могут быть
 * любые символы. Поэтому у нас — {@code base64url(JSON.stringify(keyParts))}.
 *
 * <p>Дополнительно: URL-формы {@code .../items/by-key?key=...} тоже принимают тот же
 * base64url-blob. Это даёт идемпотентные lookup'ы и стабильные URL'ы при копи/паст.
 */
public final class KeyEncoding {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<String>> LIST = new TypeReference<>() {};

    private KeyEncoding() {}

    public static String encode(List<String> keyParts) {
        try {
            byte[] payload = JSON.writeValueAsBytes(keyParts);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise key_parts", e);
        }
    }

    public static List<String> decode(String token) {
        if (token == null || token.isEmpty()) {
            throw new IllegalArgumentException("Empty key token");
        }
        try {
            byte[] raw = Base64.getUrlDecoder().decode(token);
            return JSON.readValue(new String(raw, StandardCharsets.UTF_8), LIST);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Bad base64url key token: " + token, e);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Bad key token (expecting base64url(JSON array)): " + token, e);
        }
    }
}
