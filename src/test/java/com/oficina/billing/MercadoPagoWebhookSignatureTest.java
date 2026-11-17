package com.oficina.billing;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class MercadoPagoWebhookSignatureTest {
    private static final String SECRET = "test-secret-000000000000000000000000";
    private static final String VALID = "ts=1700000000,v1=9645b97052c9c85f19bfff6b84f245c17da7011a2042be2fc81d30c1b881aac4";
    @Test void verifiesKnownHmacAndRejectsChanges() {
        assertTrue(MercadoPagoWebhookSignature.verify(SECRET, VALID, "req-123", "123456"));
        assertFalse(MercadoPagoWebhookSignature.verify(SECRET, VALID, "req-124", "123456"));
        assertFalse(MercadoPagoWebhookSignature.verify(SECRET, VALID, "req-123", "123457"));
        assertFalse(MercadoPagoWebhookSignature.verify("wrong", VALID, "req-123", "123456"));
        assertFalse(MercadoPagoWebhookSignature.verify(SECRET, VALID.replace("1700000000", "1700000001"), "req-123", "123456"));
    }
    @Test void rejectsMalformedOrAmbiguousHeaders() {
        assertFalse(MercadoPagoWebhookSignature.verify(SECRET, VALID + ",ts=1700000000", "req-123", "123456"));
        assertFalse(MercadoPagoWebhookSignature.verify(SECRET, "ts=1,v1=not-hex", "req-123", "123456"));
        assertFalse(MercadoPagoWebhookSignature.verify(SECRET, null, "req-123", "123456"));
        assertFalse(MercadoPagoWebhookSignature.verify(SECRET, VALID, "req-123;ts:1", "123456"));
        assertFalse(MercadoPagoWebhookSignature.verify(SECRET, VALID, "req-123", ""));
    }
    @Test void enforcesTimestampWindowInSecondsAndMilliseconds() {
        var now = java.time.Instant.ofEpochSecond(1700000000L);
        assertTrue(MercadoPagoWebhookSignature.isFresh("ts=1700000000,v1=x", now, 900, 60));
        assertTrue(MercadoPagoWebhookSignature.isFresh("ts=1700000000000,v1=x", now, 900, 60));
        assertFalse(MercadoPagoWebhookSignature.isFresh("ts=1699999099,v1=x", now, 900, 60));
        assertFalse(MercadoPagoWebhookSignature.isFresh("ts=1700000061,v1=x", now, 900, 60));
        assertFalse(MercadoPagoWebhookSignature.isFresh("ts=1,ts=2", now, 900, 60));
    }
}
