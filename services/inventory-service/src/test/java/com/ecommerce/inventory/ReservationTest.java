package com.ecommerce.inventory;

import com.ecommerce.common.error.BusinessException;
import com.ecommerce.common.web.AuthHeaders;
import com.ecommerce.inventory.domain.StockRepository;
import com.ecommerce.inventory.internal.ReservationDtos.Item;
import com.ecommerce.inventory.internal.ReservationDtos.ReserveRequest;
import com.ecommerce.inventory.internal.ReservationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "spring.datasource.hikari.maximum-pool-size=30",
})
@AutoConfigureMockMvc
@Import(ReservationTest.Containers.class)
class ReservationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {
        @Bean
        @ServiceConnection
        MySQLContainer<?> mysql() {
            return new MySQLContainer<>("mysql:8.4");
        }
    }

    private static final AtomicLong PRODUCT_IDS = new AtomicLong(1_000);

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    ReservationService reservationService;
    @Autowired
    StockRepository stockRepository;
    @Autowired
    TransactionTemplate transaction;

    @Test
    void reserveIsAllOrNothing() throws Exception {
        long a = productWithStock(10);
        long b = productWithStock(1);

        reserve(UUID.randomUUID().toString(), Map.of(a, 3, b, 2))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVENTORY-001"));

        // a was decremented before b failed; the rollback put it back.
        assertThat(stock(a)).isEqualTo(10);
        assertThat(stock(b)).isEqualTo(1);
    }

    @Test
    void sameOrderIdTwiceTakesStockOnce() throws Exception {
        long a = productWithStock(10);
        String orderId = UUID.randomUUID().toString();

        reserve(orderId, Map.of(a, 3)).andExpect(status().isOk());
        reserve(orderId, Map.of(a, 3)).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].quantity").value(3));

        assertThat(stock(a)).isEqualTo(7);
    }

    @Test
    void releaseGivesStockBackOnceAndBlocksAReplay() throws Exception {
        long a = productWithStock(10);
        String orderId = UUID.randomUUID().toString();
        reserve(orderId, Map.of(a, 4)).andExpect(status().isOk());

        mvc.perform(post("/internal/reservations/{id}/release", orderId)).andExpect(status().isNoContent());
        mvc.perform(post("/internal/reservations/{id}/release", orderId)).andExpect(status().isNoContent());
        assertThat(stock(a)).isEqualTo(10);

        // A late retry of the original reserve must not take the stock again.
        reserve(orderId, Map.of(a, 4)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVENTORY-004"));
        assertThat(stock(a)).isEqualTo(10);
    }

    @Test
    void hundredBuyersForFiftyUnitsSellExactlyFifty() throws Exception {
        long a = productWithStock(50);
        AtomicInteger sold = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        runConcurrently(100, i -> {
            try {
                reservationService.reserve(new ReserveRequest(UUID.randomUUID().toString(), List.of(new Item(a, 1))));
                sold.incrementAndGet();
            } catch (BusinessException e) {
                rejected.incrementAndGet();
            }
        });

        assertThat(sold.get()).isEqualTo(50);
        assertThat(rejected.get()).isEqualTo(50);
        assertThat(stock(a)).isZero();
    }

    @Test
    void oppositeItemOrderDoesNotDeadlock() throws Exception {
        long a = productWithStock(1_000);
        long b = productWithStock(1_000);

        // Half the orders list (a, b), half (b, a). Without sorting, InnoDB would detect deadlocks here.
        runConcurrently(60, i -> reservationService.reserve(new ReserveRequest(UUID.randomUUID().toString(),
                i % 2 == 0 ? List.of(new Item(a, 1), new Item(b, 1)) : List.of(new Item(b, 1), new Item(a, 1)))));

        assertThat(stock(a)).isEqualTo(940);
        assertThat(stock(b)).isEqualTo(940);
    }

    @Test
    void concurrentRetriesOfOneOrderTakeStockOnce() throws Exception {
        long a = productWithStock(100);
        String orderId = UUID.randomUUID().toString();

        runConcurrently(10, i -> reservationService.reserve(new ReserveRequest(orderId, List.of(new Item(a, 5)))));

        assertThat(stock(a)).isEqualTo(95);
    }

    @Test
    void concurrentReleasesGiveStockBackOnce() throws Exception {
        long a = productWithStock(10);
        String orderId = UUID.randomUUID().toString();
        reservationService.reserve(new ReserveRequest(orderId, List.of(new Item(a, 4))));

        runConcurrently(10, i -> reservationService.release(orderId));

        assertThat(stock(a)).isEqualTo(10);
    }

    @Test
    void settingStockRequiresAdmin() throws Exception {
        long id = PRODUCT_IDS.incrementAndGet();
        String body = "{\"quantity\":5}";
        mvc.perform(put("/api/inventory/{id}", id).contentType(MediaType.APPLICATION_JSON).content(body)
                        .header(AuthHeaders.USER_ID, 2).header(AuthHeaders.USER_ROLES, "USER"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/inventory/{id}", id).contentType(MediaType.APPLICATION_JSON).content(body)
                        .header(AuthHeaders.USER_ID, 1).header(AuthHeaders.USER_ROLES, "ADMIN"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/inventory/{id}", id)).andExpect(jsonPath("$.quantity").value(5));
    }

    private org.springframework.test.web.servlet.ResultActions reserve(String orderId, Map<Long, Integer> items) throws Exception {
        List<Map<String, Object>> list = items.entrySet().stream()
                .map(e -> Map.<String, Object>of("productId", e.getKey(), "quantity", e.getValue())).toList();
        return mvc.perform(post("/internal/reservations").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("orderId", orderId, "items", list))));
    }

    private long productWithStock(int quantity) {
        long id = PRODUCT_IDS.incrementAndGet();
        transaction.executeWithoutResult(s -> stockRepository.upsert(id, quantity));
        return id;
    }

    private int stock(long productId) {
        return stockRepository.findById(productId).orElseThrow().getQuantity();
    }

    interface Task {
        void run(int index) throws Exception;
    }

    /** Starts all tasks at the same instant; rethrows the first unexpected failure. */
    private static void runConcurrently(int count, Task task) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(count)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(pool.submit((Callable<Void>) () -> {
                    start.await();
                    task.run(index);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        }
    }
}
