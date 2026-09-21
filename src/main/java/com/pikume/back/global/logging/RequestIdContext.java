package com.pikume.back.global.logging;

import org.slf4j.MDC;

import java.util.Map;
import java.util.function.Consumer;

/** Captures only the request identifier, without propagating unrelated MDC fields. */
public record RequestIdContext(String requestId) {
    public static final String MDC_KEY = "requestId";

    public static RequestIdContext capture() {
        return new RequestIdContext(MDC.get(MDC_KEY));
    }

    public Scope open() {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        if (requestId == null) {
            MDC.remove(MDC_KEY);
        } else {
            MDC.put(MDC_KEY, requestId);
        }
        return new Scope(previous);
    }

    public Runnable wrap(Runnable action) {
        return () -> {
            try (Scope ignored = open()) {
                action.run();
            }
        };
    }

    public <T> Consumer<T> wrap(Consumer<T> action) {
        return value -> {
            try (Scope ignored = open()) {
                action.accept(value);
            }
        };
    }

    public static final class Scope implements AutoCloseable {
        private final Map<String, String> previous;

        private Scope(Map<String, String> previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (previous == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(previous);
            }
        }
    }
}
