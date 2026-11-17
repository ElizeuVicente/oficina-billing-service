package com.oficina.billing;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentTest {
    @Test void refundRequiresProviderConfirmation() {
        var payment = new Payment(UUID.randomUUID(), new BigDecimal("12.50"));
        payment.confirmApproved(); payment.confirmApproved(); payment.requestRefund();
        assertEquals(Payment.Status.REFUND_PENDING, payment.status());
        payment.confirmRefund();
        assertEquals(Payment.Status.REFUNDED, payment.status());
    }
    @Test void rejectedOrPendingPaymentCannotBeRefunded() {
        var payment = new Payment(UUID.randomUUID(), BigDecimal.ONE);
        assertThrows(IllegalStateException.class, payment::requestRefund);
        payment.confirmRejected();
        assertThrows(IllegalStateException.class, payment::confirmApproved);
        assertThrows(IllegalStateException.class, payment::requestRefund);
    }
    @Test void validatesAmount() {
        assertThrows(IllegalArgumentException.class, () -> new Payment(UUID.randomUUID(), BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new Payment(UUID.randomUUID(), new BigDecimal("1.001")));
    }
}
