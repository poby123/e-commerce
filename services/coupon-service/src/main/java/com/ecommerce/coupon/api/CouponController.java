package com.ecommerce.coupon.api;

import com.ecommerce.common.error.BusinessException;
import com.ecommerce.common.web.CurrentUserId;
import com.ecommerce.common.web.RequiresRole;
import com.ecommerce.coupon.domain.CouponPolicy;
import com.ecommerce.coupon.domain.CouponPolicyRepository;
import com.ecommerce.coupon.domain.CouponService;
import com.ecommerce.coupon.domain.DiscountType;
import com.ecommerce.coupon.domain.FirstComeIssuer;
import com.ecommerce.coupon.domain.UserCoupons;
import com.ecommerce.coupon.domain.UserCoupons.UserCoupon;
import com.ecommerce.coupon.error.CouponErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
public class CouponController {

    private final CouponPolicyRepository policies;
    private final CouponService couponService;
    private final UserCoupons userCoupons;
    private final FirstComeIssuer firstComeIssuer;

    public CouponController(CouponPolicyRepository policies, CouponService couponService, UserCoupons userCoupons,
                            FirstComeIssuer firstComeIssuer) {
        this.policies = policies;
        this.couponService = couponService;
        this.userCoupons = userCoupons;
        this.firstComeIssuer = firstComeIssuer;
    }

    public record CreatePolicyRequest(
            @NotBlank @Pattern(regexp = "[A-Z0-9_]{2,50}") String code,
            @NotBlank String name,
            @NotNull DiscountType discountType,
            @Positive long discountValue,
            @Positive Long maxDiscountAmount,
            @PositiveOrZero long minOrderAmount,
            @Positive Integer totalQuantity,
            @Positive @Max(365) int validDays,
            boolean firstCome
    ) {
    }

    public record PolicyResponse(Long id, String code, String name, DiscountType discountType, long discountValue,
                                 Long maxDiscountAmount, long minOrderAmount, Integer totalQuantity, int issuedQuantity) {
        static PolicyResponse from(CouponPolicy p) {
            return new PolicyResponse(p.getId(), p.getCode(), p.getName(), p.getDiscountType(), p.getDiscountValue(),
                    p.getMaxDiscountAmount(), p.getMinOrderAmount(), p.getTotalQuantity(), p.getIssuedQuantity());
        }
    }

    public record UserCouponResponse(long id, String code, String name, String status, long minOrderAmount, Instant expiresAt) {
    }

    @PostMapping("/api/coupon-policies")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresRole("ADMIN")
    @Transactional
    public PolicyResponse create(@Valid @RequestBody CreatePolicyRequest r) {
        if (r.firstCome() && r.totalQuantity() == null) {
            throw new BusinessException(CouponErrorCode.INVALID_POLICY, "A first-come policy needs a totalQuantity");
        }
        if (r.discountType() == DiscountType.RATE && (r.discountValue() > 100 || r.maxDiscountAmount() == null)) {
            throw new BusinessException(CouponErrorCode.INVALID_POLICY, "RATE needs a value of 1-100 and a maxDiscountAmount");
        }
        try {
            return PolicyResponse.from(policies.saveAndFlush(new CouponPolicy(r.code(), r.name(), r.discountType(), r.discountValue(),
                    r.maxDiscountAmount(), r.minOrderAmount(), r.totalQuantity(), r.validDays(), r.firstCome())));
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(CouponErrorCode.DUPLICATE_CODE);
        }
    }

    @GetMapping("/api/coupon-policies")
    @Transactional(readOnly = true)
    public List<PolicyResponse> policies() {
        return policies.findAll().stream().map(PolicyResponse::from).toList();
    }

    @PostMapping("/api/coupon-policies/{policyId}/issue")
    @ResponseStatus(HttpStatus.CREATED)
    public UserCouponResponse issue(@CurrentUserId Long userId, @PathVariable long policyId) {
        UserCoupon coupon = couponService.issue(policyId, userId);
        return toResponse(coupon, policies.findById(policyId).orElseThrow());
    }

    /**
     * First-come claim: 202 when won (the coupon appears in /api/coupons/me shortly after), 409 when sold out or
     * already claimed. Decided in Redis; stored asynchronously (FirstComeIssuer).
     */
    @PostMapping("/api/coupon-policies/{policyId}/claim")
    public ResponseEntity<Map<String, String>> claim(@CurrentUserId Long userId, @PathVariable long policyId) {
        FirstComeIssuer.Result result = firstComeIssuer.claim(policyId, userId);
        HttpStatus status = result == FirstComeIssuer.Result.WON ? HttpStatus.ACCEPTED : HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(Map.of("result", result.name()));
    }

    @GetMapping("/api/coupons/me")
    @Transactional(readOnly = true)
    public List<UserCouponResponse> mine(@CurrentUserId Long userId) {
        Map<Long, CouponPolicy> byId = policies.findAll().stream().collect(Collectors.toMap(CouponPolicy::getId, Function.identity()));
        return userCoupons.findByUser(userId).stream().map(c -> toResponse(c, byId.get(c.policyId()))).toList();
    }

    private static UserCouponResponse toResponse(UserCoupon c, CouponPolicy p) {
        return new UserCouponResponse(c.id(), p.getCode(), p.getName(), c.status().name(), p.getMinOrderAmount(), c.expiresAt());
    }
}
