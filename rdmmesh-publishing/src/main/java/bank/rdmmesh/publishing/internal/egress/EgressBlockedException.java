package bank.rdmmesh.publishing.internal.egress;

/**
 * Адресат webhook'а запрещён egress-политикой (F4 SSRF-guard, E14 round 12).
 * Не retryable: повторная доставка на тот же запрещённый host бессмысленна.
 */
public final class EgressBlockedException extends RuntimeException {
    public EgressBlockedException(String message) {
        super(message);
    }
}
