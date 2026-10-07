package com.ecommerce.common.web;

import com.ecommerce.common.error.BusinessException;
import com.ecommerce.common.error.CommonErrorCode;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

public class CurrentUserIdArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUserId.class)
                && (parameter.getParameterType() == Long.class || parameter.getParameterType() == long.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        String userId = webRequest.getHeader(AuthHeaders.USER_ID);
        if (userId == null || userId.isBlank()) {
            throw new BusinessException(CommonErrorCode.UNAUTHENTICATED);
        }
        try {
            return Long.parseLong(userId);
        } catch (NumberFormatException e) {
            throw new BusinessException(CommonErrorCode.UNAUTHENTICATED);
        }
    }
}
