package cn.grad.attendance;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** A server-generated id connects browser errors with internal HTTP calls; no bodies are logged. */
@Component @Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestTraceFilter extends OncePerRequestFilter {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(RequestTraceFilter.class);
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String id=UUID.randomUUID().toString();MDC.put("requestId",id);response.setHeader("X-Request-ID",id);
        long started=System.nanoTime();
        try{chain.doFilter(request,response);}
        finally{LOG.info("request={} method={} path={} status={} elapsedMs={}",id,request.getMethod(),request.getRequestURI(),response.getStatus(),(System.nanoTime()-started)/1_000_000);MDC.remove("requestId");}
    }
}
