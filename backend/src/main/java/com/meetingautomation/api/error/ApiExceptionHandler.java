package com.meetingautomation.api.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import com.meetingautomation.api.audio.AudioChunkConflictException;
import com.meetingautomation.api.audio.AudioChunkInvalidException;
import com.meetingautomation.api.audio.AudioSessionNotFoundException;
import com.meetingautomation.api.audio.AudioStorageException;
import com.meetingautomation.api.audio.AudioNotAvailableException;
import com.meetingautomation.api.processing.FailureDocumentWriteException;
import com.meetingautomation.api.processing.AudioChunksIncompleteException;
import com.meetingautomation.api.session.SessionStateConflictException;
import com.meetingautomation.api.session.SessionVersionConflictException;
import com.meetingautomation.api.session.ReviewValidationException;
import com.meetingautomation.api.session.DeliveryRetryException;
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

    @ExceptionHandler(com.meetingautomation.notification.slack.SlackWebhookSettingsService.InvalidSlackWebhookException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidSlackWebhook(
            com.meetingautomation.notification.slack.SlackWebhookSettingsService.InvalidSlackWebhookException failure,
            HttpServletRequest request) {
        return errorResponse(HttpStatus.BAD_REQUEST, "SLACK_WEBHOOK_INVALID",
                "Slack Incoming Webhook URL을 확인해 주세요.", "VALIDATION", false, request, Map.of());
    }

    @ExceptionHandler(com.meetingautomation.email.EmailSettingsService.InvalidEmailSettingsException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidEmailSettings(
            com.meetingautomation.email.EmailSettingsService.InvalidEmailSettingsException failure,
            HttpServletRequest request) {
        return errorResponse(HttpStatus.BAD_REQUEST, "EMAIL_SETTINGS_INVALID",
                "Gmail OAuth2 설정값과 발신 이메일 주소를 확인해 주세요.", "VALIDATION", false, request, Map.of());
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

    @ExceptionHandler(ApiValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleApiValidationFailure(
            ApiValidationException exception,
            HttpServletRequest request) {
        BindException bindingResult = new BindException("request", "CreateMeetingSessionRequest");
        bindingResult.addError(new FieldError("request", exception.field(), "INVALID"));
        return handleValidationFailure(bindingResult, request);
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

    @ExceptionHandler(DeliveryRetryException.class)
    public ResponseEntity<ApiErrorResponse> handleDeliveryRetryFailure(
            DeliveryRetryException exception, HttpServletRequest request) {
        return switch (exception.reason()) {
            case NOT_FOUND -> errorResponse(HttpStatus.NOT_FOUND, "DELIVERY_NOT_FOUND",
                    "Delivery was not found for this meeting session.", "NOT_FOUND", false, request, Map.of());
            case NOT_RETRYABLE -> errorResponse(HttpStatus.CONFLICT, "DELIVERY_NOT_RETRYABLE",
                    "This delivery cannot be retried.", "CONFLICT", false, request, Map.of());
            case IN_PROGRESS -> errorResponse(HttpStatus.CONFLICT, "DELIVERY_RETRY_IN_PROGRESS",
                    "This delivery retry is already in progress.", "CONFLICT", false, request, Map.of());
        };
    }

    @ExceptionHandler(AudioChunksIncompleteException.class)
    public ResponseEntity<ApiErrorResponse> handleIncompleteAudioChunks(
            AudioChunksIncompleteException exception,
            HttpServletRequest request) {
        return errorResponse(HttpStatus.CONFLICT, "AUDIO_CHUNKS_INCOMPLETE",
                "모든 오디오 청크를 순서대로 업로드한 뒤 처리해 주세요.", "CONFLICT", false, request, Map.of());
    }

    @ExceptionHandler(SessionStateConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleSessionStateConflict(
            SessionStateConflictException exception,
            HttpServletRequest request) {
        return errorResponse(HttpStatus.CONFLICT, "SESSION_STATE_CONFLICT",
                "현재 회의 세션 상태에서는 요청을 처리할 수 없습니다.", "CONFLICT", false, request, Map.of());
    }

    @ExceptionHandler(SessionVersionConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleSessionVersionConflict(
            SessionVersionConflictException exception,
            HttpServletRequest request) {
        return errorResponse(HttpStatus.PRECONDITION_FAILED, "SESSION_VERSION_CONFLICT",
                "The session version no longer matches the request.", "CONFLICT", false, request, Map.of());
    }

    @ExceptionHandler(ReviewValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleReviewValidationFailure(
            ReviewValidationException exception,
            HttpServletRequest request) {
        return errorResponse(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "REVIEW_VALIDATION_FAILED",
                "회의록 검토가 완료되지 않았습니다.",
                "VALIDATION",
                false,
                request,
                Map.of("issues", exception.issues()));
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

    @ExceptionHandler(AudioChunkInvalidException.class)
    public ResponseEntity<ApiErrorResponse> handleAudioChunkInvalid(
            AudioChunkInvalidException exception,
            HttpServletRequest request) {
        return errorResponse(HttpStatus.BAD_REQUEST, "AUDIO_CHUNK_INVALID",
                "오디오 청크 요청을 확인해 주세요.", "VALIDATION", false, request, Map.of());
    }

    @ExceptionHandler(AudioChunkConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleAudioChunkConflict(
            AudioChunkConflictException exception,
            HttpServletRequest request) {
        return errorResponse(HttpStatus.CONFLICT, "AUDIO_CHUNK_CONFLICT",
                "같은 순서에 다른 오디오 청크가 이미 저장되어 있습니다.", "CONFLICT", false, request, Map.of());
    }

    @ExceptionHandler(AudioSessionNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleAudioSessionNotFound(
            AudioSessionNotFoundException exception,
            HttpServletRequest request) {
        return errorResponse(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND",
                "회의 세션을 찾을 수 없습니다.", "NOT_FOUND", false, request, Map.of());
    }

    @ExceptionHandler(AudioStorageException.class)
    public ResponseEntity<ApiErrorResponse> handleAudioStorageFailure(
            AudioStorageException exception,
            HttpServletRequest request) {
        return internalError(request);
    }

    @ExceptionHandler(AudioNotAvailableException.class)
    public ResponseEntity<ApiErrorResponse> handleAudioNotAvailable(
            AudioNotAvailableException exception, HttpServletRequest request) {
        return errorResponse(HttpStatus.CONFLICT, "AUDIO_NOT_AVAILABLE",
                "다운로드할 수 있는 오디오가 없습니다.", "CONFLICT", false, request, Map.of());
    }

    @ExceptionHandler(FailureDocumentWriteException.class)
    public ResponseEntity<ApiErrorResponse> handleFailureDocumentWrite(
            FailureDocumentWriteException exception, HttpServletRequest request) {
        return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, "DOCUMENT_FAILED",
                "실패 기록 문서를 저장하지 못했습니다. 다시 시도해 주세요.", "DOCUMENT_FAILURE", true, request, Map.of());
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
        return FailureContext.correlationId(request.getHeader(REQUEST_ID_HEADER));
    }

    private record InvalidField(String field, String code) {
    }
}
