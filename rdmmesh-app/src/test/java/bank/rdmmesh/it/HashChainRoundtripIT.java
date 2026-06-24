package bank.rdmmesh.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

import bank.rdmmesh.api.eventbus.DomainEvent;
import bank.rdmmesh.app.eventbus.SyncEventBus;
import bank.rdmmesh.audit.AuditModule;
import bank.rdmmesh.audit.internal.AuditChainHasher;
import bank.rdmmesh.audit.internal.AuditChainVerifier;
import bank.rdmmesh.audit.internal.dao.AuditLogDao;
import bank.rdmmesh.audit.internal.dao.AuditLogDao.ChainRow;

/**
 * E14 round 9 (backlog §3 #1, приоритет) — write→verify roundtrip hash-chain
 * на <b>реальном</b> Postgres. Закрывает класс «precision drift» (E14.7 §1.0
 * дефект B): {@code AuditService} усекает {@code occurred_at} до µs, Postgres
 * timestamptz хранит µs — если они расходятся, verify пересчитывает другой
 * {@code entry_hash}. Раньше ловилось только ручным compose-smoke'ом
 * (E14.8 §5 — «честная оговорка»); теперь — автотест.
 *
 * <p>Полный путь: {@link SyncEventBus} → {@link AuditModule} (подписывает
 * {@code AuditService}) → INSERT с hash-chain под rdmmesh_app → чтение
 * {@link AuditLogDao#findChainRange} → {@link AuditChainVerifier}.
 */
final class HashChainRoundtripIT extends PostgresIT {

    /** Минимальный generic DomainEvent — classify() даст eventType=simpleName. */
    record ItEvent(UUID eventId, OffsetDateTime occurredAt, String note) implements DomainEvent {}

    @Test
    void chainVerifiesEndToEndThenTamperIsDetected() throws Exception {
        Jdbi jdbi = appJdbi();
        // Плоский mapper (payload-колонка) обязан уметь OffsetDateTime — иначе
        // AuditService.onEvent молча проглотит событие (serialisation fail).
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

        SyncEventBus bus = new SyncEventBus();
        AuditModule.build(jdbi, bus, mapper); // подписывает AuditService на bus

        // БД общая на все IT (singleton-контейнер, round 14). Скоупим тест
        // к СВОЕМУ id-диапазону: запоминаем хвост ДО publish и берём его
        // entry_hash как anchor (ровно его AuditService возьмёт prev_hash'ем
        // первого нашего события). Чужие SQL-строки (V073/Archive — не
        // chain-валидные) с id ≤ before в диапазон не попадают.
        long before = jdbi.withExtension(AuditLogDao.class, AuditLogDao::findMaxId)
                .orElse(0L);
        String anchor = before == 0L
                ? null
                : jdbi.withExtension(AuditLogDao.class, d -> d.findChainRange(before, before))
                        .get(0)
                        .entryHash();

        for (int i = 0; i < 4; i++) {
            bus.publish(new ItEvent(UUID.randomUUID(), OffsetDateTime.now(), "evt-" + i));
        }

        long max = jdbi.withExtension(AuditLogDao.class, AuditLogDao::findMaxId)
                .orElseThrow();
        List<ChainRow> rows =
                jdbi.withExtension(AuditLogDao.class, d -> d.findChainRange(before + 1, max));

        assertThat(rows).as("4 опубликованных события записаны в chain").hasSize(4);

        AuditChainVerifier verifier =
                new AuditChainVerifier(new AuditChainHasher(AuditChainHasher.defaultMapper()));

        AuditChainVerifier.Result ok = verifier.verify(rows, anchor);
        assertThat(ok.verified())
                .as("нетронутая цепочка верифицируется (precision-drift не воспроизводится)")
                .isTrue();
        assertThat(ok.checked()).isEqualTo(4);

        // Tamper: ломаем payload_canonical у 2-й строки (integrity-recompute
        // обязан разойтись именно на ней). Триггеры append-only временно
        // снимаем под суперюзером (rdmmesh_app не смог бы — REVOKE/триггеры).
        long tamperId = rows.get(1).id();
        try (Connection c = adminConnection(); Statement st = c.createStatement()) {
            st.execute("ALTER TABLE audit.audit_log DISABLE TRIGGER audit_log_no_update");
            st.execute("UPDATE audit.audit_log SET payload_canonical='{\"tampered\":true}' "
                    + "WHERE id=" + tamperId);
            st.execute("ALTER TABLE audit.audit_log ENABLE TRIGGER audit_log_no_update");
        }

        List<ChainRow> rows2 =
                jdbi.withExtension(AuditLogDao.class, d -> d.findChainRange(before + 1, max));
        AuditChainVerifier.Result broken = verifier.verify(rows2, anchor);

        assertThat(broken.verified()).isFalse();
        assertThat(broken.firstBrokenAt()).isEqualTo(tamperId);
        assertThat(broken.reason()).contains("entry_hash mismatch");
    }
}
