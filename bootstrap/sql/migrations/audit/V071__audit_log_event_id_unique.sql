-- V071: idempotency для audit subscriber'а.
--
-- audit-сервис подписан на DomainEvent.class и пишет каждое событие в audit_log.
-- SyncEventBus синхронен и доставляет событие один раз, но при ручных re-trigger'ах
-- (например, V1+ feature «admin replay» или nightly reconciliation) одинаковый
-- event_id может прийти повторно. Уникальный индекс + ON CONFLICT DO NOTHING в
-- INSERT-е audit-сервиса гарантирует, что в журнале одной и той же записи дважды
-- не появится.
--
-- Пара (event_id, event_type), а не одиночный event_id: event_id генерится
-- независимо в каждом publisher'е (workflow / publishing / ownership), и хотя
-- коллизия UUID в практике невозможна, явная дискриминация по типу делает индекс
-- семантически корректным и гарантирует, что вычислимая логика
-- «is_already_audited(event)» работает по полной композиции ключа.

CREATE UNIQUE INDEX IF NOT EXISTS audit_log_event_id_uq
    ON audit.audit_log (event_id, event_type);
