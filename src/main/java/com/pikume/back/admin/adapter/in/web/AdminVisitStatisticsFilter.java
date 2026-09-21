package com.pikume.back.admin.adapter.in.web;

import com.pikume.back.admin.application.port.in.RecordAdminStatisticsEventUseCase;
import com.pikume.back.admin.domain.AdminStatisticsEventType;
import com.pikume.back.security.principal.UserPrincipal;
import com.pikume.back.global.util.RequestUtil;
import com.pikume.back.security.config.AdminSecurityChainExtension;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Component
@RequiredArgsConstructor
@Slf4j
public class AdminVisitStatisticsFilter extends OncePerRequestFilter implements AdminSecurityChainExtension {

	private static final String API_PREFIX = "/api/";
	private static final String ADMIN_PREFIX = "/api/admin/";
	private static final String VISITOR_ID_HEADER = "vid";

	private final RecordAdminStatisticsEventUseCase recordAdminStatisticsEventUseCase;

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		if (shouldRecord(request)) {
			recordVisit(request);
		}
		filterChain.doFilter(request, response);
	}

	private boolean shouldRecord(HttpServletRequest request) {
		String path = request.getRequestURI();
		return path != null
				&& path.startsWith(API_PREFIX)
				&& !path.startsWith(ADMIN_PREFIX)
				&& !"OPTIONS".equalsIgnoreCase(request.getMethod());
	}

	private void recordVisit(HttpServletRequest request) {
		try {
			String userId = currentUserId();
			String visitorKey = userId != null ? hash("user:" + userId) : anonymousVisitorKey(request);
			recordAdminStatisticsEventUseCase.record(AdminStatisticsEventType.VISIT, userId, visitorKey);
		} catch (RuntimeException e) {
			log.warn("event=visit_statistics_record_failed outcome=failed exception={}", e.getClass().getSimpleName());
		}
	}

	private String currentUserId() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal userDetails)) {
			return null;
		}
		return userDetails.getId();
	}

	private String anonymousVisitorKey(HttpServletRequest request) {
		String explicitVisitorId = request.getHeader(VISITOR_ID_HEADER);
		if (explicitVisitorId != null && !explicitVisitorId.isBlank()) {
			return hash("visitor:" + explicitVisitorId.trim());
		}
		String raw = RequestUtil.getClientIp(request) + "|" + request.getHeader(HttpHeaders.USER_AGENT);
		return hash("anonymous:" + raw);
	}

	private String hash(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception e) {
			throw new IllegalStateException("방문자 식별자를 해시할 수 없습니다.", e);
		}
	}
}
