package com.ecommerce.inventory.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class ReservationDtos {

    private ReservationDtos() {
    }

    public record ReserveRequest(
            @NotBlank @Size(max = 36) String orderId,
            @NotEmpty @Size(max = 100) List<@Valid Item> items
    ) {
    }

    public record Item(@NotNull Long productId, @Positive @Max(1000) int quantity) {
    }

    public record ReservationResponse(String orderId, String status, List<Item> items) {
    }
}
