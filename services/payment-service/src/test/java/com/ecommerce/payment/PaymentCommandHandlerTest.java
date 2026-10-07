package com.ecommerce.payment;

import com.ecommerce.messaging.IncomingMessage;
import com.ecommerce.messaging.contract.PaymentMessages.CancelPayment;
import com.ecommerce.messaging.contract.PaymentMessages.Pay;
import com.ecommerce.payment.domain.Payments;
import com.ecommerce.payment.messaging.PaymentCommandHandler;
import com.ecommerce.payment.pg.PaymentGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.MySQLContainer;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false",
})
@Import(PaymentCommandHandlerTest.Containers.class)
class PaymentCommandHandlerTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {
        @Bean
        @ServiceConnection
        MySQLContainer<?> mysql() {
            return new MySQLContainer<>("mysql:8.4");
        }
    }

    @Autowired
    PaymentCommandHandler handler;
    @Autowired
    Payments payments;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper objectMapper;
    @MockitoSpyBean
    PaymentGateway paymentGateway;

    @Test
    void approvedPaymentRepliesCompleted() throws Exception {
        String order = UUID.randomUUID().toString();
        handle("Pay", new Pay(order, 7, 24_000, "tok_approve"));

        assertThat(replies(order)).containsExactly("PaymentCompleted");
        assertThat(payments.find(order).orElseThrow().status()).isEqualTo(Payments.Status.APPROVED);
    }

    @Test
    void declinedCardRepliesFailedWithTheReason() throws Exception {
        String order = UUID.randomUUID().toString();
        handle("Pay", new Pay(order, 7, 24_000, "tok_decline"));

        assertThat(replies(order)).containsExactly("PaymentFailed");
        assertThat(field(order, "reason")).isEqualTo("CARD_DECLINED");
    }

    @Test
    void retriedPayChargesOnceAndRepliesTheSameResult() throws Exception {
        String order = UUID.randomUUID().toString();
        IncomingMessage pay = message("Pay", new Pay(order, 7, 24_000, "tok_approve"));

        handler.handle(pay);
        handler.handle(pay);                                             // redelivery: same message id
        handle("Pay", new Pay(order, 7, 24_000, "tok_approve"));        // saga retry: new message id

        verify(paymentGateway, times(1)).approve(eq(order), anyLong(), anyString());
        assertThat(replies(order)).containsExactly("PaymentCompleted", "PaymentCompleted");
        assertThat(jdbc.queryForList("SELECT DISTINCT JSON_EXTRACT(payload, '$.paymentId') FROM outbox WHERE aggregate_id = ?", order)).hasSize(1);
    }

    @Test
    void cancelBeforePayBlocksThePayWithoutCallingThePg() throws Exception {
        String order = UUID.randomUUID().toString();
        handle("CancelPayment", new CancelPayment(order));
        handle("Pay", new Pay(order, 7, 24_000, "tok_approve"));

        verify(paymentGateway, never()).approve(eq(order), anyLong(), anyString());
        assertThat(replies(order)).containsExactly("PaymentFailed");
        assertThat(field(order, "reason")).isEqualTo("CANCELLED");
    }

    @Test
    void cancelAfterApprovalRefunds() throws Exception {
        String order = UUID.randomUUID().toString();
        handle("Pay", new Pay(order, 7, 24_000, "tok_approve"));
        String transaction = payments.find(order).orElseThrow().pgTransaction();

        handle("CancelPayment", new CancelPayment(order));

        verify(paymentGateway).refund(transaction);
        assertThat(payments.find(order).orElseThrow().status()).isEqualTo(Payments.Status.CANCELLED);
    }

    private void handle(String type, Object payload) throws Exception {
        handler.handle(message(type, payload));
    }

    private IncomingMessage message(String type, Object payload) throws Exception {
        return new IncomingMessage(UUID.randomUUID().toString(), type, "k", objectMapper.writeValueAsString(payload));
    }

    private List<String> replies(String order) {
        return jdbc.queryForList("SELECT type FROM outbox WHERE aggregate_id = ? ORDER BY created_at", String.class, order);
    }

    private String field(String order, String name) {
        return jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(payload, '$." + name + "')) FROM outbox WHERE aggregate_id = ? LIMIT 1",
                String.class, order);
    }
}
