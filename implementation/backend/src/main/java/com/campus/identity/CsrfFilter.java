package com.campus.identity;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class CsrfFilter extends OncePerRequestFilter {
    private static final String KEY=CsrfFilter.class.getName();
    static String token(HttpSession session) {
        synchronized(session) {
            if(session.getAttribute(KEY)==null) session.setAttribute(KEY,UUID.randomUUID().toString());
            return (String)session.getAttribute(KEY);
        }
    }
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
        String path=req.getRequestURI().substring(req.getContextPath().length());
        if(path.startsWith("/api/") && !java.util.Set.of("GET","HEAD","OPTIONS").contains(req.getMethod())) {
            var session=req.getSession(false);
            String supplied=req.getHeader("X-CSRF-Token");
            if(supplied==null || session==null || !supplied.equals(session.getAttribute(KEY))) {
                res.setStatus(403); res.setContentType("application/json;charset=UTF-8");
                res.getWriter().write("{\"message\":\"会话校验失败，请刷新页面\"}"); return;
            }
        }
        chain.doFilter(req,res);
    }
}
