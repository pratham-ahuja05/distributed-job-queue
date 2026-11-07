package com.pratham.apiservice.web;

import com.pratham.apiservice.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed-window limit on write requests per client IP, so a public demo can't be flooded.
 * Disabled when app.rate-limit-per10s is 0.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final long WINDOW_MS = 10_000;

    private final AppProperties props;
    private final Map<String, long[]> windows = new ConcurrentHashMap<>(); // ip -> [windowStart, count]

    public RateLimitInterceptor(AppProperties props) {
        this.props = props;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        int limit = props.rateLimitPer10s();
        if (limit <= 0 || "GET".equals(request.getMethod()) || "OPTIONS".equals(request.getMethod())) {
            return true;
        }
        long now = System.currentTimeMillis();
        long[] window = windows.compute(clientIp(request), (ip, w) -> {
            if (w == null || now - w[0] >= WINDOW_MS) return new long[]{now, 1};
            w[1]++;
            return w;
        });
        if (window[1] > limit) {
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"rate limit exceeded, slow down\"}");
            return false;
        }
        if (windows.size() > 10_000) {
            windows.entrySet().removeIf(e -> now - e.getValue()[0] >= WINDOW_MS);
        }
        return true;
    }

    static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
