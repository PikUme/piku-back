package com.pikume.back.user.auth.adapter.in.web;

import com.pikume.back.security.principal.UserPrincipal;
import com.pikume.back.user.auth.application.dto.GoogleAuthorizationStart;
import com.pikume.back.user.auth.application.dto.GoogleNativeChallenge;
import com.pikume.back.user.auth.application.port.in.GoogleAuthenticationUseCase;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;
import java.net.URI;
import com.pikume.back.user.auth.application.exception.GoogleAuthenticationException;
import com.pikume.back.user.auth.application.exception.SignupFlowException;
import com.pikume.back.user.auth.application.exception.InvalidCredentialsException;
import com.pikume.back.user.auth.domain.exception.OAuthRequestException;

@RestController
@RequiredArgsConstructor
public class GoogleAuthenticationController {
    private final GoogleAuthenticationUseCase google;
    private final SignupWebCredentials credentials;
    private final SignupSessionResponseWriter sessions;
    private final SignupWebSettings settings;
    public record WebStartRequest(boolean link, @Size(max=72) String password) {}
    public record MobileStartRequest(@NotBlank @Size(max=64) String registration, boolean link, @Size(max=72) String password) {}
    public record MobileCompleteRequest(@NotBlank @Size(max=128) String state, @NotBlank @Size(max=16000) String idToken) {}

    @PostMapping("/api/auth/oauth/google/start")
    public GoogleAuthorizationStart startWeb(@Valid @RequestBody WebStartRequest body, @AuthenticationPrincipal UserPrincipal principal,
        HttpServletRequest request, HttpServletResponse response) {
        String binding = credentials.requireMutation(request);
        settings.completion();
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return google.startWeb(binding, credentials.requireDevice(request), target(body.link(), principal), body.password(), request.getRemoteAddr());
    }

    @GetMapping("/api/auth/oauth/google/callback")
    public ResponseEntity<Void> callback(@RequestParam String state, @RequestParam(required=false) String code,
        @RequestParam(required=false) String error, HttpServletRequest request, HttpServletResponse response) {
        var destination = settings.completion();
        try {
            String binding = credentials.requireBinding(request);
            if (error != null || code == null || code.isBlank()) {
                google.failWeb(state,binding);
                return redirectFailure(destination,"access_denied".equals(error) ? "GOOGLE_CANCELLED" : "GOOGLE_INVALID_IDENTITY");
            }
            var result = google.completeWeb(state,code,binding);
            sessions.write(result.signup(),result.deviceId(),false,response);
            return redirect(destination);
        } catch (OAuthRequestException failure) {
            return redirectFailure(destination,"OAUTH_" + failure.getReason().name());
        } catch (GoogleAuthenticationException failure) {
            return redirectFailure(destination,"GOOGLE_" + failure.getReason().name());
        } catch (SignupFlowException failure) {
            return redirectFailure(destination,failure.getReason().name());
        } catch (SignupWebException failure) {
            return redirectFailure(destination,failure.getReason().name());
        } catch (InvalidCredentialsException failure) {
            return redirectFailure(destination,"INVALID_CREDENTIALS");
        }
    }

    @PostMapping("/api/mobile/auth/oauth/google/challenge")
    public GoogleNativeChallenge startMobile(@Valid @RequestBody MobileStartRequest body, @AuthenticationPrincipal UserPrincipal principal,
        HttpServletRequest request, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return google.startMobile(body.registration(),credentials.requireBinding(request),credentials.requireDevice(request),
            target(body.link(),principal),body.password(),request.getRemoteAddr());
    }

    @PostMapping("/api/mobile/auth/oauth/google/complete")
    public SignupSessionResponseWriter.StepResponse completeMobile(@Valid @RequestBody MobileCompleteRequest body,
        HttpServletRequest request, HttpServletResponse response) {
        var result = google.completeMobile(body.state(),body.idToken(),credentials.requireBinding(request));
        return sessions.write(result.signup(),result.deviceId(),true,response);
    }

    private String target(boolean link, UserPrincipal principal) {
        if (!link) return null;
        if (principal == null || principal.getId() == null) throw new com.pikume.back.user.auth.application.exception.InvalidCredentialsException();
        return principal.getId();
    }

    private ResponseEntity<Void> redirectFailure(URI destination, String publicCode) {
        // Only server-owned codes enter the URL; never propagate provider error text or credentials.
        return redirect(UriComponentsBuilder.fromUri(destination).queryParam("oauthError",publicCode).build().toUri());
    }

    private ResponseEntity<Void> redirect(URI destination) {
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(destination).cacheControl(CacheControl.noStore())
            .header("Referrer-Policy","no-referrer").build();
    }
}
