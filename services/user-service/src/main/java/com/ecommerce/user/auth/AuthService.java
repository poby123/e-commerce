package com.ecommerce.user.auth;

import com.ecommerce.common.error.BusinessException;
import com.ecommerce.messaging.Outbox;
import com.ecommerce.messaging.contract.Topics;
import com.ecommerce.messaging.contract.UserEvents;
import com.ecommerce.user.api.UserResponse;
import com.ecommerce.user.auth.AuthDtos.LoginRequest;
import com.ecommerce.user.auth.AuthDtos.SignupRequest;
import com.ecommerce.user.auth.AuthDtos.TokenResponse;
import com.ecommerce.user.auth.token.AccessTokenIssuer;
import com.ecommerce.user.auth.token.RefreshTokenStore;
import com.ecommerce.user.domain.User;
import com.ecommerce.user.domain.UserRepository;
import com.ecommerce.user.error.UserErrorCode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenIssuer accessTokenIssuer;
    private final RefreshTokenStore refreshTokenStore;
    private final Outbox outbox;
    // Compared against when the email is unknown, so a miss costs as much time as a wrong password.
    private final String dummyPasswordHash;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       AccessTokenIssuer accessTokenIssuer, RefreshTokenStore refreshTokenStore, Outbox outbox) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.accessTokenIssuer = accessTokenIssuer;
        this.refreshTokenStore = refreshTokenStore;
        this.outbox = outbox;
        this.dummyPasswordHash = passwordEncoder.encode("timing-equalizer");
    }

    @Transactional
    public UserResponse signup(SignupRequest request) {
        String email = normalize(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(UserErrorCode.DUPLICATE_EMAIL);
        }
        User user = User.register(email, passwordEncoder.encode(request.password()), request.name().strip());
        try {
            // flush so a concurrent signup with the same email hits the unique key here, not at commit.
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(UserErrorCode.DUPLICATE_EMAIL);
        }
        // Same transaction as the INSERT: the event exists if and only if the user does.
        outbox.send(Topics.USER_EVENTS, String.valueOf(user.getId()), new UserEvents.UserRegistered(user.getId()));
        return UserResponse.from(user);
    }

    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(normalize(request.email())).orElse(null);
        boolean matches = passwordEncoder.matches(request.password(),
                user != null ? user.getPasswordHash() : dummyPasswordHash);
        if (user == null || !matches) {
            throw new BusinessException(UserErrorCode.INVALID_CREDENTIALS);
        }
        return tokens(user, refreshTokenStore.issue(user.getId()));
    }

    @Transactional(readOnly = true)
    public TokenResponse refresh(String refreshToken) {
        RefreshTokenStore.Rotation rotation = refreshTokenStore.rotate(refreshToken);
        User user = userRepository.findById(rotation.userId())
                .orElseThrow(() -> new BusinessException(UserErrorCode.INVALID_REFRESH_TOKEN));
        return tokens(user, rotation.refreshToken());
    }

    public void logout(String refreshToken) {
        refreshTokenStore.revoke(refreshToken);
    }

    private TokenResponse tokens(User user, String refreshToken) {
        return TokenResponse.bearer(accessTokenIssuer.issue(user), accessTokenIssuer.expiresInSeconds(), refreshToken);
    }

    private static String normalize(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }
}
