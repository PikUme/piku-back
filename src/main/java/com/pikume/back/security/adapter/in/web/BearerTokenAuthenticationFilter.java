package com.pikume.back.security.adapter.in.web;

import com.pikume.back.security.adapter.out.token.JwtTokenProvider;
import com.pikume.back.security.principal.UserPrincipal;
import com.pikume.back.user.application.dto.UserIdentityView;
import com.pikume.back.user.application.port.in.QueryUserIdentityUseCase;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Slf4j
@RequiredArgsConstructor
public class BearerTokenAuthenticationFilter extends OncePerRequestFilter {

	private final JwtTokenProvider jwtTokenProvider;
	private final QueryUserIdentityUseCase queryUserIdentityUseCase;
	private final AuthenticationEntryPoint authenticationEntryPoint;

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String token = extractBearerToken(request);
		if (token == null) {
			chain.doFilter(request, response);
			return;
		}

		try {
			SecurityContextHolder.getContext().setAuthentication(authenticate(token));
		} catch (MissingJwtSubjectException exception) {
			log.warn("event=jwt_subject_missing outcome=denied reason=missing_user_id");
			reject(request, response, exception);
			return;
		} catch (Exception exception) {
			log.warn("event=jwt_filter_authentication_failed outcome=denied reason={}",
					exception.getClass().getSimpleName());
			reject(request, response, new BadCredentialsException("인증이 필요합니다."));
			return;
		}

		chain.doFilter(request, response);
	}

	private String extractBearerToken(HttpServletRequest request) {
		String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (authHeader == null || !authHeader.startsWith(AuthWebConstants.BEARER_PREFIX)) {
			return null;
		}
		return authHeader.substring(AuthWebConstants.BEARER_PREFIX.length());
	}

	private Authentication authenticate(String token) {
		if (!jwtTokenProvider.validateToken(token)) {
			throw new BadCredentialsException("인증이 필요합니다.");
		}

		String userId = requireUserId(token);
		UserIdentityView user = queryUserIdentityUseCase.queryUserIdentityById(userId)
				.orElseThrow(() -> new BadCredentialsException("인증이 필요합니다."));
		UserPrincipal userPrincipal = UserPrincipal.withProfileState(
				user.id(), user.nickname(), user.avatarReference(), user.profileSetupStatus(), user.characterId());
		return new UsernamePasswordAuthenticationToken(
				userPrincipal,
				null,
				userPrincipal.getAuthorities());
	}

	private String requireUserId(String token) {
		try {
			return jwtTokenProvider.getUserIdFromToken(token);
		} catch (BadCredentialsException exception) {
			throw new MissingJwtSubjectException(exception.getMessage(), exception);
		}
	}

	private void reject(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException exception) throws IOException, ServletException {
		authenticationEntryPoint.commence(request, response, exception);
	}

	private static class MissingJwtSubjectException extends BadCredentialsException {

		private MissingJwtSubjectException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
