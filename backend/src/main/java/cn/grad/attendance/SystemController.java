package cn.grad.attendance;

import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController @RequestMapping("/api/v1")
public class SystemController {
    final GalleryService gallery;final AuthController auth;final AttendanceService attendance;final AlgorithmClient algorithm;
    public SystemController(GalleryService gallery,AuthController auth,AttendanceService attendance,AlgorithmClient algorithm){this.gallery=gallery;this.auth=auth;this.attendance=attendance;this.algorithm=algorithm;}
    @GetMapping("/health") Object health(){return Api.ok(Map.of("status","UP"));}
    @GetMapping("/system/health") Object systemHealth(){var s=attendance.settings();var health=algorithm.health();return Api.ok(Map.of("database","UP","algorithm",Boolean.TRUE.equals(health.get("ready"))?"UP":"UNAVAILABLE","model",health.getOrDefault("modelVersion","unavailable"),"galleryVersion",s.galleryVersion,"gallerySynced",((Number)health.getOrDefault("galleryVersion",-1)).longValue()==s.galleryVersion,"detail",health.getOrDefault("detail","")));}
    @PostMapping("/system/sync-gallery") Object sync(){auth.admin();gallery.sync();return systemHealth();}
    @PostMapping("/employees/{id}/face") Object enroll(@PathVariable Long id,@RequestParam("files") List<MultipartFile> files,@RequestParam(defaultValue="false") boolean consent){return Api.ok(gallery.enroll(id,files,consent,auth.admin().id));}
    @DeleteMapping("/employees/{id}/face") Object delete(@PathVariable Long id){gallery.delete(id,auth.admin().id);return Api.ok(null);}
}
