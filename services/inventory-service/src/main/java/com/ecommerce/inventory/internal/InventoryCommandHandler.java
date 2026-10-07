package com.ecommerce.inventory.internal;

import com.ecommerce.common.error.BusinessException;
import com.ecommerce.inventory.error.InventoryErrorCode;
import com.ecommerce.inventory.internal.ReservationDtos.Item;
import com.ecommerce.inventory.internal.ReservationDtos.ReserveRequest;
import com.ecommerce.messaging.IncomingMessage;
import com.ecommerce.messaging.Outbox;
import com.ecommerce.messaging.ProcessedMessages;
import com.ecommerce.messaging.contract.InventoryMessages.ReleaseStock;
import com.ecommerce.messaging.contract.InventoryMessages.ReserveStock;
import com.ecommerce.messaging.contract.InventoryMessages.StockRejected;
import com.ecommerce.messaging.contract.InventoryMessages.StockReserved;
import com.ecommerce.messaging.contract.Topics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Saga participant. Each command is handled in one local transaction together with its de-duplication record
 * and its reply (outbox), so "stock taken" and "StockReserved sent" cannot drift apart.
 */
@Component
public class InventoryCommandHandler {

    private final ReservationService reservationService;
    private final ProcessedMessages processedMessages;
    private final Outbox outbox;
    private final TransactionTemplate transaction;
    private final ObjectMapper objectMapper;

    public InventoryCommandHandler(ReservationService reservationService, ProcessedMessages processedMessages,
                                   Outbox outbox, TransactionTemplate transaction, ObjectMapper objectMapper) {
        this.reservationService = reservationService;
        this.processedMessages = processedMessages;
        this.outbox = outbox;
        this.transaction = transaction;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = Topics.INVENTORY_COMMAND)
    public void onMessage(ConsumerRecord<String, String> record) {
        handle(IncomingMessage.from(record));
    }

    public void handle(IncomingMessage message) {
        switch (message.type()) {
            case "ReserveStock" -> reserve(message, message.payload(objectMapper, ReserveStock.class));
            case "ReleaseStock" -> release(message, message.payload(objectMapper, ReleaseStock.class));
            default -> throw new IllegalArgumentException("Unknown inventory command " + message.type());
        }
    }

    private void reserve(IncomingMessage message, ReserveStock command) {
        var request = new ReserveRequest(command.sagaId(),
                command.items().stream().map(i -> new Item(i.productId(), i.quantity())).toList());
        try {
            transaction.executeWithoutResult(status -> {
                if (!processedMessages.markProcessed(message.id())) {
                    return;
                }
                reservationService.reserve(request);
                outbox.send(Topics.ORDER_SAGA_REPLY, command.sagaId(), new StockReserved(command.sagaId()));
            });
        } catch (BusinessException rejected) {
            // The reservation rolled back; record the message and the rejection in a new transaction.
            transaction.executeWithoutResult(status -> {
                if (processedMessages.markProcessed(message.id())) {
                    outbox.send(Topics.ORDER_SAGA_REPLY, command.sagaId(), new StockRejected(command.sagaId(), reason(rejected)));
                }
            });
        }
    }

    /** Compensation: no reply. It is idempotent and the outbox guarantees delivery. */
    private void release(IncomingMessage message, ReleaseStock command) {
        transaction.executeWithoutResult(status -> {
            if (processedMessages.markProcessed(message.id())) {
                reservationService.release(command.sagaId());
            }
        });
    }

    private static String reason(BusinessException e) {
        return e.errorCode() == InventoryErrorCode.INSUFFICIENT_STOCK ? "OUT_OF_STOCK"
                : e.errorCode() == InventoryErrorCode.RESERVATION_RELEASED ? "CANCELLED"
                : e.errorCode().code();
    }
}
