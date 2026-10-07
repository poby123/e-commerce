package com.ecommerce.coupon;

import com.ecommerce.common.error.BusinessException;
import com.ecommerce.coupon.domain.CouponPolicy;
import com.ecommerce.coupon.domain.CouponPolicyRepository;
import com.ecommerce.coupon.domain.CouponService;
import com.ecommerce.coupon.domain.DiscountType;
import com.ecommerce.coupon.domain.FirstComeIssuer;
import com.ecommerce.coupon.domain.FirstComeIssuer.Result;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** Redis gate + Kafka + consumer, end to end with real containers. Throughput is measured with k6. */
@SpringBootTest(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
})
@Import({CouponTest.Containers.class, FirstComeClaimTest.Infra.class})
class FirstComeClaimTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Infra {
        @Bean
        @ServiceConnection(name = "redis")
        GenericContainer<?> redis() {
            return new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
        }

        /**
         * 3.8.1, not compose's 3.9.0: Kafka 3.9 rejects the 0.0.0.0 advertised listener that Testcontainers 1.21's
         * KafkaContainer configures, and exits at startup. The client protocol is the same.
         */
        @Bean
        @ServiceConnection
        KafkaContainer kafka() {
            return new KafkaContainer("apache/kafka:3.8.1");
        }
    }

    @Autowired
    FirstComeIssuer issuer;
    @Autowired
    CouponService couponService;
    @Autowired
    CouponPolicyRepository policies;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void burstOfClaimsIssuesExactlyTheQuantityOncePerUser() throws Exception {
        long policy = firstComePolicy(50);
        Map<Result, AtomicInteger> results = new ConcurrentHashMap<>();
        for (Result r : Result.values()) results.put(r, new AtomicInteger());

        // 300 requests from 200 users: the first 100 users click twice.
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(64)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 300; i++) {
                long user = 10_000 + (i % 200);
                futures.add(pool.submit(() -> {
                    start.await();
                    results.get(issuer.claim(policy, user)).incrementAndGet();
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) f.get();
        }

        assertThat(results.get(Result.WON).get()).isEqualTo(50);
        assertThat(issuer.claimedCount(policy)).isEqualTo(50);
        // Stored asynchronously by the consumer.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_coupons WHERE policy_id = ?", Integer.class, policy)).isEqualTo(50));
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT user_id) FROM user_coupons WHERE policy_id = ?", Integer.class, policy)).isEqualTo(50);
        assertThat(jdbc.queryForObject("SELECT issued_quantity FROM coupon_policies WHERE id = ?", Integer.class, policy)).isEqualTo(50);
    }

    @Test
    void theTwoIssuePathsDoNotMix() {
        long firstCome = firstComePolicy(10);
        assertThatThrownBy(() -> couponService.issue(firstCome, 1))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode().code()).isEqualTo("COUPON-006"));
    }

    private long firstComePolicy(int quantity) {
        return policies.saveAndFlush(new CouponPolicy("FC" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(), "first come",
                DiscountType.FIXED, 5_000, null, 0, quantity, 7, true)).getId();
    }
}
