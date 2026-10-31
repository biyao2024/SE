package cn.grad.attendance;

import static cn.grad.attendance.Domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.*;
import org.springframework.security.web.context.*;
import org.springframework.security.web.csrf.*;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwords() { return new BCryptPasswordEncoder(12); }
    @Bean UserDetailsService users(Store store) { return name -> {
        UserAccount u=store.first(UserAccount.class,"where e.username=?1",name).orElseThrow(()->new UsernameNotFoundException("账号或密码错误"));
        boolean active=u.enabled && (u.employeeId==null||store.get(Employee.class,u.employeeId).active);
        return User.withUsername(u.username).password(u.passwordHash).roles(u.role).disabled(!active).build();
    }; }
    @Bean AuthenticationManager authenticationManager(UserDetailsService users,PasswordEncoder encoder) {
        var provider=new org.springframework.security.authentication.dao.DaoAuthenticationProvider(users);provider.setPasswordEncoder(encoder);return new ProviderManager(provider);
    }
    @Bean SecurityContextRepository contexts() { return new HttpSessionSecurityContextRepository(); }
    @Bean SecurityFilterChain chain(HttpSecurity http,SecurityContextRepository contexts,Store store,ObjectMapper mapper) throws Exception {
        var csrf=new HttpSessionCsrfTokenRepository();csrf.setHeaderName("X-CSRF-TOKEN");
        http.securityContext(c->c.securityContextRepository(contexts))
            .csrf(c->c.csrfTokenRepository(csrf))
            .authorizeHttpRequests(a->a
                .requestMatchers("/api/v1/auth/csrf","/api/v1/auth/login","/api/v1/kiosk/**","/api/v1/health","/error").permitAll()
                .requestMatchers("/api/v1/auth/**","/api/v1/attendance/**","/api/v1/leave-requests/**","/api/v1/correction-requests/**").authenticated()
                .anyRequest().hasRole("ADMIN"))
            .requestCache(c->c.disable())
            .logout(c->c.disable())
            .exceptionHandling(e->e.authenticationEntryPoint((req,res,ex)->json(res,401,"UNAUTHENTICATED","请先登录",mapper))
                .accessDeniedHandler((req,res,ex)->json(res,403,"FORBIDDEN","无权执行此操作，或安全令牌已过期，请刷新页面",mapper)))
            .addFilterAfter(new OncePerRequestFilter() {
                @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
                    var auth=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
                    if(auth!=null&&auth.isAuthenticated()&&!(auth instanceof org.springframework.security.authentication.AnonymousAuthenticationToken)) {
                        var u=store.first(UserAccount.class,"where e.username=?1",auth.getName()).orElse(null);
                        if(u==null||!u.enabled||(u.employeeId!=null&&!store.get(Employee.class,u.employeeId).active)) {
                            // Remove the stale login so this browser can establish a new CSRF
                            // session and sign in with another account on its next request.
                            org.springframework.security.core.context.SecurityContextHolder.clearContext();
                            var session=request.getSession(false);
                            if(session!=null)session.invalidate();
                            json(response,401,"ACCOUNT_DISABLED","账号已停用",mapper);return;
                        }
                        if(u.mustChangePassword&&!request.getRequestURI().startsWith("/api/v1/auth/")) { json(response,403,"PASSWORD_CHANGE_REQUIRED","请先修改初始密码",mapper);return; }
                    }
                    chain.doFilter(request,response);
                }
            },AnonymousAuthenticationFilter.class);
        return http.build();
    }
    static void json(HttpServletResponse res,int status,String code,String message,ObjectMapper mapper) throws IOException { res.setStatus(status);res.setContentType("application/json;charset=UTF-8");mapper.writeValue(res.getOutputStream(),new Api.Envelope(code,message,null,Api.requestId())); }
}
