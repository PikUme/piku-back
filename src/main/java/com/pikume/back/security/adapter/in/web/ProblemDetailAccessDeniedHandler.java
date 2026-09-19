package com.pikume.back.security.adapter.in.web;

import com.pikume.back.security.adapter.in.web.problem.SecurityProblemType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class ProblemDetailAccessDeniedHandler implements AccessDeniedHandler {

	private final SecurityProblemResponseWriter problemWriter;

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
			throws IOException {
		if (accessDeniedException instanceof ProfileSetupRequiredException) {
			problemWriter.write(request, response, SecurityProblemType.PROFILE_SETUP_REQUIRED, accessDeniedException.getMessage());
			return;
		}
		problemWriter.write(
				request,
				response,
				SecurityProblemType.FORBIDDEN,
				"권한이 없습니다.");
	}
}
