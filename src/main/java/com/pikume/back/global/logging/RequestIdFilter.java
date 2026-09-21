package com.pikume.back.global.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.web.context.request.async.WebAsyncUtils;
import org.springframework.web.filter.GenericFilterBean;

import java.io.IOException;
import java.util.Enumeration;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
public final class RequestIdFilter extends GenericFilterBean {
    public static final String HEADER_NAME = "X-Request-Id";
    private static final String CONTEXT_ATTRIBUTE = RequestIdFilter.class.getName() + ".context";
    private static final Pattern VALID_REQUEST_ID = Pattern.compile("[0-9a-fA-F]{32}");

    private final boolean production;
    private final String managementBasePath;

    public RequestIdFilter(Environment environment) {
        production = environment.acceptsProfiles(Profiles.of("prod"));
        managementBasePath = environment.getProperty("management.endpoints.web.base-path", "/actuator")
                .replaceAll("/+$", "");
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        Resolution resolution = resolve(httpRequest);
        RequestIdContext context = resolution.context();
        httpResponse.setHeader(HEADER_NAME, context.requestId());

        try (RequestIdContext.Scope ignored = context.open()) {
            if (resolution.reason() != null) {
                if (production && !isManagementRequest(httpRequest)) {
                    log.warn("event=request_id_generated reason={}", resolution.reason());
                } else {
                    log.debug("event=request_id_generated reason={}", resolution.reason());
                }
            }
            WebAsyncUtils.getAsyncManager(httpRequest).registerCallableInterceptor(
                    CONTEXT_ATTRIBUTE, new RequestIdCallableInterceptor(context));
            chain.doFilter(request, response);
        }
    }

    private Resolution resolve(HttpServletRequest request) {
        synchronized (request) {
            if (request.getAttribute(CONTEXT_ATTRIBUTE) instanceof RequestIdContext existing) {
                return new Resolution(existing, null);
            }
            Enumeration<String> headers = request.getHeaders(HEADER_NAME);
            String header = headers != null && headers.hasMoreElements() ? headers.nextElement() : null;
            boolean valid = header != null && VALID_REQUEST_ID.matcher(header).matches() && !headers.hasMoreElements();
            RequestIdContext context = new RequestIdContext(valid ? header : UUID.randomUUID().toString().replace("-", ""));
            request.setAttribute(CONTEXT_ATTRIBUTE, context);
            return new Resolution(context, valid ? null : header == null ? "missing_header" : "invalid_header");
        }
    }

    private boolean isManagementRequest(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        // A root management base path must not exempt every application endpoint.
        return !managementBasePath.isEmpty()
                && (path.equals(managementBasePath) || path.startsWith(managementBasePath + "/"));
    }

    private record Resolution(RequestIdContext context, String reason) {
    }
}
