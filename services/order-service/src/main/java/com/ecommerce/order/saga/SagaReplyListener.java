package com.ecommerce.order.saga;

import com.ecommerce.messaging.IncomingMessage;
import com.ecommerce.messaging.contract.Topics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Replies are keyed by saga id, so one saga's replies arrive in order on one partition. An optimistic-lock
 * conflict with the timeout job fails the message, and the Kafka error handler retries it on fresh state.
 */
@Component
public class SagaReplyListener {

    private final OrderSagaManager manager;

    public SagaReplyListener(OrderSagaManager manager) {
        this.manager = manager;
    }

    @KafkaListener(topics = Topics.ORDER_SAGA_REPLY)
    public void onMessage(ConsumerRecord<String, String> record) {
        manager.onReply(IncomingMessage.from(record));
    }
}
