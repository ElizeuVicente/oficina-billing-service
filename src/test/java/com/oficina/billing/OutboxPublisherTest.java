package com.oficina.billing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

class OutboxPublisherTest {
    @Test void confirmationMarksEventAndRejectionLeavesItPending() {
        var repository = mock(OutboxRepository.class);
        var rabbit = mock(RabbitTemplate.class);
        var event = new OutboxEvent(UUID.randomUUID(), UUID.randomUUID(), "PagamentoConfirmado", "{}", Instant.now());
        when(repository.lockPending(any(Pageable.class))).thenReturn(List.of(event));
        doAnswer(call -> { CorrelationData correlation = call.getArgument(3); correlation.getFuture().complete(new CorrelationData.Confirm(true, null)); return null; })
                .when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        new OutboxPublisher(repository, rabbit).publishBatch();
        assertNotNull(ReflectionTestUtils.getField(event, "publishedAt"));

        var rejected = new OutboxEvent(UUID.randomUUID(), UUID.randomUUID(), "PagamentoRecusado", "{}", Instant.now());
        when(repository.lockPending(any(Pageable.class))).thenReturn(List.of(rejected));
        doAnswer(call -> { CorrelationData correlation = call.getArgument(3); correlation.getFuture().complete(new CorrelationData.Confirm(false, "rejected")); return null; })
                .when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertThrows(IllegalStateException.class, () -> new OutboxPublisher(repository, rabbit).publishBatch());
        assertNull(ReflectionTestUtils.getField(rejected, "publishedAt"));
    }
}
