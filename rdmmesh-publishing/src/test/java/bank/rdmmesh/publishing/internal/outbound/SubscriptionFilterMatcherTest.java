package bank.rdmmesh.publishing.internal.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import bank.rdmmesh.publishing.internal.outbound.SubscriptionFilterMatcher.Filter;

class SubscriptionFilterMatcherTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void empty_filter_matches_anything() {
        Filter f = Filter.empty();
        assertThat(SubscriptionFilterMatcher.matches(f, "risk", "ifrs9_stages", "VERSION_PUBLISHED")).isTrue();
        assertThat(SubscriptionFilterMatcher.matches(f, null, null, "VERSION_PUBLISHED")).isTrue();
    }

    @Test
    void null_filter_matches_anything() {
        assertThat(SubscriptionFilterMatcher.matches(null, "risk", "x", "VERSION_PUBLISHED")).isTrue();
    }

    @Test
    void domains_restrict() {
        Filter f = new Filter(List.of("risk"), List.of(), List.of());
        assertThat(SubscriptionFilterMatcher.matches(f, "risk", "x", "VERSION_PUBLISHED")).isTrue();
        assertThat(SubscriptionFilterMatcher.matches(f, "treasury", "x", "VERSION_PUBLISHED")).isFalse();
    }

    @Test
    void codesets_restrict() {
        Filter f = new Filter(List.of(), List.of("ifrs9_stages"), List.of());
        assertThat(SubscriptionFilterMatcher.matches(f, "risk", "ifrs9_stages", "VERSION_PUBLISHED")).isTrue();
        assertThat(SubscriptionFilterMatcher.matches(f, "risk", "country_iso", "VERSION_PUBLISHED")).isFalse();
    }

    @Test
    void events_restrict() {
        Filter f = new Filter(List.of(), List.of(), List.of("VERSION_DEPRECATED"));
        assertThat(SubscriptionFilterMatcher.matches(f, "x", "y", "VERSION_PUBLISHED")).isFalse();
        assertThat(SubscriptionFilterMatcher.matches(f, "x", "y", "VERSION_DEPRECATED")).isTrue();
    }

    @Test
    void or_within_field_and_within_filter() {
        Filter f = new Filter(List.of("risk", "treasury"), List.of(), List.of());
        assertThat(SubscriptionFilterMatcher.matches(f, "risk", "x", "VERSION_PUBLISHED")).isTrue();
        assertThat(SubscriptionFilterMatcher.matches(f, "treasury", "x", "VERSION_PUBLISHED")).isTrue();
        assertThat(SubscriptionFilterMatcher.matches(f, "compliance", "x", "VERSION_PUBLISHED")).isFalse();
    }

    @Test
    void parse_empty_or_null_returns_empty_filter() {
        assertThat(SubscriptionFilterMatcher.parse(json, null).domains()).isEmpty();
        assertThat(SubscriptionFilterMatcher.parse(json, "").domains()).isEmpty();
        assertThat(SubscriptionFilterMatcher.parse(json, "{}").events()).isEmpty();
    }

    @Test
    void parse_full_filter() {
        Filter f = SubscriptionFilterMatcher.parse(json,
                "{\"domains\":[\"risk\"],\"codesets\":[\"a\",\"b\"],\"events\":[\"VERSION_PUBLISHED\"]}");
        assertThat(f.domains()).containsExactly("risk");
        assertThat(f.codesets()).containsExactly("a", "b");
        assertThat(f.events()).containsExactly("VERSION_PUBLISHED");
    }

    @Test
    void parse_garbage_returns_empty_filter() {
        // Намеренный fail-safe: лучше доставить, чем тихо потерять событие при битой
        // конфигурации subscription'а.
        Filter f = SubscriptionFilterMatcher.parse(json, "this is not json");
        assertThat(f.domains()).isEmpty();
        assertThat(f.codesets()).isEmpty();
        assertThat(f.events()).isEmpty();
    }
}
