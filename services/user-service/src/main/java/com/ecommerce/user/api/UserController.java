package com.ecommerce.user.api;

import com.ecommerce.common.error.BusinessException;
import com.ecommerce.common.web.CurrentUserId;
import com.ecommerce.user.domain.UserRepository;
import com.ecommerce.user.error.UserErrorCode;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserRepository userRepository;

    public UserController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @GetMapping("/me")
    @Transactional(readOnly = true)
    public UserResponse me(@CurrentUserId Long userId) {
        return userRepository.findById(userId)
                .map(UserResponse::from)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
    }
}
