package com.ecommerce.user.auth.token;

import com.ecommerce.user.domain.User;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/** Signs RS256 access tokens. Claims hold only the user id and roles, no personal data. */
@Component
public class AccessTokenIssuer {

    private final RSAKey signingKey;
    private final RSASSASigner signer;
    private final JwtProperties properties;
    private final Clock clock;

    public AccessTokenIssuer(RSAKey signingKey, JwtProperties properties) throws JOSEException {
        this.signingKey = signingKey;
        this.signer = new RSASSASigner(signingKey);
        this.properties = properties;
        this.clock = Clock.systemUTC();
    }

    public String issue(User user) {
        Instant now = clock.instant();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(properties.issuer())
                .subject(String.valueOf(user.getId()))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(properties.accessTokenTtl())))
                .jwtID(UUID.randomUUID().toString())
                .claim("roles", List.of(user.getRole().name()))
                .build();
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(signingKey.getKeyID())
                .type(JOSEObjectType.JWT)
                .build();
        SignedJWT jwt = new SignedJWT(header, claims);
        try {
            jwt.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to sign access token", e);
        }
        return jwt.serialize();
    }

    public long expiresInSeconds() {
        return properties.accessTokenTtl().toSeconds();
    }
}
