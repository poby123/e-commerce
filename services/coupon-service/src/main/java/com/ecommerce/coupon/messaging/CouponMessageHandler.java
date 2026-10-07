package com.ecommerce.coupon.messaging;

import com.ecommerce.coupon.domain.CouponPolicyRepository;
import com.ecommerce.coupon.domain.CouponService;
import com.ecommerce.coupon.domain.UserCoupons;
import com.ecommerce.messaging.IncomingMessage;
import com.ecommerce.messaging.Outbox;
import com.ecommerce.messaging.ProcessedMessages;
import com.ecommerce.messaging.contract.CouponMessages.ConfirmCoupon;
import com.ecommerce.messaging.contract.CouponMessages.CouponRejected;
import com.ecommerce.messaging.contract.CouponMessages.CouponReserved;
import com.ecommerce.messaging.contract.CouponMessages.ReleaseCoupon;
import com.ecommerce.messaging.contract.CouponMessages.ReserveCoupon;
import com.ecommerce.messaging.contract.Topics;
import com.ecommerce.messaging.contract.UserEvents.UserRegistered;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;

/**
 * Two roles:
 * <ul>
 *   <li>Saga participant (orchestration): reserve / confirm / release on command from OrderSaga.</li>
 *   <li>Event subscriber (choreography): a new user gets the WELCOME coupon. user-service does not know this
 *       happens, and a failure here never affects signup.</li>
 * </ul>
 */
@Component
public class CouponMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(CouponMessageHandler.class);
    static final String WELCOME_POLICY = "WELCOME";

    private final CouponService couponService;
    private final CouponPolicyRepository policies;
    private final UserCoupons userCoupons;
    private final ProcessedMessages processedMessages;
    private final Outbox outbox;
    private final TransactionTemplate transaction;
    private final ObjectMapper objectMapper;

    public CouponMessageHandler(CouponService couponService, CouponPolicyRepository policies, UserCoupons userCoupons,
                                ProcessedMessages processedMessages, Outbox outbox, TransactionTemplate transaction,
                                ObjectMapper objectMapper) {
        this.couponService = couponService;
        this.policies = policies;
        this.userCoupons = userCoupons;
        this.processedMessages = processedMessages;
        this.outbox = outbox;
        this.transaction = transaction;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = {Topics.COUPON_COMMAND, Topics.USER_EVENTS})
    public void onMessage(ConsumerRecord<String, String> record) {
        handle(IncomingMessage.from(record));
    }

    public void handle(IncomingMessage message) {
        switch (message.type()) {
            case "ReserveCoupon" -> reserve(message, message.payload(objectMapper, ReserveCoupon.class));
            case "ConfirmCoupon" -> once(message, () -> {
                ConfirmCoupon c = message.payload(objectMapper, ConfirmCoupon.class);
                couponService.confirm(c.sagaId(), c.userCouponId());
            });
            case "ReleaseCoupon" -> once(message, () -> {
                ReleaseCoupon c = message.payload(objectMapper, ReleaseCoupon.class);
                couponService.release(c.sagaId(), c.userCouponId());
            });
            case "UserRegistered" -> once(message, () -> issueWelcome(message.payload(objectMapper, UserRegistered.class)));
            default -> throw new IllegalArgumentException("Unknown coupon message " + message.type());
        }
    }

    private void reserve(IncomingMessage message, ReserveCoupon command) {
        try {
            transaction.executeWithoutResult(status -> {
                if (!processedMessages.markProcessed(message.id())) {
                    return;
                }
                long discount = couponService.reserve(command.sagaId(), command.userId(), command.userCouponId(), command.orderAmount());
                outbox.send(Topics.ORDER_SAGA_REPLY, command.sagaId(), new CouponReserved(command.sagaId(), discount));
            });
        } catch (CouponService.Rejected rejected) {
            transaction.executeWithoutResult(status -> {
                if (processedMessages.markProcessed(message.id())) {
                    outbox.send(Topics.ORDER_SAGA_REPLY, command.sagaId(), new CouponRejected(command.sagaId(), rejected.reason()));
                }
            });
        }
    }

    private void issueWelcome(UserRegistered event) {
        policies.findByCode(WELCOME_POLICY).ifPresentOrElse(
                policy -> userCoupons.insert(policy.getId(), event.userId(), Instant.now().plus(Duration.ofDays(policy.getValidDays()))),
                () -> log.warn("No {} policy; user {} gets no welcome coupon", WELCOME_POLICY, event.userId()));
    }

    private void once(IncomingMessage message, Runnable action) {
        transaction.executeWithoutResult(status -> {
            if (processedMessages.markProcessed(message.id())) {
                action.run();
            }
        });
    }
}
