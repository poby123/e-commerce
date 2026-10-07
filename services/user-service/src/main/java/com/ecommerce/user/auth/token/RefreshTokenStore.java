package com.ecommerce.user.auth.token;

import com.ecommerce.common.error.BusinessException;
import com.ecommerce.user.error.UserErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Opaque refresh tokens in Redis with rotation and reuse detection.
 *
 * <ul>
 *   <li>Every login starts a "family". Each refresh consumes the token and issues the next one in the same family.</li>
 *   <li>A consumed token presented again means it was stolen (or replayed): the whole family is revoked,
 *       so the thief and the victim both have to log in again.</li>
 *   <li>Only SHA-256 hashes are stored, so a Redis dump does not contain usable tokens.</li>
 *   <li>The family key expires refreshTokenTtl after login and is never extended: an absolute session limit.</li>
 * </ul>
 */
@Component
public class RefreshTokenStore {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenStore.class);

    private static final String TOKEN_KEY = "auth:refresh:";        // + hash -> "userId:familyId"
    private static final String USED_TOKEN_KEY = "auth:refresh-used:"; // + hash -> familyId
    private static final String FAMILY_KEY = "auth:family:";       // + familyId -> userId

    private final StringRedisTemplate redis;
    private final Duration ttl;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenStore(StringRedisTemplate redis, JwtProperties properties) {
        this.redis = redis;
        this.ttl = properties.refreshTokenTtl();
    }

    public record Rotation(long userId, String refreshToken) {
    }

    /** Starts a new family (login). */
    public String issue(long userId) {
        String familyId = UUID.randomUUID().toString();
        redis.opsForValue().set(FAMILY_KEY + familyId, String.valueOf(userId), ttl);
        return issueInFamily(userId, familyId);
    }

    public Rotation rotate(String refreshToken) {
        String hash = hash(refreshToken);
        // GETDEL is atomic: of two concurrent refreshes with the same token, exactly one gets the value.
        String value = redis.opsForValue().getAndDelete(TOKEN_KEY + hash);
        if (value == null) {
            String reusedFamily = redis.opsForValue().get(USED_TOKEN_KEY + hash);
            if (reusedFamily != null) {
                redis.delete(FAMILY_KEY + reusedFamily);
                log.warn("Refresh token reuse detected, revoked family {}", reusedFamily);
            }
            throw new BusinessException(UserErrorCode.INVALID_REFRESH_TOKEN);
        }

        int separator = value.indexOf(':');
        long userId = Long.parseLong(value.substring(0, separator));
        String familyId = value.substring(separator + 1);

        Duration remaining = familyRemaining(familyId);
        if (remaining == null) {
            throw new BusinessException(UserErrorCode.INVALID_REFRESH_TOKEN);
        }
        redis.opsForValue().set(USED_TOKEN_KEY + hash, familyId, remaining);
        return new Rotation(userId, issueInFamily(userId, familyId));
    }

    /** Logout: the token and every token rotated from the same login stop working. */
    public void revoke(String refreshToken) {
        String value = redis.opsForValue().getAndDelete(TOKEN_KEY + hash(refreshToken));
        if (value != null) {
            redis.delete(FAMILY_KEY + value.substring(value.indexOf(':') + 1));
        }
    }

    private String issueInFamily(long userId, String familyId) {
        Duration remaining = familyRemaining(familyId);
        if (remaining == null) {
            throw new BusinessException(UserErrorCode.INVALID_REFRESH_TOKEN);
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redis.opsForValue().set(TOKEN_KEY + hash(token), userId + ":" + familyId, remaining);
        return token;
    }

    /** Time left until the family's absolute expiry, or null if it was revoked or expired. */
    private Duration familyRemaining(String familyId) {
        Long millis = redis.getExpire(FAMILY_KEY + familyId, TimeUnit.MILLISECONDS);
        if (millis == null || millis <= 0) {
            return null;
        }
        return Duration.ofMillis(millis);
    }

    private static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
