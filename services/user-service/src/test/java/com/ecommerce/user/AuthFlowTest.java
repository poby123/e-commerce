package com.ecommerce.user;

import com.ecommerce.common.web.AuthHeaders;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
class AuthFlowTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    void signupLoginAndReadOwnProfile() throws Exception {
        String email = uniqueEmail();
        long userId = signup(email, "password123").get("id").asLong();

        JsonNode tokens = login(email, "password123");
        assertThat(tokens.get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(tokens.get("expiresIn").asLong()).isEqualTo(900);

        // The access token verifies against the published JWKS: the same check the gateway does.
        SignedJWT jwt = SignedJWT.parse(tokens.get("accessToken").asText());
        String jwks = mvc.perform(get("/.well-known/jwks.json")).andReturn().getResponse().getContentAsString();
        RSAKey publicKey = JWKSet.parse(jwks).getKeyByKeyId(jwt.getHeader().getKeyID()).toRSAKey();
        assertThat(publicKey.isPrivate()).isFalse();
        assertThat(jwt.verify(new RSASSAVerifier(publicKey))).isTrue();
        assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo(String.valueOf(userId));
        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("ecommerce-auth");
        assertThat(jwt.getJWTClaimsSet().getStringListClaim("roles")).containsExactly("USER");

        mvc.perform(get("/api/users/me").header(AuthHeaders.USER_ID, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    void signupWritesUserRegisteredToTheOutbox() throws Exception {
        long userId = signup(uniqueEmail(), "password123").get("id").asLong();

        var row = jdbc.queryForMap("SELECT aggregate_type, type, JSON_EXTRACT(payload, '$.userId') AS user_id FROM outbox WHERE aggregate_id = ?",
                String.valueOf(userId));
        assertThat(row.get("aggregate_type")).isEqualTo("user.events");
        assertThat(row.get("type")).isEqualTo("UserRegistered");
        assertThat(Long.parseLong(String.valueOf(row.get("user_id")))).isEqualTo(userId);
    }

    @Test
    void failedSignupWritesNoEvent() throws Exception {
        String email = uniqueEmail();
        signup(email, "password123");
        Integer before = jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Integer.class);

        postJson("/api/auth/signup", Map.of("email", email, "password", "password123", "name", "Kim"))
                .andExpect(status().isConflict());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Integer.class)).isEqualTo(before);
    }

    @Test
    void emailIsCaseInsensitiveAndUnique() throws Exception {
        String email = uniqueEmail();
        signup(email, "password123");

        postJson("/api/auth/signup", Map.of("email", email.toUpperCase(), "password", "password123", "name", "Kim"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER-001"));

        login(email.toUpperCase(), "password123");
    }

    @Test
    void wrongPasswordAndUnknownEmailLookTheSame() throws Exception {
        String email = uniqueEmail();
        signup(email, "password123");

        postJson("/api/auth/login", Map.of("email", email, "password", "wrong-password"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-001"));
        postJson("/api/auth/login", Map.of("email", uniqueEmail(), "password", "password123"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-001"));
    }

    @Test
    void refreshRotatesTokenAndReuseRevokesTheWholeFamily() throws Exception {
        String email = uniqueEmail();
        signup(email, "password123");
        String first = login(email, "password123").get("refreshToken").asText();

        String second = refresh(first).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String secondToken = objectMapper.readTree(second).get("refreshToken").asText();
        assertThat(secondToken).isNotEqualTo(first);

        // Replaying the consumed token looks like theft: it fails and takes the current token down with it.
        refresh(first).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH-002"));
        refresh(secondToken).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesRefreshToken() throws Exception {
        String email = uniqueEmail();
        signup(email, "password123");
        String refreshToken = login(email, "password123").get("refreshToken").asText();

        postJson("/api/auth/logout", Map.of("refreshToken", refreshToken)).andExpect(status().isNoContent());
        refresh(refreshToken).andExpect(status().isUnauthorized());
    }

    @Test
    void invalidSignupReturnsFieldErrors() throws Exception {
        postJson("/api/auth/signup", Map.of("email", "not-an-email", "password", "short", "name", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-400"))
                .andExpect(jsonPath("$.errors[*].field").value(org.hamcrest.Matchers.containsInAnyOrder(
                        List.of("email", "password", "name").toArray())));
    }

    @Test
    void profileWithoutGatewayHeaderIsRejected() throws Exception {
        mvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON-401"));
    }

    private JsonNode signup(String email, String password) throws Exception {
        String body = postJson("/api/auth/signup", Map.of("email", email, "password", password, "name", "Kim"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private JsonNode login(String email, String password) throws Exception {
        String body = postJson("/api/auth/login", Map.of("email", email, "password", password))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return postJson("/api/auth/refresh", Map.of("refreshToken", refreshToken));
    }

    private ResultActions postJson(String uri, Object body) throws Exception {
        return mvc.perform(post(uri).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }
}
