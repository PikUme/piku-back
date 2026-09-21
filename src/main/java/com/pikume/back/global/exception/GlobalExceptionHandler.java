package com.pikume.back.global.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import com.pikume.back.global.error.ApiProblemType;
import com.pikume.back.global.error.CommonProblemType;
import com.pikume.back.global.error.ProblemDetailFactory;
import com.pikume.back.global.error.ValidationProblemType;
import com.pikume.back.global.notification.DiscordWebhookService;

import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final Optional<DiscordWebhookService> discordWebhookService;
    private final ProblemDetailFactory problemDetailFactory;

    @Autowired
    public GlobalExceptionHandler(Optional<DiscordWebhookService> discordWebhookService,
            ProblemDetailFactory problemDetailFactory) {
        this.discordWebhookService = discordWebhookService;
        this.problemDetailFactory = problemDetailFactory;
    }

    public GlobalExceptionHandler(Optional<DiscordWebhookService> discordWebhookService) {
        this(discordWebhookService, new ProblemDetailFactory());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidationException(MethodArgumentNotValidException e,
            HttpServletRequest request) {
        Map<String, String> errors = e.getBindingResult()
                .getFieldErrors()
                .stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        error -> error.getDefaultMessage() != null
                                ? error.getDefaultMessage()
                                : "입력값을 확인해주세요.",
                        (firstMessage, ignored) -> firstMessage));
        log.debug("event=request_validation_failed outcome=denied reason=invalid_request fieldCount={}",
                errors.size());

        return buildValidationProblem("요청 값이 올바르지 않습니다.", errors, request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ProblemDetail> handleMissingParams(MissingServletRequestParameterException ex,
            HttpServletRequest request) {
        Map<String, String> errors = Map.of(
                ex.getParameterName(),
                String.format("'%s' parameter of type '%s' is missing", ex.getParameterName(), ex.getParameterType()));
        return buildValidationProblem("요청 값이 올바르지 않습니다.", errors, request);
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ProblemDetail> handleMissingRequestPart(MissingServletRequestPartException ex,
            HttpServletRequest request) {
        Map<String, String> errors = Map.of(ex.getRequestPartName(), "필수 요청 파트가 없습니다.");
        log.debug("event=request_validation_failed outcome=denied reason=missing_request_part fieldCount=1");
        return buildValidationProblem("요청 값이 올바르지 않습니다.", errors, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolationException(ConstraintViolationException e,
            HttpServletRequest request) {
        Map<String, String> errors = e.getConstraintViolations().stream()
                .collect(Collectors.groupingBy(
                        violation -> violation.getPropertyPath().toString(),
                        TreeMap::new,
                        Collectors.collectingAndThen(
                                Collectors.mapping(
                                        ConstraintViolation::getMessage,
                                        Collectors.toCollection(TreeSet::new)),
                                messages -> String.join(", ", messages))));
        log.debug("event=request_validation_failed outcome=denied reason=constraint_violation fieldCount={}",
                errors.size());

        return buildValidationProblem("요청 값이 올바르지 않습니다.", errors, request);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ProblemDetail> handleMethodValidationException(HandlerMethodValidationException e,
            HttpServletRequest request) {
        Map<String, String> errors = new HashMap<>();
        for (ParameterValidationResult result : e.getParameterValidationResults()) {
            String parameterName = result.getMethodParameter().getParameterName();
            String message = result.getResolvableErrors().stream()
                    .map(MessageSourceResolvable::getDefaultMessage)
                    .collect(Collectors.joining(", "));
            errors.put(parameterName, message);
        }
        log.debug("event=request_validation_failed outcome=denied reason=invalid_method_argument fieldCount={}",
                errors.size());

        return buildValidationProblem("요청 값이 올바르지 않습니다.", errors, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleHttpMessageNotReadable(HttpMessageNotReadableException ignored,
            HttpServletRequest request) {
        log.debug("event=request_body_parse_failed outcome=denied reason=malformed_request");
        return buildProblem(CommonProblemType.MALFORMED_REQUEST, "요청 본문을 해석할 수 없습니다.", request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemDetail> handleNoResourceFoundException(NoResourceFoundException e,
            HttpServletRequest request) {
        log.warn("event=request_failed outcome=not_found reason=resource_not_found status=404");
        return buildProblem(CommonProblemType.RESOURCE_NOT_FOUND, "요청한 리소스를 찾을 수 없습니다.", request);
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<ProblemDetail> handleIOException(IOException ex, HttpServletRequest request) {
        if (isClientDisconnected(ex)) {
            // 클라이언트가 스트림 중간에 연결을 끊은 케이스
            log.debug("event=request_stream_closed outcome=disconnected reason=client_disconnect");
            return ResponseEntity.noContent().build();
        }

        // 그 외 IOException은 다시 던져서 기본 처리
        discordWebhookService.ifPresent(service -> service.sendExceptionNotification(ex, request));
        log.error("event=request_failed outcome=failed reason=io_exception exception={}",
                ex.getClass().getSimpleName());
        return buildProblem(CommonProblemType.INTERNAL_SERVER_ERROR, "파일 처리 중 오류가 발생했습니다.", request);
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncRequestNotUsableException(AsyncRequestNotUsableException e, HttpServletRequest request) {
        if (isClientDisconnected(e)) {
            log.debug("event=async_request_stream_closed outcome=disconnected reason=client_disconnect");
            return;
        }

        log.error("event=request_failed outcome=failed reason=async_request_unusable exception={}",
                e.getClass().getSimpleName());
    }

    private boolean isClientDisconnected(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (isConnectionReset(current.getMessage())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isConnectionReset(String message) {
        if (message == null) {
            return false;
        }

        String normalizedMessage = message.toLowerCase(Locale.ROOT);
        // OS나 JDK에 따라 메시지가 다를 수 있으므로 유사 패턴 포함
        return normalizedMessage.contains("connection reset by peer")
                || normalizedMessage.contains("broken pipe")
                || normalizedMessage.contains("an existing connection was forcibly closed")
                || normalizedMessage.contains("connection aborted")
                || normalizedMessage.contains("aborted by the software in your host machine")
                || (message.contains("현재 연결은 사용자의 호스트 시스템") && message.contains("중단되었습니다"));
    }

    private ResponseEntity<ProblemDetail> buildProblem(ApiProblemType problemType, String detail,
            HttpServletRequest request) {
        ProblemDetail problemDetail = problemDetailFactory.create(problemType, detail, request.getRequestURI());
        return ResponseEntity.status(problemType.status()).body(problemDetail);
    }

    private ResponseEntity<ProblemDetail> buildValidationProblem(String detail, Map<String, String> fieldErrors,
            HttpServletRequest request) {
        ProblemDetail problemDetail = problemDetailFactory.validation(detail, request.getRequestURI(), fieldErrors);
        return ResponseEntity.status(ValidationProblemType.INVALID_REQUEST.status()).body(problemDetail);
    }

}
