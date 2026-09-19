package com.pikume.back.user.auth.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pikume.back.security.principal.UserPrincipal;
import com.pikume.back.user.application.dto.SignupNicknameReservation;
import com.pikume.back.user.application.dto.SignupProfileResult;
import com.pikume.back.user.application.port.in.*;
import com.pikume.back.user.auth.application.dto.*;
import com.pikume.back.user.auth.application.exception.InvalidCredentialsException;
import com.pikume.back.user.auth.application.exception.SignupFailure;
import com.pikume.back.user.auth.application.exception.SignupFlowException;
import com.pikume.back.user.auth.application.port.in.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** Screen submission APIs share use cases; each ingress uses its own credential transport. */
@RestController
@RequestMapping({"/api/auth/signup", "/api/mobile/auth/signup"})
@RequiredArgsConstructor
public class SignupController {
    private final SignupFlowUseCase signup;
    private final QuerySignupAgreementUseCase agreements;
    private final QuerySignupConfigurationUseCase configuration;
    private final QueryUserAccessUseCase users;
    private final ReserveSignupNicknameUseCase nicknames;
    private final CompleteSignupProfileUseCase profiles;
    private final WithdrawPendingSignupUseCase withdrawals;
    private final SignupWebCredentials credentials;
    private final SignupSessionResponseWriter sessions;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProgressResponse(boolean enabled, SignupProgress progress, String callerBinding, String csrfToken) {}
    public record EmailCodeRequest(@NotBlank @Email @Size(max=255) String email, @Size(max=36) String challengeId,
        boolean restartAuthentication) {}
    public record EmailAuthenticationRequest(@NotBlank String challengeId, @NotBlank @Email String email,
        @Pattern(regexp="[0-9]{6}") @NotNull String code, @NotBlank @Size(max=72) String password) {}
    public record SocialEmailRequest(@NotBlank String challengeId, @NotBlank @Email String email,
        @Pattern(regexp="[0-9]{6}") @NotNull String code) {}
    public record AgreementsRequest(@NotEmpty @Size(max=20) List<@NotNull AgreementAcceptance> agreements) {}
    public record NicknameRequest(@io.swagger.v3.oas.annotations.media.Schema(requiredMode=io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED, description="앞뒤 공백 정리 후 1~20자의 닉네임") String nickname) {}
    public record ProfileRequest(@io.swagger.v3.oas.annotations.media.Schema(requiredMode=io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED, description="예약한 닉네임. 앞뒤 공백 정리 후 1~20자") String nickname, @NotNull @Positive Long characterId) {}

    @GetMapping("/progress")
    public ProgressResponse progress(@AuthenticationPrincipal UserPrincipal principal, HttpServletRequest request, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        boolean mobile = SignupWebCredentials.mobile(request);
        String binding;
        String csrf = null;
        if (mobile) binding = credentials.mobileBootstrap(request);
        else { var context = credentials.bootstrap(request,response); binding = context.binding(); csrf = context.csrf(); }
        SignupProgress progress;
        if (principal != null) {
            var user = users.queryUserAccess(principal.getId()).filter(value -> !value.withdrawn()).orElseThrow(InvalidCredentialsException::new);
            boolean pending = "REQUIRED".equals(user.profileSetupStatus().name());
            progress = new SignupProgress(pending ? SignupNextAction.PROFILE : SignupNextAction.COMPLETE,
                null, user.id(), user.profileSetupStatus().name(), null);
        } else progress = anonymousProgress(request, response, binding);
        return new ProgressResponse(configuration.querySignupConfiguration().enabled(), progress, mobile ? binding : null, csrf);
    }

    private SignupProgress anonymousProgress(HttpServletRequest request, HttpServletResponse response, String binding) {
        String proof = credentials.proof(request);
        try {
            SignupProgress progress = signup.progress(proof, binding);
            if (!SignupWebCredentials.mobile(request) && proof == null && credentials.hasProofCookie(request)) {
                credentials.clearProof(response);
            }
            if (progress.userId() != null) {
                return new SignupProgress(SignupNextAction.AUTHENTICATE, progress.email(), progress.userId(),
                    progress.profileSetupStatus(), progress.expiresAt());
            }
            return progress;
        } catch (SignupFlowException failure) {
            if (failure.getReason() != SignupFailure.PROOF_INVALID && failure.getReason() != SignupFailure.PROOF_EXPIRED
                && failure.getReason() != SignupFailure.FLOW_MISMATCH && failure.getReason() != SignupFailure.USER_UNAVAILABLE) {
                throw failure;
            }
            if (!SignupWebCredentials.mobile(request)) credentials.clearProof(response);
            return new SignupProgress(SignupNextAction.AUTHENTICATE, null, null, null, null);
        }
    }

    @GetMapping("/agreements")
    public List<SignupAgreementDocument> agreements(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return agreements.querySignupAgreements();
    }

    @PostMapping("/email/code")
    public EmailSignupChallengeResult sendCode(@Valid @RequestBody EmailCodeRequest body, HttpServletRequest request, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        var result = signup.sendEmailCode(new EmailSignupChallengeCommand(body.email(), bindingForWrite(request), request.getRemoteAddr(),
            body.restartAuthentication() ? null : body.challengeId(), body.restartAuthentication() ? null : credentials.proof(request)));
        if (body.restartAuthentication() && !SignupWebCredentials.mobile(request)) credentials.clearProof(response);
        return result;
    }

    @PostMapping("/email")
    public SignupSessionResponseWriter.StepResponse authenticateEmail(@Valid @RequestBody EmailAuthenticationRequest body,
        HttpServletRequest request, HttpServletResponse response) {
        var result = signup.authenticateEmail(new EmailSignupAuthenticationCommand(body.challengeId(), body.email(), body.code(), body.password(), bindingForWrite(request)));
        return sessions.write(result, null, SignupWebCredentials.mobile(request), response);
    }

    @PostMapping("/social/email")
    public SignupSessionResponseWriter.StepResponse verifySocialEmail(@Valid @RequestBody SocialEmailRequest body,
        HttpServletRequest request, HttpServletResponse response) {
        String binding = bindingForWrite(request);
        var result = signup.verifySocialEmail(new SocialSignupEmailCommand(credentials.requireProof(request), body.challengeId(), body.email(), body.code(), binding));
        return sessions.write(result, null, SignupWebCredentials.mobile(request), response);
    }

    @PostMapping("/agreements")
    public SignupSessionResponseWriter.StepResponse agree(@Valid @RequestBody AgreementsRequest body,
        HttpServletRequest request, HttpServletResponse response) {
        String binding = bindingForWrite(request);
        String device = credentials.requireDevice(request);
        var result = signup.agree(new SignupAgreementCommand(credentials.requireProof(request),binding,body.agreements()));
        return sessions.write(result, device, SignupWebCredentials.mobile(request), response);
    }

    @PostMapping("/nickname")
    public SignupNicknameReservation reserve(@AuthenticationPrincipal UserPrincipal principal, @Valid @RequestBody NicknameRequest body,
        HttpServletRequest request, HttpServletResponse response) {
        bindingForWrite(request);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return nicknames.reserveSignupNickname(userId(principal),body.nickname());
    }

    @PostMapping("/profile")
    public SignupProfileResult complete(@AuthenticationPrincipal UserPrincipal principal, @Valid @RequestBody ProfileRequest body,
        HttpServletRequest request, HttpServletResponse response) {
        bindingForWrite(request);
        var result = profiles.completeSignupProfile(userId(principal),body.nickname(),body.characterId());
        if (!SignupWebCredentials.mobile(request)) credentials.clearProof(response);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return result;
    }

    @DeleteMapping("/profile")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void withdraw(@AuthenticationPrincipal UserPrincipal principal, HttpServletRequest request, HttpServletResponse response) {
        bindingForWrite(request);
        withdrawals.withdrawPendingSignup(userId(principal));
        if (!SignupWebCredentials.mobile(request)) { credentials.clearProof(response); sessions.clearRefresh(response); }
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    private String bindingForWrite(HttpServletRequest request) {
        return SignupWebCredentials.mobile(request) ? credentials.requireBinding(request) : credentials.requireMutation(request);
    }
    private String userId(UserPrincipal principal) {
        if (principal == null || principal.getId() == null) throw new InvalidCredentialsException();
        return principal.getId();
    }
}
