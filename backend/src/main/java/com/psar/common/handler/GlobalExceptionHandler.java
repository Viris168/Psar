package com.psar.common.handler;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.psar.common.exception.ApiException;
import com.psar.common.exception.ErrorCode;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Turns every exception thrown from a controller into an {@link ErrorResponse}.
 * Errors raised inside the security filter chain (before a controller runs)
 * are handled by {@link SecurityErrorHandler} instead.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ---- domain exceptions -------------------------------------------------

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException ex) {
        return build(ex.getErrorCode(), ex.getMessage(), ex.getDetails());
    }

    // ---- 400 ---------------------------------------------------------------

    /** @Valid @RequestBody failed. Class-level checks (@AssertTrue) show up under their property name. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleBodyValidation(MethodArgumentNotValidException ex) {
        Map<String, String> details = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(e -> details.putIfAbsent(e.getField(), e.getDefaultMessage()));
        ex.getBindingResult().getGlobalErrors()
                .forEach(e -> details.putIfAbsent(e.getObjectName(), e.getDefaultMessage()));
        return build(ErrorCode.VALIDATION_FAILED, "Request validation failed", details);
    }

    /** Constraints on @PathVariable / @RequestParam (Spring 6.1+ built-in method validation). */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleParamValidation(HandlerMethodValidationException ex) {
        Map<String, String> details = new LinkedHashMap<>();
        ex.getParameterValidationResults().forEach(result -> {
            String name = result.getMethodParameter().getParameterName();
            result.getResolvableErrors()
                    .forEach(e -> details.putIfAbsent(name, e.getDefaultMessage()));
        });
        return build(ErrorCode.VALIDATION_FAILED, "Request validation failed", details);
    }

    /** Constraints checked by @Validated service beans. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        Map<String, String> details = ex.getConstraintViolations().stream()
                .collect(Collectors.toMap(
                        v -> lastNode(v.getPropertyPath().toString()),
                        v -> v.getMessage(),
                        (first, second) -> first,
                        LinkedHashMap::new));
        return build(ErrorCode.VALIDATION_FAILED, "Request validation failed", details);
    }

    /** Broken JSON, or a value of the wrong type (e.g. an unknown enum like deliveryType="BOAT"). */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        if (ex.getCause() instanceof InvalidFormatException ife && !ife.getPath().isEmpty()) {
            String field = ife.getPath().stream()
                    .map(JsonMappingException.Reference::getFieldName)
                    .filter(name -> name != null)
                    .collect(Collectors.joining("."));
            return build(ErrorCode.MALFORMED_REQUEST, "Request body has an invalid value",
                    Map.of(field, "Invalid value: " + ife.getValue()));
        }
        return build(ErrorCode.MALFORMED_REQUEST, "Request body is missing or not valid JSON", Map.of());
    }

    /** e.g. a non-UUID in /api/addresses/{publicId}. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return build(ErrorCode.MALFORMED_REQUEST, "Request has an invalid value",
                Map.of(ex.getName(), "Invalid value"));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException ex) {
        return build(ErrorCode.VALIDATION_FAILED, "Request validation failed",
                Map.of(ex.getParameterName(), "is required"));
    }

    // ---- 401 / 403 ---------------------------------------------------------

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleBadCredentials(BadCredentialsException ex) {
        return build(ErrorCode.INVALID_CREDENTIALS, "Invalid email or password", Map.of());
    }

    @ExceptionHandler(DisabledException.class)
    public ResponseEntity<ErrorResponse> handleDisabled(DisabledException ex) {
        return build(ErrorCode.ACCOUNT_DISABLED, "This account is disabled", Map.of());
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthentication(AuthenticationException ex) {
        return build(ErrorCode.UNAUTHORIZED, "Authentication required", Map.of());
    }

    /** Thrown by @PreAuthorize on a controller or service method. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        return build(ErrorCode.FORBIDDEN, "You do not have permission to do this", Map.of());
    }

    // ---- 404 / 405 / 409 / 413 / 415 ---------------------------------------

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex) {
        return build(ErrorCode.NOT_FOUND, "Endpoint not found", Map.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        return build(ErrorCode.METHOD_NOT_ALLOWED, "Method " + ex.getMethod() + " is not supported here", Map.of());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaType(HttpMediaTypeNotSupportedException ex) {
        return build(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "Content type is not supported", Map.of());
    }

    /**
     * Safety net for unique/check constraints the service didn't pre-check (or lost a race on).
     * Never leak constraint or table names to the client.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        return build(ErrorCode.CONFLICT, "The request conflicts with existing data", Map.of());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleUploadTooLarge(MaxUploadSizeExceededException ex) {
        return build(ErrorCode.PAYLOAD_TOO_LARGE, "Uploaded file is too large", Map.of());
    }

    // ---- 500 ---------------------------------------------------------------

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        return build(ErrorCode.INTERNAL_ERROR, "Something went wrong", Map.of());
    }

    private static ResponseEntity<ErrorResponse> build(ErrorCode code, String message, Map<String, String> details) {
        return ResponseEntity.status(code.getStatus()).body(ErrorResponse.of(code, message, details));
    }

    /** "register.request.email" -> "email" */
    private static String lastNode(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? path : path.substring(dot + 1);
    }
}
