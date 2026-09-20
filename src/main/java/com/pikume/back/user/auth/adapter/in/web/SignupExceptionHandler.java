package com.pikume.back.user.auth.adapter.in.web;

import com.pikume.back.global.error.ApiProblemType;
import com.pikume.back.global.error.ProblemDetailFactory;
import com.pikume.back.user.application.exception.SignupProfileException;
import com.pikume.back.user.auth.application.exception.GoogleAuthenticationException;
import com.pikume.back.user.auth.application.exception.InvalidCredentialsException;
import com.pikume.back.user.auth.application.exception.SignupFlowException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.net.URI;
import java.util.Locale;

@RestControllerAdvice(basePackages="com.pikume.back.user.auth.adapter.in.web")
@RequiredArgsConstructor
public class SignupExceptionHandler {
    private final ProblemDetailFactory problems;

    @ExceptionHandler(SignupFlowException.class)
    public ResponseEntity<ProblemDetail> signup(SignupFlowException error, HttpServletRequest request) {
        HttpStatus status = switch (error.getReason()) {
            case SIGNUP_DISABLED, DEFAULT_CHARACTER_UNAVAILABLE, EMAIL_SEND_FAILED -> HttpStatus.SERVICE_UNAVAILABLE;
            case LEGACY_SIGNUP_DISABLED, PROOF_EXPIRED, CODE_EXPIRED -> HttpStatus.GONE;
            case RATE_LIMITED, ATTEMPTS_EXHAUSTED -> HttpStatus.TOO_MANY_REQUESTS;
            case USER_UNAVAILABLE -> HttpStatus.UNAUTHORIZED;
            case PROOF_ALREADY_USED, AGREEMENT_VERSION_MISMATCH, EMAIL_ALREADY_REGISTERED, ACCOUNT_LINK_CONFLICT, NICKNAME_COLLISION -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        String detail = switch (error.getReason()) {
            case SIGNUP_DISABLED -> "현재 신규 회원가입을 이용할 수 없습니다.";
            case LEGACY_SIGNUP_DISABLED -> "새 회원가입 흐름을 이용해주세요.";
            case PROOF_EXPIRED -> "가입 인증이 만료되었습니다. 다시 인증해주세요.";
            case AGREEMENTS_REQUIRED -> "필수 약관에 동의해주세요.";
            case AGREEMENT_VERSION_MISMATCH -> "최신 약관을 확인한 후 다시 동의해주세요.";
            case EMAIL_REQUIRED -> "서비스에서 사용할 이메일을 인증해주세요.";
            case EMAIL_ALREADY_REGISTERED, ACCOUNT_LINK_CONFLICT -> "이미 등록된 계정입니다. 기존 로그인 방법을 이용해주세요.";
            case CODE_MISMATCH -> "인증 코드가 일치하지 않습니다.";
            case CODE_EXPIRED -> "인증 코드가 만료되었습니다.";
            case RATE_LIMITED, ATTEMPTS_EXHAUSTED -> "요청 한도를 초과했습니다. 잠시 후 다시 시도해주세요.";
            case EMAIL_SEND_FAILED -> "인증 이메일을 발송하지 못했습니다. 다시 요청해주세요.";
            case DEFAULT_CHARACTER_UNAVAILABLE -> "기본 캐릭터를 준비하지 못했습니다. 잠시 후 다시 시도해주세요.";
            case USER_UNAVAILABLE -> "사용할 수 없는 계정입니다.";
            default -> "가입 요청을 확인해주세요. 인증을 다시 시작해야 할 수 있습니다.";
        };
        return response(error.getReason().name(), status, detail, request);
    }

    @ExceptionHandler(SignupProfileException.class)
    public ResponseEntity<ProblemDetail> profile(SignupProfileException error, HttpServletRequest request) {
        HttpStatus status = switch(error.getFailure()) {
            case USER_UNAVAILABLE -> HttpStatus.UNAUTHORIZED;
            case PROFILE_SETUP_REQUIRED -> HttpStatus.FORBIDDEN;
            case NICKNAME_UNAVAILABLE, HOLD_REQUIRED, PROFILE_ALREADY_COMPLETED -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        String detail = switch(error.getFailure()) {
            case INVALID_NICKNAME -> "사용할 수 없는 닉네임입니다.";
            case NICKNAME_UNAVAILABLE -> "이미 사용 중이거나 다른 회원이 점유한 닉네임입니다.";
            case HOLD_REQUIRED -> "닉네임 중복 검사를 다시 수행해주세요.";
            case PROFILE_ALREADY_COMPLETED -> "이미 프로필 설정이 완료되었습니다.";
            case PROFILE_SETUP_REQUIRED -> "가입 프로필 설정을 먼저 완료해주세요.";
            case USER_UNAVAILABLE -> "사용할 수 없는 계정입니다.";
            case INVALID_CHARACTER -> "선택할 수 없는 캐릭터입니다.";
        };
        return response(error.getFailure().name(), status, detail, request);
    }

    @ExceptionHandler(SignupWebException.class)
    public ResponseEntity<ProblemDetail> web(SignupWebException error, HttpServletRequest request) {
        HttpStatus status = switch(error.getReason()) {
            case ORIGIN_FORBIDDEN, CSRF_INVALID -> HttpStatus.FORBIDDEN;
            case CONFIGURATION -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_REQUEST;
        };
        return response(error.getReason().name(),status,"가입 요청의 출처와 인증 정보를 확인해주세요.",request);
    }

    @ExceptionHandler(GoogleAuthenticationException.class)
    public ResponseEntity<ProblemDetail> google(GoogleAuthenticationException error, HttpServletRequest request) {
        HttpStatus status = error.getReason() == GoogleAuthenticationException.Reason.INVALID_IDENTITY ? HttpStatus.UNAUTHORIZED : HttpStatus.SERVICE_UNAVAILABLE;
        return response("GOOGLE_" + error.getReason().name(),status,"Google 인증을 완료하지 못했습니다. 로그인을 다시 시작해주세요.",request);
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ProblemDetail> credentials(InvalidCredentialsException error, HttpServletRequest request) {
        return response("INVALID_CREDENTIALS",HttpStatus.UNAUTHORIZED,"기존 로그인 방법으로 본인 인증을 다시 수행해주세요.",request);
    }

    @ExceptionHandler(com.pikume.back.user.auth.domain.exception.OAuthRequestException.class)
    public ResponseEntity<ProblemDetail> oauth(com.pikume.back.user.auth.domain.exception.OAuthRequestException error, HttpServletRequest request) {
        HttpStatus status = switch (error.getReason()) {
            case DISABLED, CONFIGURATION -> HttpStatus.SERVICE_UNAVAILABLE;
            case EXPIRED -> HttpStatus.GONE;
            case REPLAY -> HttpStatus.CONFLICT;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.BAD_REQUEST;
        };
        return response("OAUTH_" + error.getReason().name(),status,"Google 로그인 요청을 다시 시작해주세요.",request);
    }

    private ResponseEntity<ProblemDetail> response(String code, HttpStatus status, String detail, HttpServletRequest request) {
        var descriptor = new SignupProblem(URI.create("https://api.pikume.com/problems/signup/" + code.toLowerCase(Locale.ROOT).replace('_','-')),status,status.getReasonPhrase());
        ProblemDetail problem = problems.create(descriptor,detail,request.getRequestURI());
        problem.setProperty("code",code);
        if ("PROFILE_SETUP_REQUIRED".equals(code)) problem.setProperty("nextAction","PROFILE");
        if ("PROOF_EXPIRED".equals(code) || "LEGACY_SIGNUP_DISABLED".equals(code)) problem.setProperty("nextAction","AUTHENTICATE");
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).cacheControl(CacheControl.noStore()).body(problem);
    }
    private record SignupProblem(URI type, HttpStatus status, String title) implements ApiProblemType {}
}
