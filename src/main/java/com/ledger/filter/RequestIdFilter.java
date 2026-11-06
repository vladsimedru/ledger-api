package com.ledger.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request an id: the caller's {@code X-Request-Id} if it sent a sensible one, a new one
 * otherwise. It is echoed back, put in every log line (MDC) and carried into the announcements the
 * request causes, so one request can be followed end to end.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    private static final Pattern ACCEPTABLE = Pattern.compile("[\\x21-\\x7E]{1,128}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = resolveRequestId(request);
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /** The caller's id if it sent a sensible one; otherwise a new one, logged with the request it was made for. */
    private static String resolveRequestId(HttpServletRequest request) {
        String given = request.getHeader(HEADER);
        if (given != null && ACCEPTABLE.matcher(given).matches()) {
            return given;
        }
        String generated = UUID.randomUUID().toString();
        if (given == null) {
            log.debug("No {} header; generated {} for {} {}", HEADER, generated, request.getMethod(), request.getRequestURI());
        } else {
            log.debug("Invalid {} header ignored; generated {} for {} {}", HEADER, generated, request.getMethod(),
                    request.getRequestURI());
        }
        return generated;
    }
}
