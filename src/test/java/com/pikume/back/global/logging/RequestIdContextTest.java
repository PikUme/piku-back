package com.pikume.back.global.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

import java.util.Map;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestIdContextTest {
    @AfterEach
    void clearContext() {
        MDC.clear();
    }

    @Test
    void capturedContextOnlyPropagatesRequestIdAndRestoresWorkerAfterFailure() {
        MDC.put("requestId", "request-context");
        MDC.put("unrelated", "caller-value");
        var context = RequestIdContext.capture();
        MDC.setContextMap(Map.of("requestId", "worker-context", "worker", "preserved"));

        assertThatThrownBy(context.wrap(() -> {
            assertThat(MDC.get("requestId")).isEqualTo("request-context");
            assertThat(MDC.get("unrelated")).isNull();
            MDC.put("temporary", "removed-after-scope");
            throw new IllegalStateException("task failed");
        })::run).isInstanceOf(IllegalStateException.class);

        assertThat(MDC.getCopyOfContextMap()).containsExactlyInAnyOrderEntriesOf(
                Map.of("requestId", "worker-context", "worker", "preserved"));
    }

    @Test
    void taskCapturedOutsideARequestDoesNotBorrowWorkerRequestId() {
        var context = RequestIdContext.capture();
        MDC.put("requestId", "worker-context");

        context.wrap(() -> assertThat(MDC.get("requestId")).isNull()).run();

        assertThat(MDC.get("requestId")).isEqualTo("worker-context");
    }

    @Test
    void callableProcessingRestoresWorkerContextAfterExceptionalResult() {
        var interceptor = new RequestIdCallableInterceptor(new RequestIdContext("request-context"));
        var request = new ServletWebRequest(new MockHttpServletRequest());
        Callable<String> callable = () -> MDC.get("requestId");
        MDC.put("requestId", "worker-context");

        interceptor.preProcess(request, callable);
        assertThat(MDC.get("requestId")).isEqualTo("request-context");
        interceptor.postProcess(request, callable, new IllegalStateException("task failed"));

        assertThat(MDC.get("requestId")).isEqualTo("worker-context");
    }
}
