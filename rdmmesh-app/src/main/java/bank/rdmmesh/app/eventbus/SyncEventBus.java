package bank.rdmmesh.app.eventbus;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bank.rdmmesh.api.eventbus.DomainEvent;
import bank.rdmmesh.api.eventbus.EventBus;

/**
 * Минимальный синхронный in-process pub/sub для {@link EventBus}. Подписчики
 * вызываются последовательно в потоке publisher'а. Если кто-то бросит исключение —
 * остальные подписчики всё равно получают событие (исключение логируется), а
 * publisher'у возвращается результат успешно (доставка ≠ обработка).
 *
 * <p>Эпик E5 пока ничем сюда не подписывается извне; {@code rdmmesh-audit} (E10)
 * подпишется на {@link DomainEvent} полностью, {@code rdmmesh-publishing} (E6) —
 * на {@code WorkflowTransitionEvent} с фильтром по {@code to=PUBLISHED}.
 *
 * <p>Расположение в {@code rdmmesh-app}: бизнес-модулям достаточно интерфейса
 * {@code EventBus} из {@code rdmmesh-api}; конкретная реализация — деталь
 * composition root'а и не должна тащить за собой dropwizard / metrics / etc.
 */
public final class SyncEventBus implements EventBus {

    private static final Logger log = LoggerFactory.getLogger(SyncEventBus.class);

    private final Map<Class<? extends DomainEvent>, List<Consumer<? extends DomainEvent>>> subs =
            new ConcurrentHashMap<>();

    @Override
    @SuppressWarnings("unchecked")
    public <E extends DomainEvent> void publish(E event) {
        // Доставка по точному типу + по всем зарегистрированным супертипам
        // (DomainEvent.class — глобальная подписка, удобна аудиту).
        for (Map.Entry<Class<? extends DomainEvent>, List<Consumer<? extends DomainEvent>>> e : subs.entrySet()) {
            if (!e.getKey().isAssignableFrom(event.getClass())) continue;
            for (Consumer<? extends DomainEvent> raw : e.getValue()) {
                try {
                    ((Consumer<E>) raw).accept(event);
                } catch (RuntimeException ex) {
                    log.warn("eventbus: subscriber failed for {} ({}): {}",
                            event.getClass().getSimpleName(), event.eventId(), ex.toString());
                }
            }
        }
    }

    @Override
    public <E extends DomainEvent> Subscription subscribe(Class<E> type, Consumer<E> handler) {
        List<Consumer<? extends DomainEvent>> list =
                subs.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>());
        list.add(handler);
        return () -> list.remove(handler);
    }
}
