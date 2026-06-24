package bank.rdmmesh.publishing.internal.outbound;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bank.rdmmesh.publishing.internal.egress.EgressBlockedException;
import bank.rdmmesh.publishing.internal.egress.EgressPolicy;
import bank.rdmmesh.publishing.internal.outbound.dao.SubscriptionDao;
import bank.rdmmesh.publishing.internal.outbound.dao.SubscriptionDao.SubscriptionRow;
import bank.rdmmesh.publishing.internal.outbound.dao.WebhookOutboxDao;
import bank.rdmmesh.publishing.internal.outbound.dao.WebhookOutboxDao.OutboxRow;
import io.dropwizard.lifecycle.Managed;

/**
 * Background-доставщик outbound webhook'ов. Dropwizard {@link Managed} стартует/остановит
 * вместе с сервисом.
 *
 * <p>Цикл работы (раз в {@link #tickInterval()} секунд):
 * <ol>
 *   <li>Открывает транзакцию, забирает up-to-{@link #BATCH_SIZE} due-строк через
 *       {@code FOR UPDATE SKIP LOCKED} (таким образом другая реплика их не возьмёт).</li>
 *   <li>Для каждой строки делает HTTP-POST на {@code subscription.url}; success
 *       (2xx) → {@code markDelivered}, failure → {@code markRetry} с backoff'ом или
 *       {@code markGivenUp} после {@link Backoff#MAX_ATTEMPTS} попыток.</li>
 *   <li>Транзакция коммитится по завершении пачки.</li>
 * </ol>
 *
 * <p><b>Замечание по lock'у:</b> POST'ы выполняются ВНУТРИ транзакции, пока row-locks
 * удерживаются. На пилоте N=10 × timeout=10s = max 100s lock — приемлемо. Если worker
 * займёт несколько реплик в V1+ — переходим на lease-based подход (UPDATE
 * {@code next_attempt_at} в отдельной короткой tx, чтобы lock освобождался сразу).
 */
public final class WebhookDeliveryWorker implements Managed {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryWorker.class);

    /** Сколько событий за один tick. Маленькие пачки — короче lock. */
    public static final int BATCH_SIZE = 10;

    /** Connect timeout HTTP-клиента. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** Request timeout HTTP-клиента. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final Jdbi jdbi;
    private final HttpClient http;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final long tickSeconds;
    private final EgressPolicy egress;

    public WebhookDeliveryWorker(Jdbi jdbi, long tickSeconds, EgressPolicy egress) {
        this.jdbi = jdbi;
        this.tickSeconds = tickSeconds;
        this.egress = egress;
        this.http = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rdmmesh-webhook-delivery");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) return;
        log.info("webhook-delivery: starting (tick={}s, batch={})", tickSeconds, BATCH_SIZE);
        executor.scheduleWithFixedDelay(this::safeTick, tickSeconds, tickSeconds, TimeUnit.SECONDS);
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        log.info("webhook-delivery: stopping");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    public long tickInterval() {
        return tickSeconds;
    }

    /** Обёртка, чтобы исключения не убивали ScheduledExecutor'у задачу. */
    void safeTick() {
        try {
            drainOnce();
        } catch (RuntimeException e) {
            log.warn("webhook-delivery tick error: {}", e.toString(), e);
        }
    }

    /** Один проход worker'а. Доступно для unit/IT-тестов. */
    public void drainOnce() {
        jdbi.useTransaction(handle -> {
            WebhookOutboxDao outbox = handle.attach(WebhookOutboxDao.class);
            SubscriptionDao subs = handle.attach(SubscriptionDao.class);
            List<OutboxRow> due = outbox.claimDue(BATCH_SIZE);
            if (due.isEmpty()) return;

            for (OutboxRow row : due) {
                SubscriptionRow s = subs.findById(row.subscriptionId()).orElse(null);
                if (s == null) {
                    // subscription удалена жёстко (ON DELETE CASCADE); строка outbox
                    // уже удалилась бы — попадание сюда невозможно. Но закроем
                    // give-up-семантикой на всякий случай.
                    outbox.markGivenUp(row.id(), Instant.now(), "GIVE_UP: subscription not found");
                    continue;
                }
                if (!s.active()) {
                    // active=false: владелец деактивировал, не пытаемся доставлять.
                    outbox.markGivenUp(row.id(), Instant.now(),
                            "GIVE_UP: subscription " + s.id() + " is inactive");
                    continue;
                }

                deliver(row, s, outbox, subs);
            }
        });
    }

    private void deliver(OutboxRow row, SubscriptionRow s,
                         WebhookOutboxDao outbox, SubscriptionDao subs) {
        URI uri;
        try {
            uri = URI.create(s.url());
        } catch (IllegalArgumentException e) {
            outbox.markGivenUp(row.id(), Instant.now(),
                    "GIVE_UP: invalid url: " + s.url());
            return;
        }

        // F4 SSRF-guard (E14 round 12): авторитетная DNS-rebinding-safe
        // проверка — резолв host'а здесь, прямо перед connect. Блок =
        // GIVE_UP без retry: повтор на тот же запрещённый адрес бессмыслен.
        try {
            egress.check(uri.getHost());
        } catch (EgressBlockedException blocked) {
            outbox.markGivenUp(row.id(), Instant.now(),
                    "GIVE_UP: " + blocked.getMessage());
            subs.markDelivery(s.id(), Instant.now(), "FAILED");
            log.warn("webhook-delivery: SSRF-BLOCKED subscription={} event={}: {}",
                    s.id(), row.eventId(), blocked.getMessage());
            return;
        }

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("X-RDM-Event-Id", row.eventId().toString())
                .header("X-RDM-Event-Type", row.eventType())
                .header(WebhookHmac.HEADER, WebhookHmac.headerValue(row.signature()))
                .POST(HttpRequest.BodyPublishers.ofString(row.payloadJson()))
                .build();

        int attempts = row.attempts() + 1; // эта попытка
        Instant now = Instant.now();
        try {
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = resp.statusCode();
            if (status >= 200 && status < 300) {
                outbox.markDelivered(row.id(), now);
                subs.markDelivery(s.id(), now, "OK");
                log.info("webhook-delivery: OK subscription={} event={} attempts={} status={}",
                        s.id(), row.eventId(), attempts, status);
            } else {
                handleFailure(row, s, outbox, subs, attempts,
                        "HTTP " + status + ": " + truncate(resp.body(), 256));
            }
        } catch (Exception e) {
            // InterruptedException, IOException, HttpTimeoutException и т.п.
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            handleFailure(row, s, outbox, subs, attempts,
                    e.getClass().getSimpleName() + ": " + truncate(String.valueOf(e.getMessage()), 256));
        }
    }

    private void handleFailure(OutboxRow row, SubscriptionRow s,
                               WebhookOutboxDao outbox, SubscriptionDao subs,
                               int attempts, String error) {
        Instant now = Instant.now();
        if (Backoff.exhausted(attempts)) {
            outbox.markGivenUp(row.id(), now,
                    "GIVE_UP after " + attempts + " attempts: " + error);
            subs.markDelivery(s.id(), now, "FAILED");
            log.warn("webhook-delivery: GIVE_UP subscription={} event={} after={} attempts: {}",
                    s.id(), row.eventId(), attempts, error);
        } else {
            Duration delay = Backoff.nextDelay(attempts);
            outbox.markRetry(row.id(), now.plus(delay), error);
            subs.markDelivery(s.id(), now, "RETRYING");
            log.info("webhook-delivery: RETRY subscription={} event={} attempt={} next_in={}: {}",
                    s.id(), row.eventId(), attempts, delay, error);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
