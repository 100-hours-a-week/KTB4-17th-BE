package com.team.dating_backend.chat.monitoring;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

// 전환 중 처리 중인 HTTP 요청이 남아 있는지 확인하기 위한 지표다.
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class MigrationHttpMetricsFilter extends OncePerRequestFilter implements MeterBinder {

    private final AtomicInteger activeRequests = new AtomicInteger();

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("chat.http.requests.active", activeRequests, AtomicInteger::get)
            .description("In-flight HTTP requests, excluding actuator and WebSocket upgrades")
            .register(registry);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.equals("/actuator") || path.startsWith("/actuator/")
            || "websocket".equalsIgnoreCase(request.getHeader("Upgrade"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
        FilterChain filterChain) throws ServletException, IOException {
        activeRequests.incrementAndGet();
        RequestCompletion completion = new RequestCompletion();
        HttpServletRequest wrapped = new HttpServletRequestWrapper(request) {
            @Override
            public AsyncContext startAsync() {
                return completion.track(super.startAsync());
            }

            @Override
            public AsyncContext startAsync(ServletRequest asyncRequest,
                ServletResponse asyncResponse) {
                return completion.track(super.startAsync(asyncRequest, asyncResponse));
            }
        };
        try {
            filterChain.doFilter(wrapped, response);
        } finally {
            if (!completion.asyncStarted.get()) {
                if (request.isAsyncStarted()) {
                    completion.track(request.getAsyncContext());
                } else {
                    completion.finish();
                }
            }
        }
    }

    private final class RequestCompletion implements AsyncListener {

        private final AtomicBoolean asyncStarted = new AtomicBoolean();
        private final AtomicBoolean completed = new AtomicBoolean();

        private AsyncContext track(AsyncContext context) {
            asyncStarted.set(true);
            try {
                context.addListener(this);
            } catch (IllegalStateException exception) {
                // 다른 스레드에서 비동기 응답이 이미 끝났을 수 있다.
                finish();
            }
            return context;
        }

        private void finish() {
            if (completed.compareAndSet(false, true)) {
                activeRequests.decrementAndGet();
            }
        }

        @Override
        public void onComplete(AsyncEvent event) {
            finish();
        }

        @Override
        public void onTimeout(AsyncEvent event) {
            // 오류 처리 후 요청이 이어질 수 있으므로 onComplete를 기다린다.
        }

        @Override
        public void onError(AsyncEvent event) {
            // 오류 처리 후 요청이 이어질 수 있으므로 onComplete를 기다린다.
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
            track(event.getAsyncContext());
        }
    }
}
