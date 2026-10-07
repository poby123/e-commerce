package com.ecommerce.coupon;

import com.ecommerce.common.error.BusinessException;
import com.ecommerce.common.web.AuthHeaders;
import com.ecommerce.coupon.domain.CouponService;
import com.ecommerce.coupon.domain.UserCoupons;
import com.ecommerce.coupon.messaging.CouponMessageHandler;
import com.ecommerce.messaging.IncomingMessage;
import com.ecommerce.messaging.contract.CouponMessages.ConfirmCoupon;
import com.ecommerce.messaging.contract.CouponMessages.ReleaseCoupon;
import com.ecommerce.messaging.contract.CouponMessages.ReserveCoupon;
import com.ecommerce.messaging.contract.UserEvents.UserRegistered;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false",
        "spring.datasource.hikari.maximum-pool-size=30",
})
@AutoConfigureMockMvc
@Import(CouponTest.Containers.class)
class CouponTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {
        @Bean
        @ServiceConnection
        MySQLContainer<?> mysql() {
            return new MySQLContainer<>("mysql:8.4");
        }
    }

    private static final AtomicLong USER_IDS = new AtomicLong(1_000);

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    CouponService couponService;
    @Autowired
    CouponMessageHandler handler;
    @Autowired
    UserCoupons userCoupons;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void limitedPolicyIssuesExactlyItsQuantityUnderConcurrency() throws Exception {
        long policy = createPolicy("FIXED", 1000, null, 0, 5);
        AtomicInteger issued = new AtomicInteger();

        runConcurrently(20, i -> {
            try {
                couponService.issue(policy, USER_IDS.incrementAndGet());
                issued.incrementAndGet();
            } catch (BusinessException soldOut) {
                assertThat(soldOut.errorCode().code()).isEqualTo("COUPON-003");
            }
        });

        assertThat(issued.get()).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_coupons WHERE policy_id = ?", Integer.class, policy)).isEqualTo(5);
    }

    @Test
    void oneCouponPerUserPerPolicy() throws Exception {
        long policy = createPolicy("FIXED", 1000, null, 0, null);
        long user = USER_IDS.incrementAndGet();
        issue(policy, user).andExpect(status().isCreated());
        issue(policy, user).andExpect(status().isConflict());
    }

    @Test
    void reserveComputesTheDiscountAndConfirmUsesTheCoupon() throws Exception {
        long user = USER_IDS.incrementAndGet();
        long coupon = issued(createPolicy("RATE", 10, 5_000L, 10_000, null), user);
        String order = UUID.randomUUID().toString();

        handle("ReserveCoupon", order, new ReserveCoupon(order, user, coupon, 80_000));
        assertThat(reply(order)).isEqualTo("CouponReserved");
        assertThat(discount(order)).isEqualTo(5_000); // 10% of 80,000 = 8,000, capped at 5,000

        handle("ConfirmCoupon", order, new ConfirmCoupon(order, coupon));
        assertThat(userCoupons.find(coupon).orElseThrow().status()).isEqualTo(UserCoupons.Status.USED);
    }

    @Test
    void aReservedCouponCannotBeUsedByAnotherOrderUntilReleased() throws Exception {
        long user = USER_IDS.incrementAndGet();
        long coupon = issued(createPolicy("FIXED", 3000, null, 0, null), user);
        String first = UUID.randomUUID().toString();
        String second = UUID.randomUUID().toString();

        handle("ReserveCoupon", first, new ReserveCoupon(first, user, coupon, 20_000));
        handle("ReserveCoupon", second, new ReserveCoupon(second, user, coupon, 20_000));
        assertThat(reply(second)).isEqualTo("CouponRejected");
        assertThat(reason(second)).isEqualTo("COUPON_NOT_AVAILABLE");

        handle("ReleaseCoupon", first, new ReleaseCoupon(first, coupon));
        String third = UUID.randomUUID().toString();
        handle("ReserveCoupon", third, new ReserveCoupon(third, user, coupon, 20_000));
        assertThat(reply(third)).isEqualTo("CouponReserved");
    }

    @Test
    void couponsAreChecked() throws Exception {
        long user = USER_IDS.incrementAndGet();
        long coupon = issued(createPolicy("FIXED", 3000, null, 10_000, null), user);

        String small = UUID.randomUUID().toString();
        handle("ReserveCoupon", small, new ReserveCoupon(small, user, coupon, 9_000));
        assertThat(reason(small)).isEqualTo("MIN_ORDER_AMOUNT_NOT_MET");

        String stranger = UUID.randomUUID().toString();
        handle("ReserveCoupon", stranger, new ReserveCoupon(stranger, USER_IDS.incrementAndGet(), coupon, 20_000));
        assertThat(reason(stranger)).isEqualTo("COUPON_NOT_FOUND");
    }

    @Test
    void releaseThatOvertookItsReserveMakesTheReserveFail() throws Exception {
        long user = USER_IDS.incrementAndGet();
        long coupon = issued(createPolicy("FIXED", 3000, null, 0, null), user);
        String order = UUID.randomUUID().toString();

        handle("ReleaseCoupon", order, new ReleaseCoupon(order, coupon));
        handle("ReserveCoupon", order, new ReserveCoupon(order, user, coupon, 20_000));

        assertThat(reason(order)).isEqualTo("CANCELLED");
        assertThat(userCoupons.find(coupon).orElseThrow().status()).isEqualTo(UserCoupons.Status.ISSUED);
    }

    @Test
    void newUserGetsTheWelcomeCouponOnceEvenIfTheEventIsRedelivered() throws Exception {
        long user = USER_IDS.incrementAndGet();
        IncomingMessage event = message("UserRegistered", String.valueOf(user), new UserRegistered(user));

        handler.handle(event);
        handler.handle(event);
        handler.handle(message("UserRegistered", String.valueOf(user), new UserRegistered(user))); // relay replay, new id

        assertThat(userCoupons.findByUser(user)).hasSize(1);
    }

    private long createPolicy(String type, long value, Long max, long minOrder, Integer quantity) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("code", "P" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                "name", "test", "discountType", type, "discountValue", value, "minOrderAmount", minOrder, "validDays", 30));
        if (max != null) body.put("maxDiscountAmount", max);
        if (quantity != null) body.put("totalQuantity", quantity);
        String response = mvc.perform(post("/api/coupon-policies").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body))
                        .header(AuthHeaders.USER_ID, 1).header(AuthHeaders.USER_ROLES, "ADMIN"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asLong();
    }

    private org.springframework.test.web.servlet.ResultActions issue(long policy, long user) throws Exception {
        return mvc.perform(post("/api/coupon-policies/{id}/issue", policy).header(AuthHeaders.USER_ID, user));
    }

    private long issued(long policy, long user) {
        return couponService.issue(policy, user).id();
    }

    private void handle(String type, String key, Object payload) throws Exception {
        handler.handle(message(type, key, payload));
    }

    private IncomingMessage message(String type, String key, Object payload) throws Exception {
        return new IncomingMessage(UUID.randomUUID().toString(), type, key, objectMapper.writeValueAsString(payload));
    }

    private String reply(String order) {
        return jdbc.queryForObject("SELECT type FROM outbox WHERE aggregate_id = ?", String.class, order);
    }

    private String reason(String order) {
        return jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(payload, '$.reason')) FROM outbox WHERE aggregate_id = ?", String.class, order);
    }

    private long discount(String order) {
        return Long.parseLong(jdbc.queryForObject("SELECT JSON_EXTRACT(payload, '$.discountAmount') FROM outbox WHERE aggregate_id = ?", String.class, order));
    }

    interface Task {
        void run(int index) throws Exception;
    }

    private static void runConcurrently(int count, Task task) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(count)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    task.run(index);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
        }
    }
}
