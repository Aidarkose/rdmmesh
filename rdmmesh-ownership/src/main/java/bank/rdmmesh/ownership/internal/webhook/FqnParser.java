package bank.rdmmesh.ownership.internal.webhook;

import java.util.Optional;

/**
 * Парсер OM FQN для table-events. Контракт ingestion-коннектора (SPEC §3.6): FQN таблиц —
 * {@code rdmmesh.<domain_name>.<codeset_name>}. Ничего, кроме этого префикса, RDM не
 * признаёт — webhook'и, которые приходят без префикса {@code rdmmesh.}, не относятся к
 * RDM-таблицам и должны быть проигнорированы.
 *
 * <p>OM в принципе допускает FQN с quoted-сегментами (если имя содержит точку), но в нашей
 * модели {@code domain.name}/{@code codeset.name} соответствует regexp
 * {@code ^[a-z][a-z0-9_]{0,63}$} (см. CHECK в V010), поэтому quoting не возникает.
 */
public final class FqnParser {

    private static final String EXPECTED_PREFIX = "rdmmesh";

    private FqnParser() {}

    public static Optional<TableFqn> parseTable(String fqn) {
        if (fqn == null || fqn.isBlank()) return Optional.empty();
        String[] parts = fqn.split("\\.", -1);
        if (parts.length != 3) return Optional.empty();
        if (!EXPECTED_PREFIX.equals(parts[0])) return Optional.empty();
        if (parts[1].isEmpty() || parts[2].isEmpty()) return Optional.empty();
        return Optional.of(new TableFqn(parts[1], parts[2]));
    }

    public record TableFqn(String domainName, String codesetName) {}
}
