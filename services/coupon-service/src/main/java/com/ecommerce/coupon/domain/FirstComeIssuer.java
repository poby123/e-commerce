package com.ecommerce.coupon.domain;

import com.ecommerce.common.error.BusinessException;
import com.ecommerce.coupon.error.CouponErrorCode;
import com.ecommerce.messaging.contract.CouponMessages.CouponClaimed;
import com.ecommerce.messaging.contract.Topics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * First-come issuance for a burst of requests (e.g. 10,000 at 00:00 for 1,000 coupons).
 *
 * <ol>
 *   <li>Redis decides, in one Lua script: already claimed? sold out? otherwise record the user. Atomic, so no
 *       race between "check" and "take", and the DB never sees the losers.</li>
 *   <li>A winner is published to Kafka (key = policy id) and acknowledged before we answer. If that fails, the
 *       user is removed from the Redis set again, so Redis never counts a coupon nobody will store.</li>
 *   <li>A consumer stores it. One partition per policy means the inserts for a policy are serial: no lock
 *       contention on the policy row, at the speed the DB can take.</li>
 * </ol>
 * The DB stays the source of truth (unique policy+user, CHECK issued <= total); Redis is the gate.
 */
@Component
public class FirstComeIssuer {

    public enum Result { WON, SOLD_OUT, ALREADY_CLAIMED }

    private static final Logger log = LoggerFactory.getLogger(FirstComeIssuer.class);

    /** KEYS[1] = claimed user set, ARGV[1] = user id, ARGV[2] = limit. 1 won, 0 sold out, -1 already claimed. */
    private static final RedisScript<Long> CLAIM = RedisScript.of("""
            if redis.call('SISMEMBER', KEYS[1], ARGV[1]) == 1 then return -1 end
            if redis.call('SCARD', KEYS[1]) >= tonumber(ARGV[2]) then return 0 end
            redis.call('SADD', KEYS[1], ARGV[1])
            return 1
            """, Long.class);

    private record PolicyLimit(boolean firstCome, int limit) {
    }

    private final StringRedisTemplate redis;
    private final KafkaTemplate<String, String> kafka;
    private final CouponPolicyRepository policies;
    private final ObjectMapper objectMapper;
    // Policies do not change after creation; caching keeps the burst off the DB entirely.
    private final Map<Long, PolicyLimit> limits = new ConcurrentHashMap<>();

    public FirstComeIssuer(StringRedisTemplate redis, KafkaTemplate<String, String> kafka, CouponPolicyRepository policies,
                           ObjectMapper objectMapper) {
        this.redis = redis;
        this.kafka = kafka;
        this.policies = policies;
        this.objectMapper = objectMapper;
    }

    public Result claim(long policyId, long userId) {
        PolicyLimit policy = limits.computeIfAbsent(policyId, id -> policies.findById(id)
                .map(p -> new PolicyLimit(p.isFirstCome(), p.getTotalQuantity() == null ? 0 : p.getTotalQuantity()))
                .orElseThrow(() -> new BusinessException(CouponErrorCode.POLICY_NOT_FOUND)));
        if (!policy.firstCome()) {
            throw new BusinessException(CouponErrorCode.WRONG_ISSUE_PATH);
        }

        String key = claimedKey(policyId);
        Long outcome = redis.execute(CLAIM, List.of(key), String.valueOf(userId), String.valueOf(policy.limit()));
        if (outcome == null || outcome == 0) {
            return Result.SOLD_OUT;
        }
        if (outcome < 0) {
            return Result.ALREADY_CLAIMED;
        }

        try {
            ProducerRecord<String, String> record = new ProducerRecord<>(Topics.COUPON_CLAIM, String.valueOf(policyId),
                    objectMapper.writeValueAsString(new CouponClaimed(policyId, userId)));
            record.headers().add("id", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
            record.headers().add("type", CouponClaimed.class.getSimpleName().getBytes(StandardCharsets.UTF_8));
            kafka.send(record).get(5, TimeUnit.SECONDS);
            return Result.WON;
        } catch (JsonProcessingException e) {
            redis.opsForSet().remove(key, String.valueOf(userId));
            throw new IllegalStateException(e);
        } catch (Exception e) {
            // Not stored anywhere: give the slot back so Redis and the DB agree.
            redis.opsForSet().remove(key, String.valueOf(userId));
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Claim of policy {} by user {} not published; slot released", policyId, userId, e);
            throw new BusinessException(CouponErrorCode.CLAIM_UNAVAILABLE);
        }
    }

    public long claimedCount(long policyId) {
        Long size = redis.opsForSet().size(claimedKey(policyId));
        return size == null ? 0 : size;
    }

    private static String claimedKey(long policyId) {
        return "coupon:first-come:" + policyId + ":claimed";
    }
}
