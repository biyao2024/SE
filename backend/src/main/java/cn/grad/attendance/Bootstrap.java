package cn.grad.attendance;

import static cn.grad.attendance.Domain.*;
import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class Bootstrap implements ApplicationRunner {
    private final Store store;private final PasswordEncoder passwords;private final String username,password;
    public Bootstrap(Store store,PasswordEncoder passwords,@Value("${app.bootstrap-username}") String username,@Value("${app.bootstrap-password}") String password) {this.store=store;this.passwords=passwords;this.username=username;this.password=password;}
    @Override @Transactional public void run(ApplicationArguments args) {
        if(store.list(UserAccount.class,"").isEmpty()) {AuthController.validatePassword(password);var u=new UserAccount();u.username=username;u.passwordHash=passwords.encode(password);u.role="ADMIN";store.save(u);}
        if(store.list(Settings.class,"").isEmpty()) store.save(new Settings());
        if(store.list(Rule.class,"").isEmpty()) {var r=new Rule();r.effectiveDate=LocalDate.of(2000,1,1);store.save(r);}
        if(store.list(Department.class,"").isEmpty()) {var d=new Department();d.name="默认部门";store.save(d);}
        for(var s:store.list(PunchSession.class,"where e.status='PENDING'")) {s.status="CANCELLED";s.resultMessage="服务重启，请重新开始";}
    }
}
