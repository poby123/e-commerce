package com.ecommerce.user.auth.token;

import com.ecommerce.user.domain.User;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SigningKeyConfigTest {

    private final SigningKeyConfig config = new SigningKeyConfig();

    @Test
    void replicasSharingAKeyFileVerifyEachOthersTokens(@TempDir Path dir) throws Exception {
        Path pem = writePkcs8Pem(dir.resolve("jwt-private.pem"));
        JwtProperties properties = properties(new FileSystemResource(pem), false);

        RSAKey podA = config.signingKey(properties);
        RSAKey podB = config.signingKey(properties);
        assertThat(podB.getKeyID()).isEqualTo(podA.getKeyID());

        // Token from pod A, verified with the JWKS that pod B publishes (what the gateway may fetch).
        String token = new AccessTokenIssuer(podA, properties).issue(user(7L));
        Map<String, Object> jwksFromB = new JwksController(podB).jwks();
        SignedJWT jwt = SignedJWT.parse(token);
        RSAKey publicKey = JWKSet.parse(jwksFromB).getKeyByKeyId(jwt.getHeader().getKeyID()).toRSAKey();
        assertThat(jwt.verify(new RSASSAVerifier(publicKey))).isTrue();
        assertThat(jwksFromB.toString()).doesNotContain("\"d\"");
    }

    @Test
    void missingKeyFailsStartupUnlessGenerationIsAllowed() {
        assertThatThrownBy(() -> config.signingKey(properties(null, false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("private-key-location");
    }

    @Test
    void generatedKeysDifferPerInstanceWhichIsWhyTheyAreDevOnly() throws Exception {
        JwtProperties properties = properties(null, true);
        assertThat(config.signingKey(properties).getKeyID())
                .isNotEqualTo(config.signingKey(properties).getKeyID());
    }

    /** Same format as `openssl genpkey -algorithm RSA` (scripts/generate-jwt-key.sh). */
    private static Path writePkcs8Pem(Path path) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
        return Files.writeString(path, "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n");
    }

    private static JwtProperties properties(FileSystemResource key, boolean allowGenerated) {
        return new JwtProperties("ecommerce-auth", Duration.ofMinutes(15), Duration.ofDays(14), key, allowGenerated);
    }

    private static User user(long id) {
        User user = User.register("a@example.com", "hash", "Kim");
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
