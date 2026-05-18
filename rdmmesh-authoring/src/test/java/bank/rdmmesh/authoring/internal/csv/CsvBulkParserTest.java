package bank.rdmmesh.authoring.internal.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class CsvBulkParserTest {

    private final CsvBulkParser parser = new CsvBulkParser(new ObjectMapper());

    @Test
    void parses_single_keyed_csv_with_label_columns() throws IOException {
        String csv = """
                key_parts,label_ru,label_en
                KZ,Казахстан,Kazakhstan
                RU,Россия,Russia
                """;
        List<CsvBulkParser.Row> rows = parser.parse(stream(csv));
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).keyParts()).containsExactly("KZ");
        assertThat(rows.get(0).labelRu()).isEqualTo("Казахстан");
        assertThat(rows.get(0).labelEn()).isEqualTo("Kazakhstan");
    }

    @Test
    void parses_composite_keyed_csv_via_json_array() throws IOException {
        String csv = """
                key_parts,attr.pd
                "[""RETAIL"",""BB"",""12M""]",0.05
                """;
        List<CsvBulkParser.Row> rows = parser.parse(stream(csv));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).keyParts()).containsExactly("RETAIL", "BB", "12M");
        // attr.* колонки сохраняются как строки — числовая коэрция отключена,
        // чтобы не ломать enum'ы типа "stage"="1". Для чисел используется JSON-attributes.
        assertThat(rows.get(0).attributes()).containsEntry("pd", "0.05");
    }

    @Test
    void parses_inline_attributes_json() throws IOException {
        String csv = """
                key_parts,attributes
                "1","{""stage"":""1"",""pd"":0.01}"
                """;
        List<CsvBulkParser.Row> rows = parser.parse(stream(csv));
        assertThat(rows.get(0).attributes()).containsEntry("stage", "1");
        assertThat(rows.get(0).attributes()).containsEntry("pd", 0.01);
    }

    @Test
    void coerces_only_booleans_others_stay_strings() throws IOException {
        String csv = """
                key_parts,attr.is_active,attr.count,attr.note
                A,true,42,hello
                """;
        var attrs = parser.parse(stream(csv)).get(0).attributes();
        assertThat(attrs).containsEntry("is_active", Boolean.TRUE);
        // Числа осознанно НЕ распознаются — иначе ломаются enum'ы.
        assertThat(attrs).containsEntry("count", "42");
        assertThat(attrs).containsEntry("note", "hello");
    }

    @Test
    void parses_dates_and_indexes() throws IOException {
        String csv = """
                key_parts,order_index,effective_from,effective_to
                X,3,2026-01-01,2026-12-31
                """;
        var row = parser.parse(stream(csv)).get(0);
        assertThat(row.orderIndex()).isEqualTo(3);
        assertThat(row.effectiveFrom()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(row.effectiveTo()).isEqualTo(LocalDate.of(2026, 12, 31));
    }

    @Test
    void rejects_missing_key_parts_column() {
        String csv = """
                label_ru
                Foo
                """;
        assertThatThrownBy(() -> parser.parse(stream(csv)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key_parts");
    }

    private static InputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
