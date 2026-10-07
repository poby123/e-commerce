package com.ecommerce.user.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record SignupRequest(
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(min = 8, max = 64) String password,
            @NotBlank @Size(max = 50) String name
    ) {
    }

    public record LoginRequest(
            @NotBlank String email,
            @NotBlank String password
    ) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    public record TokenResponse(String tokenType, String accessToken, long expiresIn, String refreshToken) {

        public static TokenResponse bearer(String accessToken, long expiresIn, String refreshToken) {
            return new TokenResponse("Bearer", accessToken, expiresIn, refreshToken);
        }
    }
}
