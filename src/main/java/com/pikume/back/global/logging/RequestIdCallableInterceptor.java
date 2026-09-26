package com.pikume.back.global.logging;

import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.async.CallableProcessingInterceptor;

import java.util.concurrent.Callable;

final class RequestIdCallableInterceptor implements CallableProcessingInterceptor {
    private final RequestIdContext context;
    private final ThreadLocal<RequestIdContext.Scope> scope = new ThreadLocal<>();

    RequestIdCallableInterceptor(RequestIdContext context) {
        this.context = context;
    }

    @Override
    public <T> void preProcess(NativeWebRequest request, Callable<T> task) {
        scope.set(context.open());
    }

    @Override
    public <T> void postProcess(NativeWebRequest request, Callable<T> task, Object result) {
        RequestIdContext.Scope current = scope.get();
        try {
            if (current != null) {
                current.close();
            }
        } finally {
            scope.remove();
        }
    }
}
