package com.ecommerce.common.web;

import com.ecommerce.common.error.BusinessException;
import com.ecommerce.common.error.CommonErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;

public class RequiresRoleInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        RequiresRole required = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), RequiresRole.class);
        if (required == null) {
            required = AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), RequiresRole.class);
        }
        if (required == null) {
            return true;
        }
        if (request.getHeader(AuthHeaders.USER_ID) == null) {
            throw new BusinessException(CommonErrorCode.UNAUTHENTICATED);
        }
        String roles = request.getHeader(AuthHeaders.USER_ROLES);
        String role = required.value();
        if (roles == null || Arrays.stream(roles.split(",")).map(String::trim).noneMatch(role::equals)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        return true;
    }
}
