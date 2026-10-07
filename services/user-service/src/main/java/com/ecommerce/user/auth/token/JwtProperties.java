package com.ecommerce.user.auth.token;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.io.Resource;

import java.time.Duration;

/**
 * @param issuer             "iss" claim; the gateway checks it
 * @param accessTokenTtl     short, because an access token cannot be revoked before it expires
 * @param refreshTokenTtl    absolute session length; rotation does not extend it
 * @param privateKeyLocation PKCS#8 PEM RSA key (scripts/generate-jwt-key.sh)
 * @param allowGeneratedKey  without a key file, generate one at startup instead of failing. Local dev only:
 *                           every instance would sign with a different key, and a restart invalidates all tokens
 */
@ConfigurationProperties("ecommerce.jwt")
public record JwtProperties(
        @DefaultValue("ecommerce-auth") String issuer,
        @DefaultValue("15m") Duration accessTokenTtl,
        @DefaultValue("14d") Duration refreshTokenTtl,
        Resource privateKeyLocation,
        @DefaultValue("false") boolean allowGeneratedKey
) {
}
