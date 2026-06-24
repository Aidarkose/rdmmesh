# Runbook — ротация HMAC-ключей (zero-downtime)

> **Аудитория.** Оператор / DevOps, выполняющий плановую или экстренную ротацию
> HMAC-секретов rdmmesh. Введено в **E14 round 6**.
>
> **Принцип.** Подписываем всегда *primary*-ключом; проверяем против *любого* из
> accepted-ключей (`primary` + опциональный `previous`). Это даёт overlap-окно,
> в котором валидны и старый, и новый ключ — поэтому контрагента (OM Event
> Subscription, consumer-системы) можно переключать без простоя.

---

## 1. Три независимых HMAC-секрета

| Назначение | Env-var (primary) | Overlap-var (previous) | Кто подписывает | Кто проверяет |
|---|---|---|---|---|
| **E6** approval_signature published-версий | `RDM_HMAC_KEY` | `RDM_HMAC_KEY_PREVIOUS` | rdmmesh (на publish) | rdmmesh verify-endpoint¹ |
| **E7** inbound OM webhook | `RDM_OM_WEBHOOK_HMAC_KEY` | `RDM_OM_WEBHOOK_HMAC_KEY_PREVIOUS` | OpenMetadata Event Subscription | **rdmmesh** (`HmacVerifier`) |
| **E9** outbound webhook (per-subscription) | `RDM_WEBHOOK_KEY_<ID>` (фолбэк `RDM_WEBHOOK_KEY_DEFAULT`) | `RDM_WEBHOOK_KEY_<ID>_PREVIOUS` (фолбэк `RDM_WEBHOOK_KEY_DEFAULT_PREVIOUS`) | rdmmesh (на enqueue) | consumer-система |

Все ключи — ≥ 32 байт UTF-8. Источник в проде — Vault/SOPS (адаптер
подменяется, env-имена сохраняются как pointer-семантика).

¹ **Важно (E6 ограничение).** Полная HMAC-перепроверка *исторических*
`approval_signature` в `GET /versions/{id}/verify` пока **не реализована**
(handoff E6 §3 #3): подписанный ISO-timestamp в БД не сохраняется
(`published_at` = SQL `now()` ≠ подписанный `Instant.now()`), поэтому точную
подписанную строку воспроизвести нельзя без изменения замороженной E6-формулы
+ миграции. Round 6 даёт rotation-примитив (`acceptedHmacKeys()`), но его
включение для E6-историч-verify — отдельный follow-up. **Практический вывод:**
ротация `RDM_HMAC_KEY` безопасна для *новых* publish'ей; verify-endpoint
сегодня и так проверяет только `content_hash` (SHA-256), не подпись.

---

## 2. Процедура ротации `RDM_OM_WEBHOOK_HMAC_KEY` (наиболее критичная)

Это единственный ключ, который проверяет **rdmmesh** и подписывает **внешняя
система (OM)** — ротация без overlap привела бы к отклонению всех webhook'ов.

1. **Сгенерировать** новый ключ (≥32 байт, CSPRNG):
   `openssl rand -base64 48`.
2. **Развернуть overlap.** Выставить:
   - `RDM_OM_WEBHOOK_HMAC_KEY` = **новый** ключ;
   - `RDM_OM_WEBHOOK_HMAC_KEY_PREVIOUS` = **старый** ключ.
   Перезапустить/rolling-restart `rdmmesh-service`. Теперь RDM принимает
   подписи под **обоими** ключами (`HmacVerifier` перебирает
   `acceptedHmacKeys()`).
3. **Переключить OM.** В OpenMetadata Event Subscription, бьющей в
   `/api/v1/webhooks/om/ownership`, заменить secret на **новый** ключ.
4. **Проверить.** Дождаться/спровоцировать domain- или table-событие; в логах
   `ownership-webhook: ... APPLIED` и HTTP 200 (не 401).
5. **Снять overlap.** Удалить `RDM_OM_WEBHOOK_HMAC_KEY_PREVIOUS`,
   перезапустить. Старый ключ больше не принимается.

**Откат (на шаге 2–4):** вернуть `RDM_OM_WEBHOOK_HMAC_KEY` = старый,
удалить `_PREVIOUS`, перезапустить, вернуть secret в OM.

## 3. Ротация `RDM_HMAC_KEY` (E6 snapshot signing)

Подписывает и проверяет одна система (rdmmesh). Достаточно выставить новый
`RDM_HMAC_KEY` и перезапустить — новые публикации подписываются новым ключом.
`RDM_HMAC_KEY_PREVIOUS` поддерживается портом для будущей историч-verify, но
verify-endpoint её сегодня не использует (см. сноску ¹).

## 4. Ротация `RDM_WEBHOOK_KEY_<ID>` (E9 outbound)

Подпись ставится **один раз на enqueue** и хранится в
`publishing.webhook_outbox.signature`; доставка её не пересчитывает. Проверяет
подпись **consumer**, не RDM.

1. Выставить новый `RDM_WEBHOOK_KEY_<ID>` (+ опционально
   `RDM_WEBHOOK_KEY_<ID>_PREVIOUS` = старый — `resolveAllKeys` его вернёт, если
   позже понадобится inbound-verify).
2. Перезапустить — новые события подписываются новым ключом.
3. Consumer обязан в течение overlap-окна принимать **обе** подписи
   (двух-ключевая verify-фаза — ответственность consumer'а, E9 §3 #4).
4. Согласовать с командой consumer'а длительность окна, затем убрать старый.

---

## 5. Чек-лист

- [ ] Новый ключ ≥ 32 байт, из CSPRNG, в Vault/SOPS.
- [ ] Overlap-var выставлен ДО переключения контрагента.
- [ ] Контрагент (OM / consumer) переключён и проверен на живом событии.
- [ ] Overlap-var снят после подтверждения.
- [ ] Действие зафиксировано в change-log / audit (кто, когда, какой секрет).
