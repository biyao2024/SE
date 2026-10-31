package cn.grad.attendance;

import static cn.grad.attendance.Domain.*;
import jakarta.servlet.http.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.authentication.*;
import org.springframework.security.core.context.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/auth")
public class AuthController {
    private final Store store; private final AuthenticationManager manager;private final SecurityContextRepository contexts;private final PasswordEncoder passwords;
    private final Map<String,Attempts> attempts=new ConcurrentHashMap<>();
    private record Attempts(int count,long since) {}
    public AuthController(Store store,AuthenticationManager manager,SecurityContextRepository contexts,PasswordEncoder passwords) { this.store=store;this.manager=manager;this.contexts=contexts;this.passwords=passwords; }
    @GetMapping("/csrf") Object csrf(CsrfToken token) { return Api.ok(Map.of("token",token.getToken(),"headerName",token.getHeaderName())); }
    @PostMapping("/login") Object login(@RequestBody Map<String,Object> body,HttpServletRequest request,HttpServletResponse response) {
        String username=Api.required(body,"username",100),password=Api.password(body,"password");
        // Per-account and per-peer throttling; never store the submitted password.
        String key=request.getRemoteAddr()+":"+username;long now=System.currentTimeMillis();
        attempts.entrySet().removeIf(e->now-e.getValue().since()>300_000);
        var a=attempts.compute(key,(k,v)->v==null?new Attempts(1,now):new Attempts(v.count()+1,v.since()));
        if(a.count()>10) throw Api.error(429,"RATE_LIMIT","尝试次数过多，请5分钟后重试");
        try {
            var auth=manager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(username,password));
            if(request.getSession(false)!=null) request.changeSessionId();
            SecurityContext context=SecurityContextHolder.createEmptyContext();context.setAuthentication(auth);SecurityContextHolder.setContext(context);contexts.saveContext(context,request,response);
            attempts.remove(key);return Api.ok(view(current()));
        } catch(org.springframework.security.core.AuthenticationException e) { throw Api.error(401,"INVALID_CREDENTIALS","账号或密码错误，或账号已停用"); }
    }
    @GetMapping("/me") Object me() { return Api.ok(view(current())); }
    @PostMapping("/logout") Object logout(HttpServletRequest request) { var s=request.getSession(false);if(s!=null)s.invalidate();SecurityContextHolder.clearContext();return Api.ok(null); }
    @PutMapping("/password") @Transactional Object password(@RequestBody Map<String,Object> body) {
        var u=store.lock(UserAccount.class,current().id);String old=Api.password(body,"oldPassword"),next=Api.password(body,"newPassword");validatePassword(next);
        if(!passwords.matches(old,u.passwordHash)) throw Api.error(400,"PASSWORD_MISMATCH","原密码不正确");
        if(passwords.matches(next,u.passwordHash)) throw Api.error(400,"INVALID_PASSWORD","新密码不能与原密码相同");
        u.passwordHash=passwords.encode(next);u.mustChangePassword=false;return Api.ok(null);
    }
    public static void validatePassword(String password) { if(password.length()<12||password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72||!password.matches("(?s).*[A-Za-z].*")||!password.matches("(?s).*[0-9].*")) throw Api.error(400,"WEAK_PASSWORD","密码至少12字符、UTF-8编码最多72字节，包含字母和数字"); }
    public UserAccount current() { var auth=SecurityContextHolder.getContext().getAuthentication();if(auth==null)throw Api.error(401,"UNAUTHENTICATED","请先登录");return store.first(UserAccount.class,"where e.username=?1",auth.getName()).orElseThrow(()->Api.error(401,"UNAUTHENTICATED","请先登录")); }
    public UserAccount admin() { var u=current();if(!"ADMIN".equals(u.role))throw Api.error(403,"FORBIDDEN","仅管理员可操作");return u; }
    public Long employee() { var u=current();if(u.employeeId==null)throw Api.error(400,"NO_EMPLOYEE","此管理员账号尚未关联员工档案");return u.employeeId; }
    Object view(UserAccount u) { return Map.of("id",u.id,"username",u.username,"role",u.role,"employeeId",u.employeeId==null?0:u.employeeId,"name",u.employeeId==null?"管理员":store.get(Employee.class,u.employeeId).name,"mustChangePassword",u.mustChangePassword); }
}
