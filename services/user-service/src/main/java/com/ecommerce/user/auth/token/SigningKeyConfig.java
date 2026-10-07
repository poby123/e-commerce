package com.ecommerce.user.auth.token;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

@Configuration
public class SigningKeyConfig {

    private static final Logger log = LoggerFactory.getLogger(SigningKeyConfig.class);

    @Bean
    RSAKey signingKey(JwtProperties properties) throws IOException, GeneralSecurityException, JOSEException {
        Resource location = properties.privateKeyLocation();
        if (location == null) {
            if (!properties.allowGeneratedKey()) {
                throw new IllegalStateException("ecommerce.jwt.private-key-location is required "
                        + "(create one with scripts/generate-jwt-key.sh, or set ecommerce.jwt.allow-generated-key=true for local dev)");
            }
            log.warn("No signing key configured: generated a temporary one. Tokens become invalid on restart "
                    + "and differ between instances, so this only works for a single local instance.");
            return new RSAKeyGenerator(2048).keyIDFromThumbprint(true).generate();
        }
        RSAPrivateCrtKey privateKey = readPkcs8(location);
        RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
        // kid = key thumbprint, so a new key always gets a new kid and the gateway refetches the JWKS.
        return new RSAKey.Builder(publicKey).privateKey(privateKey).keyIDFromThumbprint().build();
    }

    private RSAPrivateCrtKey readPkcs8(Resource location) throws IOException, GeneralSecurityException {
        String pem = location.getContentAsString(StandardCharsets.US_ASCII)
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(pem);
        return (RSAPrivateCrtKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }
}
