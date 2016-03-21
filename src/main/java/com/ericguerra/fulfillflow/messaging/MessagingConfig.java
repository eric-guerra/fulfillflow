package com.ericguerra.fulfillflow.messaging;

import com.ericguerra.fulfillflow.service.FulfillmentService;
import com.ericguerra.fulfillflow.service.OrderPlaced;
import com.ericguerra.fulfillflow.service.StatusPublisher;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Configuration
@Profile("!test")
public class MessagingConfig {
    public static final String ORDERS_QUEUE = "fulfillflow.orders";
    public static final String STATUS_EXCHANGE = "fulfillment.status";

    @Bean
    TopicExchange ordersExchange() {
        return new TopicExchange("orders", true, false);
    }

    @Bean
    Queue ordersQueue() {
        return QueueBuilder.durable(ORDERS_QUEUE).deadLetterExchange("orders.dlx").deadLetterRoutingKey("fulfillflow.orders.dlq").build();
    }

    @Bean
    Binding ordersBinding() {
        return BindingBuilder.bind(ordersQueue()).to(ordersExchange()).with("orders.placed");
    }

    @Bean
    DirectExchange ordersDlx() {
        return new DirectExchange("orders.dlx");
    }

    @Bean
    Queue ordersDlq() {
        return QueueBuilder.durable("fulfillflow.orders.dlq").build();
    }

    @Bean
    Binding dlqBinding() {
        return BindingBuilder.bind(ordersDlq()).to(ordersDlx()).with("fulfillflow.orders.dlq");
    }

    @Bean
    TopicExchange statusExchange() {
        return new TopicExchange(STATUS_EXCHANGE, true, false);
    }

    @Bean
    MessageConverter jsonConverter(com.fasterxml.jackson.databind.ObjectMapper mapper) {
        return new Jackson2JsonMessageConverter(mapper);
    }

    /** Routing key encodes the status so consumers can subscribe to e.g. "shipment.SHIPPED". */
    @Bean
    StatusPublisher rabbitStatusPublisher(RabbitTemplate rabbit) {
        return e -> rabbit.convertAndSend(STATUS_EXCHANGE, "shipment." + e.status(), e);
    }

    @Component
    @Profile("!test")
    static class OrderListener {
        private final FulfillmentService fulfillment;

        OrderListener(FulfillmentService fulfillment) {
            this.fulfillment = fulfillment;
        }

        @RabbitListener(queues = ORDERS_QUEUE)
        public void onOrder(OrderPlaced order) {
            try {
                fulfillment.receive(order);
            } catch (IllegalArgumentException e) {
                throw new AmqpRejectAndDontRequeueException(e.getMessage(), e);
            }
        }
    }
}
