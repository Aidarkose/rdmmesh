-- V041: webhook_outbox.payload — переключение jsonb → text.
--
-- Семантика outbox: байты payload'а ДОЛЖНЫ доставляться consumer'у байт-в-байт,
-- потому что HMAC-подпись (publishing.webhook_outbox.signature) считается над
-- исходными UTF-8-байтами JSON. PostgreSQL JSONB нормализует представление
-- при записи (сортировка ключей, удаление whitespace, дедуп) — после round-trip
-- в БД байты НЕ совпадают с теми, по которым считалась подпись, и receiver
-- получает signature mismatch.
--
-- Нам не нужны индексы / JSONB-операторы по payload'у (это очередь для
-- worker'а, не аналитический объект), поэтому простая переключка на text
-- решает проблему без потери функциональности.

ALTER TABLE publishing.webhook_outbox
    ALTER COLUMN payload TYPE text USING payload::text;
