package bank.rdmmesh.ownership.internal.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FqnParserTest {

    @Test
    void parses_three_segment_fqn() {
        var parsed = FqnParser.parseTable("rdmmesh.risk.ifrs9_stages");
        assertThat(parsed).isPresent();
        assertThat(parsed.get().domainName()).isEqualTo("risk");
        assertThat(parsed.get().codesetName()).isEqualTo("ifrs9_stages");
    }

    @Test
    void rejects_wrong_prefix() {
        // Только rdmmesh-namespace принимается; что-нибудь чужое (другой database service в OM) —
        // не наша таблица, webhook'у она не интересна.
        assertThat(FqnParser.parseTable("warehouse.risk.ifrs9_stages")).isEmpty();
    }

    @Test
    void rejects_two_segment_fqn() {
        assertThat(FqnParser.parseTable("rdmmesh.risk")).isEmpty();
    }

    @Test
    void rejects_four_segment_fqn() {
        assertThat(FqnParser.parseTable("rdmmesh.risk.ifrs9.extra")).isEmpty();
    }

    @Test
    void rejects_empty_or_null() {
        assertThat(FqnParser.parseTable(null)).isEmpty();
        assertThat(FqnParser.parseTable("")).isEmpty();
        assertThat(FqnParser.parseTable("   ")).isEmpty();
    }

    @Test
    void rejects_empty_segments() {
        assertThat(FqnParser.parseTable("rdmmesh..ifrs9_stages")).isEmpty();
        assertThat(FqnParser.parseTable("rdmmesh.risk.")).isEmpty();
    }
}
