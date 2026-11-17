package com.oficina.billing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Signature verification only; callers must also deduplicate and query the provider. */
final class MercadoPagoWebhookSignature {
    private MercadoPagoWebhookSignature() { }

    static boolean verify(String secret, String signature, String requestId, String dataId) {
        if (secret == null || secret.isBlank() || signature == null || signature.length() > 512
                || requestId == null || requestId.isBlank() || requestId.length() > 200
                || dataId == null || dataId.isBlank() || dataId.length() > 200
                || requestId.indexOf(';') >= 0 || dataId.indexOf(';') >= 0) return false;
        String timestamp = null, hash = null;
        for (String part : signature.split(",")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length != 2) return false;
            if (pair[0].equals("ts")) { if (timestamp != null) return false; timestamp = pair[1]; }
            else if (pair[0].equals("v1")) { if (hash != null) return false; hash = pair[1]; }
        }
        if (timestamp == null || !timestamp.matches("[0-9]{1,20}") || hash == null || !hash.matches("[0-9a-fA-F]{64}")) return false;
        String manifest = "id:" + dataId + ";request-id:" + requestId + ";ts:" + timestamp + ";";
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return MessageDigest.isEqual(mac.doFinal(manifest.getBytes(StandardCharsets.UTF_8)), HexFormat.of().parseHex(hash));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    static boolean isFresh(String signature, java.time.Instant now, long maxAgeSeconds, long futureSkewSeconds) {
        if (signature == null || maxAgeSeconds <= 0 || futureSkewSeconds < 0) return false;
        try {
            String timestamp = null;
            for (String part : signature.split(",")) {
                String[] pair = part.trim().split("=", 2);
                if (pair.length == 2 && pair[0].equals("ts")) {
                    if (timestamp != null) return false;
                    timestamp = pair[1];
                }
            }
            if (timestamp == null || !timestamp.matches("[0-9]{1,16}")) return false;
            long value = Long.parseLong(timestamp);
            java.time.Instant signedAt = timestamp.length() >= 13 ? java.time.Instant.ofEpochMilli(value) : java.time.Instant.ofEpochSecond(value);
            return !signedAt.isBefore(now.minusSeconds(maxAgeSeconds)) && !signedAt.isAfter(now.plusSeconds(futureSkewSeconds));
        } catch (RuntimeException invalid) { return false; }
    }
}
