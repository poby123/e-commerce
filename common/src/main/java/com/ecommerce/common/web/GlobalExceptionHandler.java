package com.ecommerce.common.web;

import com.ecommerce.common.error.BusinessException;
import com.ecommerce.common.error.CommonErrorCode;
import com.ecommerce.common.error.ErrorCode;
import com.ecommerce.common.error.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
        ErrorCode errorCode = e.errorCode();
        return ResponseEntity.status(errorCode.status()).body(ErrorResponse.of(errorCode, e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        List<ErrorResponse.FieldError> errors = e.getBindingResult().getFieldErrors().stream()
                .map(error -> new ErrorResponse.FieldError(error.getField(), error.getDefaultMessage()))
                .toList();
        return respond(CommonErrorCode.INVALID_INPUT, ErrorResponse.of(CommonErrorCode.INVALID_INPUT, errors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException e) {
        return respond(CommonErrorCode.INVALID_INPUT, ErrorResponse.of(CommonErrorCode.INVALID_INPUT, "Malformed request body"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoResourceFoundException e) {
        return respond(CommonErrorCode.NOT_FOUND, ErrorResponse.of(CommonErrorCode.NOT_FOUND));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return respond(CommonErrorCode.METHOD_NOT_ALLOWED, ErrorResponse.of(CommonErrorCode.METHOD_NOT_ALLOWED));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        return respond(CommonErrorCode.INTERNAL_ERROR, ErrorResponse.of(CommonErrorCode.INTERNAL_ERROR));
    }

    private ResponseEntity<ErrorResponse> respond(ErrorCode errorCode, ErrorResponse body) {
        return ResponseEntity.status(errorCode.status()).body(body);
    }
}
