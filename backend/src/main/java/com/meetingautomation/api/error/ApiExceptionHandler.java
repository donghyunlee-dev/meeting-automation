package com.meetingautomation.api.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.meetingautomation.document.DocumentProviderException;
import com.meetingautomation.api.idempotency.IdempotencyKeyConflictException;
import com.meetingautomation.api.participant.ParticipantNotFoundException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(com.meetingautomation.document.settings.SettingsException.class)
    public ResponseEntity<ApiErrorResponse> handleSettingsFailure(
            com.meetingautomation.document.settings.SettingsException failure, HttpServletRequest request) {
        return errorResponse(HttpStatusCode.valueOf(failure.status()), failure.code(), failure.getMessage(),
                failure.category(), false, request, Map.of());
    }

    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiErrorResponse> handleValidationFailure(
            BindException exception,
            HttpServletRequest request) {
        List<InvalidField> fieldErrors = exception.getBindingResult().getFieldErrors().stream()
                .map(ApiExceptionHandler::toInvalidField)
                .distinct()
                .sorted(Comparator.comparing(InvalidField::field).thenComparing(InvalidField::code))
                .toList();

        return errorResponse(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "요청 필드를 확인해 주세요.",
                "VALIDATION",
                false,
                request,
                validationDetails(fieldErrors));
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ApiErrorResponse> handleInvalidRequest(
            Exception exception,
            HttpServletRequest request) {
        Map<String, Object> details = exception instanceof MethodArgumentTypeMismatchException mismatch
                ? validationDetails(List.of(new InvalidField(
                        mismatch.getName() == null ? "parameter" : mismatch.getName(), "TypeMismatch")))
                : Map.of();
        return errorResponse(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "요청 내용을 확인해 주세요.",
                "VALIDATION",
                false,
                request,
                details);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(
            ConstraintViolationException exception,
            HttpServletRequest request) {
        List<InvalidField> fieldErrors = exception.getConstraintViolations().stream()
                .map(ApiExceptionHandler::toInvalidField)
                .toList();
        return errorResponse(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "요청 내용을 확인해 주세요.",
                "VALIDATION",
                false,
                request,
                validationDetails(fieldErrors));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleHandlerMethodValidationFailure(
            HandlerMethodValidationException exception,
            HttpServletRequest request) {
        if (exception.isForReturnValue()) {
            return internalError(request);
        }

        return errorResponse(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "요청 내용을 확인해 주세요.",
                "VALIDATION",
                false,
                request,
                validationDetails(validationErrors(exception)));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingResource(
            NoResourceFoundException exception,
            HttpServletRequest request) {
        return errorResponse(
                HttpStatus.NOT_FOUND,
                "NOT_FOUND",
                "요청한 리소스를 찾을 수 없습니다.",
                "NOT_FOUND",
                false,
                request,
                Map.of());
    }

    @ExceptionHandler(DocumentProviderException.class)
    public ResponseEntity<ApiErrorResponse> handleDocumentProviderFailure(
            DocumentProviderException exception,
            HttpServletRequest request) {
        return errorResponse(
                HttpStatusCode.valueOf(exception.statusCode()),
                exception.code(),
                exception.getMessage(),
                exception.category(),
                exception.retryable(),
                request,
                Map.of());
    }

    @ExceptionHandler(IdempotencyKeyConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleIdempotencyConflict(
            IdempotencyKeyConflictException exception,
            HttpServletRequest request) {
        return errorResponse(
                HttpStatus.CONFLICT,
                "IDEMPOTENCY_KEY_CONFLICT",
                "같은 요청 키가 다른 내용에 사용되었습니다.",
                "CONFLICT",
                false,
                request,
                Map.of());
    }

    @ExceptionHandler(ParticipantNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleParticipantNotFound(
            ParticipantNotFoundException exception,
            HttpServletRequest request) {
        return errorResponse(
                HttpStatus.NOT_FOUND,
                "PARTICIPANT_NOT_FOUND",
                "요청한 참가자를 찾을 수 없습니다.",
                "NOT_FOUND",
                false,
                request,
                Map.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpectedFailure(
            Exception exception,
            HttpServletRequest request) {
        if (exception instanceof ErrorResponse errorResponse
                && errorResponse.getStatusCode().is4xxClientError()) {
            return errorResponse(
                    errorResponse.getStatusCode(),
                    "VALIDATION_FAILED",
                    "요청 내용을 확인해 주세요.",
                    "VALIDATION",
                    false,
                    request,
                    Map.of());
        }

        return internalError(request);
    }

    private ResponseEntity<ApiErrorResponse> internalError(HttpServletRequest request) {
        return errorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "요청을 처리하는 중 오류가 발생했습니다.",
                "INTERNAL",
                false,
                request,
                Map.of());
    }

    private ResponseEntity<ApiErrorResponse> errorResponse(
            HttpStatusCode status,
            String code,
            String message,
            String category,
            boolean retryable,
            HttpServletRequest request,
            Map<String, Object> details) {
        ApiError error = new ApiError(
                code,
                message,
                category,
                retryable,
                traceId(request),
                details);
        return ResponseEntity.status(status).body(new ApiErrorResponse(error));
    }

    private static InvalidField toInvalidField(FieldError error) {
        String constraintCode = error.getCode() == null ? "INVALID" : error.getCode();
        return new InvalidField(error.getField(), constraintCode);
    }

    private static InvalidField toInvalidField(ConstraintViolation<?> violation) {
        String field = "request";
        for (jakarta.validation.Path.Node node : violation.getPropertyPath()) {
            if (node.getName() != null) {
                field = node.getName();
            }
        }
        String code = violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName();
        return new InvalidField(field, code);
    }

    private static List<InvalidField> validationErrors(HandlerMethodValidationException exception) {
        return exception.getParameterValidationResults().stream()
                .flatMap(ApiExceptionHandler::validationErrors)
                .distinct()
                .sorted(Comparator.comparing(InvalidField::field).thenComparing(InvalidField::code))
                .toList();
    }

    private static java.util.stream.Stream<InvalidField> validationErrors(ParameterValidationResult result) {
        if (result instanceof ParameterErrors parameterErrors) {
            return parameterErrors.getFieldErrors().stream().map(ApiExceptionHandler::toInvalidField);
        }

        String parameterName = result.getMethodParameter().getParameterName();
        String field = parameterName == null ? "parameter" : parameterName;
        return result.getResolvableErrors().stream()
                .map(error -> new InvalidField(field, validationCode(error)));
    }

    private static String validationCode(MessageSourceResolvable error) {
        String[] codes = error.getCodes();
        if (codes == null || codes.length == 0) {
            return "INVALID";
        }
        int qualifier = codes[0].indexOf('.');
        return qualifier < 0 ? codes[0] : codes[0].substring(0, qualifier);
    }

    private static Map<String, Object> validationDetails(List<InvalidField> fieldErrors) {
        return Map.of("fieldErrors", fieldErrors.stream()
                .distinct()
                .sorted(Comparator.comparing(InvalidField::field).thenComparing(InvalidField::code))
                .toList());
    }

    private static String traceId(HttpServletRequest request) {
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (requestId != null && !requestId.isBlank()) {
            return requestId;
        }
        return "tr_" + UUID.randomUUID();
    }

    private record InvalidField(String field, String code) {
    }
}
