package cn.grad.attendance;

import java.util.Map;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class PolicyController {
    private final AttendancePolicyService policy;
    private final AttendanceService attendance;
    private final AuthController auth;

    public PolicyController(AttendancePolicyService policy, AttendanceService attendance, AuthController auth) {
        this.policy = policy;
        this.attendance = attendance;
        this.auth = auth;
    }

    @GetMapping("/attendance-policy")
    public Object policy() { auth.current(); return Api.ok(policy.view()); }

    @PutMapping("/attendance-policy")
    @Transactional
    public Object updatePolicy(@RequestBody Map<String, Object> body) {
        var actor = auth.admin();
        attendance.guard();
        return Api.ok(policy.updateMode(Api.required(body, "mode", 32), actor.id));
    }

    @GetMapping("/attendance-locations")
    public Object locations() { return Api.ok(policy.locations("ADMIN".equals(auth.current().role))); }

    @PostMapping("/attendance-locations")
    @Transactional
    public Object createLocation(@RequestBody Map<String, Object> body) {
        var actor = auth.admin();
        attendance.guard();
        return Api.ok(policy.saveLocation(null, body, actor.id));
    }

    @PutMapping("/attendance-locations/{id}")
    @Transactional
    public Object updateLocation(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        var actor = auth.admin();
        attendance.guard();
        return Api.ok(policy.saveLocation(id, body, actor.id));
    }

    @DeleteMapping("/attendance-locations/{id}")
    @Transactional
    public Object deleteLocation(@PathVariable Long id) {
        var actor = auth.admin();
        attendance.guard();
        policy.deleteLocation(id, actor.id);
        return Api.ok(null);
    }
}
