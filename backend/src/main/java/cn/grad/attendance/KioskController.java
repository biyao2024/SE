package cn.grad.attendance;

import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/kiosk")
public class KioskController {
    final KioskService service;
    public KioskController(KioskService service){this.service=service;}
    @GetMapping("/status") Object status(@CookieValue(name="PUNCH_DEVICE",required=false) String token){return Api.ok(service.status(token));}
    @PostMapping("/sessions") Object create(@CookieValue(name="PUNCH_DEVICE",required=false) String token,@RequestBody Map<String,Object> body){return Api.ok(service.create(token,Api.required(body,"type",10)));}
    @GetMapping("/sessions/{id}") Object result(@CookieValue(name="PUNCH_DEVICE",required=false) String token,@PathVariable String id){return Api.ok(service.result(token,id));}
    @DeleteMapping("/sessions/{id}") Object cancel(@CookieValue(name="PUNCH_DEVICE",required=false) String token,@PathVariable String id){service.cancel(token,id);return Api.ok(null);}
}
