package com.ecommerce.inventory;

import com.ecommerce.inventory.domain.StockRepository;
import com.ecommerce.inventory.internal.InventoryCommandHandler;
import com.ecommerce.messaging.IncomingMessage;
import com.ecommerce.messaging.contract.InventoryMessages.Item;
import com.ecommerce.messaging.contract.InventoryMessages.ReleaseStock;
import com.ecommerce.messaging.contract.InventoryMessages.ReserveStock;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false",
})
@Import(ReservationTest.Containers.class)
class InventoryCommandHandlerTest {

    private static final AtomicLong PRODUCT_IDS = new AtomicLong(50_000);

    @Autowired
    InventoryCommandHandler handler;
    @Autowired
    StockRepository stockRepository;
    @Autowired
    TransactionTemplate transaction;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper objectMapper;

    @Test
    void reserveCommandTakesStockAndRepliesInTheSameTransaction() throws Exception {
        long product = productWithStock(5);
        String sagaId = UUID.randomUUID().toString();

        handler.handle(message("ReserveStock", sagaId, new ReserveStock(sagaId, List.of(new Item(product, 2)))));

        assertThat(stock(product)).isEqualTo(3);
        assertThat(replies(sagaId)).containsExactly("StockReserved");
    }

    @Test
    void redeliveredMessageIsIgnored() throws Exception {
        long product = productWithStock(5);
        String sagaId = UUID.randomUUID().toString();
        IncomingMessage message = message("ReserveStock", sagaId, new ReserveStock(sagaId, List.of(new Item(product, 2))));

        handler.handle(message);
        handler.handle(message);

        assertThat(stock(product)).isEqualTo(3);
        assertThat(replies(sagaId)).containsExactly("StockReserved");
    }

    @Test
    void outOfStockIsAReplyNotAnError() throws Exception {
        long product = productWithStock(1);
        String sagaId = UUID.randomUUID().toString();

        handler.handle(message("ReserveStock", sagaId, new ReserveStock(sagaId, List.of(new Item(product, 2)))));

        assertThat(stock(product)).isEqualTo(1);
        assertThat(replies(sagaId)).containsExactly("StockRejected");
        assertThat(jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(payload, '$.reason')) FROM outbox WHERE aggregate_id = ?",
                String.class, sagaId)).isEqualTo("OUT_OF_STOCK");
    }

    @Test
    void releaseThatOvertookItsReserveMakesTheReserveFail() throws Exception {
        long product = productWithStock(5);
        String sagaId = UUID.randomUUID().toString();

        handler.handle(message("ReleaseStock", sagaId, new ReleaseStock(sagaId)));
        handler.handle(message("ReserveStock", sagaId, new ReserveStock(sagaId, List.of(new Item(product, 2)))));

        assertThat(stock(product)).isEqualTo(5);
        assertThat(replies(sagaId)).containsExactly("StockRejected");
    }

    private IncomingMessage message(String type, String key, Object payload) throws Exception {
        return new IncomingMessage(UUID.randomUUID().toString(), type, key, objectMapper.writeValueAsString(payload));
    }

    private List<String> replies(String sagaId) {
        return jdbc.queryForList("SELECT type FROM outbox WHERE aggregate_type = 'order.saga-reply' AND aggregate_id = ? ORDER BY created_at",
                String.class, sagaId);
    }

    private long productWithStock(int quantity) {
        long id = PRODUCT_IDS.incrementAndGet();
        transaction.executeWithoutResult(s -> stockRepository.upsert(id, quantity));
        return id;
    }

    private int stock(long productId) {
        return stockRepository.findById(productId).orElseThrow().getQuantity();
    }
}
