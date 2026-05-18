package bank.rdmmesh.authoring.internal.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class AttributesValidatorTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID CODESET = UUID.randomUUID();
    private static final int SCHEMA_VERSION = 1;

    private static final String IFRS9_STAGE_SCHEMA = """
            {
              "type": "object",
              "required": ["stage"],
              "properties": {
                "stage": {"type": "string", "enum": ["1", "2", "3"]},
                "pd":    {"type": "number", "minimum": 0, "maximum": 1}
              },
              "additionalProperties": false
            }
            """;

    @Test
    void empty_schema_accepts_anything() {
        AttributesValidator v = new AttributesValidator(JSON);
        List<String> errs = v.validate(CODESET, SCHEMA_VERSION, "{}", Map.of("foo", 42));
        assertThat(errs).isEmpty();
    }

    @Test
    void valid_payload_passes() {
        AttributesValidator v = new AttributesValidator(JSON);
        List<String> errs = v.validate(
                CODESET, SCHEMA_VERSION, IFRS9_STAGE_SCHEMA,
                Map.of("stage", "2", "pd", 0.05));
        assertThat(errs).isEmpty();
    }

    @Test
    void missing_required_field_fails() {
        AttributesValidator v = new AttributesValidator(JSON);
        List<String> errs = v.validate(
                CODESET, SCHEMA_VERSION, IFRS9_STAGE_SCHEMA,
                Map.of("pd", 0.1));
        assertThat(errs).isNotEmpty();
        assertThat(String.join(" ", errs)).contains("stage");
    }

    @Test
    void out_of_enum_value_fails() {
        AttributesValidator v = new AttributesValidator(JSON);
        List<String> errs = v.validate(
                CODESET, SCHEMA_VERSION, IFRS9_STAGE_SCHEMA,
                Map.of("stage", "X"));
        assertThat(errs).isNotEmpty();
    }

    @Test
    void out_of_range_number_fails() {
        AttributesValidator v = new AttributesValidator(JSON);
        List<String> errs = v.validate(
                CODESET, SCHEMA_VERSION, IFRS9_STAGE_SCHEMA,
                Map.of("stage", "1", "pd", 1.5));
        assertThat(errs).isNotEmpty();
    }

    @Test
    void invalid_schema_yields_validation_error_not_500() {
        AttributesValidator v = new AttributesValidator(JSON);
        // Не валидный JSON Schema text — но валидатор не падает.
        List<String> errs = v.validate(
                CODESET, SCHEMA_VERSION, "not-a-json", Map.of("stage", "1"));
        assertThat(errs).isNotEmpty();
        assertThat(errs.get(0)).contains("Schema is invalid");
    }

    @Test
    void cache_handles_revision_invalidation() {
        AttributesValidator v = new AttributesValidator(JSON);
        UUID cs = UUID.randomUUID();
        // Прогрев первого rev'а.
        assertThat(v.validate(cs, 1, IFRS9_STAGE_SCHEMA, Map.of("stage", "1"))).isEmpty();
        // Вызов с другой ревизией не путает кэш.
        String stricter = """
                {"type":"object","required":["stage","pd"],"properties":{
                  "stage":{"type":"string","enum":["1","2","3"]},
                  "pd":{"type":"number","minimum":0,"maximum":1}}}
                """;
        List<String> errs = v.validate(cs, 2, stricter, Map.of("stage", "1"));
        assertThat(errs).isNotEmpty(); // pd теперь required
    }
}
