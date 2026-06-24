package bank.rdmmesh.publishing.internal.egress;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

/**
 * Минимальный CIDR-матчер (IPv4 + IPv6), без внешних зависимостей
 * (SPEC §3.1 — lean). Сравнение по префиксу байтового представления адреса.
 *
 * <p>E14 round 12 (F4 SSRF-guard): описывает allowlist приватных
 * consumer-сетей, который банк задаёт через
 * {@code RDM_WEBHOOK_EGRESS_PRIVATE_ALLOWLIST}.
 */
public final class Cidr {

    private final byte[] network;
    private final int prefixLen;
    private final String raw;

    private Cidr(byte[] network, int prefixLen, String raw) {
        this.network = network;
        this.prefixLen = prefixLen;
        this.raw = raw;
    }

    /** Парсит {@code "10.20.0.0/16"} либо {@code "fc00::/7"}. */
    public static Cidr parse(String spec) {
        String s = spec.trim();
        int slash = s.indexOf('/');
        if (slash < 0) {
            throw new IllegalArgumentException("CIDR без префикса: " + spec);
        }
        InetAddress base;
        try {
            base = InetAddress.getByName(s.substring(0, slash));
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("невалидный адрес в CIDR: " + spec, e);
        }
        int prefix;
        try {
            prefix = Integer.parseInt(s.substring(slash + 1).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("невалидный префикс в CIDR: " + spec, e);
        }
        byte[] addr = base.getAddress();
        int maxBits = addr.length * 8;
        if (prefix < 0 || prefix > maxBits) {
            throw new IllegalArgumentException(
                    "префикс " + prefix + " вне диапазона для " + spec);
        }
        return new Cidr(maskTo(addr, prefix), prefix, s);
    }

    /** true, если {@code ip} попадает в эту сеть (семейство адресов должно совпасть). */
    public boolean contains(InetAddress ip) {
        byte[] a = ip.getAddress();
        if (a.length != network.length) {
            return false; // v4 vs v6 — разные семейства
        }
        return Arrays.equals(maskTo(a, prefixLen), network);
    }

    private static byte[] maskTo(byte[] addr, int prefixLen) {
        byte[] out = addr.clone();
        for (int i = 0; i < out.length; i++) {
            int bitsForByte = prefixLen - i * 8;
            if (bitsForByte >= 8) {
                continue; // байт целиком в префиксе
            }
            if (bitsForByte <= 0) {
                out[i] = 0; // байт целиком вне префикса
            } else {
                int mask = (0xFF << (8 - bitsForByte)) & 0xFF;
                out[i] = (byte) (out[i] & mask);
            }
        }
        return out;
    }

    @Override
    public String toString() {
        return raw;
    }
}
