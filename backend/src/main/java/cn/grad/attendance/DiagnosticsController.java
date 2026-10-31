package cn.grad.attendance;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/kiosk/diagnostics")
public class DiagnosticsController {
    private final KioskService kiosk;private final AuthController auth;
    public DiagnosticsController(KioskService kiosk,AuthController auth){this.kiosk=kiosk;this.auth=auth;}
    @PostMapping("/sessions") Object create(@CookieValue(name="PUNCH_DEVICE",required=false) String token,@RequestBody Map<String,Object> body){
        kiosk.diagnosticAccess(auth.admin().username);
        return Api.ok(kiosk.create(token,Api.required(body,"type",10)));
    }
}
