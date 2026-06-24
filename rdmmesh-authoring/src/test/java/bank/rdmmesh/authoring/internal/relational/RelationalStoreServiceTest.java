package bank.rdmmesh.authoring.internal.relational;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import bank.rdmmesh.api.eventbus.VersionPublishedDomainEvent;
import bank.rdmmesh.authoring.resource.CodeItemDto;
import bank.rdmmesh.spec.events.VersionPublishedEvent;

/**
 * Pure-тесты best-effort гарантий publish-хука {@link RelationalStoreService}
 * (Stage 2-final). Без БД: проверяем, что {@code onVersionPublished} никогда не
 * бросает — иначе сбой реляционного зеркала ронял бы шину/publishing.
 */
class RelationalStoreServiceTest {

    /** jdbi/catalog null — допустимо: все ветки ниже не должны доходить до их использования
     *  без перехвата. */
    private final RelationalStoreService store =
            new RelationalStoreService(null, null, new ObjectMapper());

    @Test
    void onVersionPublished_null_payload_is_noop() {
        VersionPublishedDomainEvent event =
                new VersionPublishedDomainEvent(UUID.randomUUID(), OffsetDateTime.now(), null);
        assertThatCode(() -> store.onVersionPublished(event)).doesNotThrowAnyException();
    }

    @Test
    void onVersionPublished_blank_version_id_is_noop() {
        VersionPublishedDomainEvent event = new VersionPublishedDomainEvent(
                UUID.randomUUID(), OffsetDateTime.now(), new VersionPublishedEvent());
        assertThatCode(() -> store.onVersionPublished(event)).doesNotThrowAnyException();
    }

    @Test
    void onVersionPublished_bad_version_id_is_swallowed() {
        VersionPublishedEvent payload = new VersionPublishedEvent().withVersionId("not-a-uuid");
        VersionPublishedDomainEvent event =
                new VersionPublishedDomainEvent(UUID.randomUUID(), OffsetDateTime.now(), payload);
        assertThatCode(() -> store.onVersionPublished(event)).doesNotThrowAnyException();
    }

    @Test
    void onVersionPublished_backend_error_is_swallowed() {
        // Валидный version_id, но jdbi == null → внутренний сбой должен быть поглощён
        // (best-effort), а не выброшен наружу в шину.
        VersionPublishedEvent payload =
                new VersionPublishedEvent().withVersionId(UUID.randomUUID().toString());
        VersionPublishedDomainEvent event =
                new VersionPublishedDomainEvent(UUID.randomUUID(), OffsetDateTime.now(), payload);
        assertThatCode(() -> store.onVersionPublished(event)).doesNotThrowAnyException();
    }

    // ── read-path projection (Stage 3) ────────────────────────────────────────────

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void projectRow_reconstructs_canonical_dto() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("branch_id", "001");
        row.put("branch_sgmnt_id", 42L);          // атрибут (bigint)
        row.put("pd", 0.5);                        // атрибут (double)
        row.put("label_ru", "Отделение 001");
        row.put("label_en", "Branch 001");
        row.put("description_ru", "опорное");
        row.put("order_index", 7);
        row.put("parent_key", "[\"000\"]");        // jsonb-текст
        row.put("parent_ref", "{\"codeset\":\"x\",\"key\":[\"9\"]}");  // jsonb cross-ref
        row.put("status", "ACTIVE");
        row.put("effective_from", "2026-01-01");
        row.put("system_from", "2026-06-10T12:00:00Z");

        UUID version = UUID.randomUUID();
        CodeItemDto dto = RelationalStoreService.projectRow(
                List.of("branch_id"), List.of("branch_sgmnt_id", "pd"), row, version, JSON);

        assertThat(dto.keyParts()).containsExactly("001");
        assertThat(dto.attributes())
                .containsEntry("branch_sgmnt_id", 42L)
                .containsEntry("pd", 0.5);
        assertThat(dto.labelRu()).isEqualTo("Отделение 001");
        assertThat(dto.descriptionRu()).isEqualTo("опорное");
        assertThat(dto.parentKey()).containsExactly("000");
        assertThat(dto.parentRef()).containsEntry("codeset", "x");
        assertThat(dto.orderIndex()).isEqualTo(7);
        assertThat(dto.status()).isEqualTo("ACTIVE");
        assertThat(dto.effectiveFrom()).isEqualTo("2026-01-01");
        assertThat(dto.systemFrom()).isEqualTo("2026-06-10T12:00:00Z");
        assertThat(dto.versionId()).isEqualTo(version.toString());
        // нет в relational-модели:
        assertThat(dto.id()).isNull();
        assertThat(dto.rowVersion()).isNull();
    }

    @Test
    void projectRow_handles_missing_and_null_fields() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("code", "X");
        row.put("parent_key", null);   // корневой item — parent_key SQL NULL

        CodeItemDto dto = RelationalStoreService.projectRow(
                List.of("code"), List.of(), row, null, JSON);

        assertThat(dto.keyParts()).containsExactly("X");
        assertThat(dto.parentKey()).isNull();
        assertThat(dto.orderIndex()).isNull();
        assertThat(dto.versionId()).isNull();          // current-снапшот без version_id
        assertThat(dto.attributes()).isEmpty();
        assertThat(dto.status()).isNull();
    }

    // ── колоночный diff (Stage 5) ─────────────────────────────────────────────────

    private static Map<String, Object> row(String code, String label, Object pd) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("label_ru", label);
        m.put("pd", pd);
        return m;
    }

    @Test
    void diffRows_detects_added_removed_changed() {
        List<String> keys = List.of("code");
        List<String> cols = List.of("label_ru", "pd");
        List<Map<String, Object>> from = List.of(
                row("A", "Альфа", 0.1),
                row("B", "Бета", 0.2));       // будет CHANGED
        List<Map<String, Object>> to = List.of(
                row("B", "Бета", 0.9),         // pd изменился
                row("C", "Гамма", 0.3));       // ADDED; A — REMOVED

        RelationalStoreService.RelDiffSummary d =
                RelationalStoreService.diffRows(keys, cols, from, to);

        assertThat(d.added()).isEqualTo(1);
        assertThat(d.removed()).isEqualTo(1);
        assertThat(d.changed()).isEqualTo(1);
        assertThat(d.entries())
                .anySatisfy(e -> {
                    assertThat(e.op()).isEqualTo("ADDED");
                    assertThat(e.keyParts()).containsExactly("C");
                })
                .anySatisfy(e -> {
                    assertThat(e.op()).isEqualTo("REMOVED");
                    assertThat(e.keyParts()).containsExactly("A");
                })
                .anySatisfy(e -> {
                    assertThat(e.op()).isEqualTo("CHANGED");
                    assertThat(e.keyParts()).containsExactly("B");
                    assertThat(e.changedColumns()).containsExactly("pd");
                });
    }

    @Test
    void diffRows_identical_sets_have_no_changes() {
        List<String> keys = List.of("code");
        List<String> cols = List.of("label_ru", "pd");
        List<Map<String, Object>> rows = List.of(row("A", "Альфа", 0.1));
        RelationalStoreService.RelDiffSummary d =
                RelationalStoreService.diffRows(keys, cols, rows, rows);
        assertThat(d.added()).isZero();
        assertThat(d.removed()).isZero();
        assertThat(d.changed()).isZero();
        assertThat(d.entries()).isEmpty();
    }
}
