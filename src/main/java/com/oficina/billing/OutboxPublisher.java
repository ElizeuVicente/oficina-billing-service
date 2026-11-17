package com.oficina.billing;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "billing.outbox.enabled", havingValue = "true")
class MessagingConfiguration {
    static final String EXCHANGE = "oficina.billing.events";
    static final String QUEUE = "oficina.billing.events.local";
    @Bean DirectExchange billingExchange() { return new DirectExchange(EXCHANGE, true, false); }
    @Bean Queue billingQueue() { return new Queue(QUEUE, true); }
    @Bean Binding billingBinding(Queue billingQueue, DirectExchange billingExchange) {
        return BindingBuilder.bind(billingQueue).to(billingExchange).with("quote.events");
    }
}

@Component
@ConditionalOnProperty(name = "billing.outbox.enabled", havingValue = "true")
class OutboxPublisher {
    private final OutboxRepository repository;
    private final RabbitTemplate rabbit;
    OutboxPublisher(OutboxRepository repository, RabbitTemplate rabbit) {
        this.repository = repository; this.rabbit = rabbit; this.rabbit.setMandatory(true);
    }
    @Scheduled(fixedDelayString = "${billing.outbox.delay-ms:5000}")
    @Transactional
    public void publishBatch() {
        for (OutboxEvent event : repository.lockPending(PageRequest.of(0, 25))) {
            var properties = new MessageProperties();
            properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            properties.setMessageId(event.getEventId().toString());
            properties.setType(event.getEventType());
            var correlation = new CorrelationData(event.getEventId().toString());
            rabbit.send(MessagingConfiguration.EXCHANGE, "quote.events", new Message(event.getPayload().getBytes(StandardCharsets.UTF_8), properties), correlation);
            try {
                var confirm = correlation.getFuture().get(10, TimeUnit.SECONDS);
                if (!confirm.isAck() || correlation.getReturned() != null) throw new IllegalStateException("Broker rejected or returned event " + event.getEventId());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for broker", e);
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
                throw new IllegalStateException("Broker confirmation unavailable", e);
            }
            event.markPublished();
        }
    }
}
