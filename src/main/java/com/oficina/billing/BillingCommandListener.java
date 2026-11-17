package com.oficina.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

@Configuration
@ConditionalOnProperty(name = "billing.commands.enabled", havingValue = "true")
class BillingCommandConfiguration {
    static final String QUEUE = "oficina.billing.commands.v1";
    @Bean DirectExchange billingCommandsExchange() { return new DirectExchange("oficina.billing.commands", true, false); }
    @Bean Queue billingCommandsQueue() { return QueueBuilder.durable(QUEUE).build(); }
    @Bean Binding billingCommandsBinding(Queue billingCommandsQueue, DirectExchange billingCommandsExchange) {
        return BindingBuilder.bind(billingCommandsQueue).to(billingCommandsExchange).with("quote.commands");
    }
}

@Component
@ConditionalOnProperty(name = "billing.commands.enabled", havingValue = "true")
class BillingCommandListener {
    private final QuoteService quotes;
    private final ObjectMapper json;
    BillingCommandListener(QuoteService quotes, ObjectMapper json) { this.quotes = quotes; this.json = json; }
    @RabbitListener(queues = BillingCommandConfiguration.QUEUE)
    public void receive(Message message) {
        try {
            JsonNode command = json.readTree(message.getBody());
            UUID quoteId = UUID.fromString(command.path("quoteId").asText());
            quotes.compensate(quoteId);
        } catch (Exception e) {
            throw new AmqpRejectAndDontRequeueException("Invalid quote compensation command", e);
        }
    }
}
