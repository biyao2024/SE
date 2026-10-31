package cn.grad.attendance;

import static cn.grad.attendance.Domain.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KioskService {
    final Store store;final AttendanceService attendance;final GalleryService gallery;final AlgorithmClient algorithm;
    public KioskService(Store store,AttendanceService attendance,GalleryService gallery,AlgorithmClient algorithm){this.store=store;this.attendance=attendance;this.gallery=gallery;this.algorithm=algorithm;}
    public Device device(String token){if(token==null||token.isBlank())throw Api.error(403,"DEVICE_UNAUTHORIZED","设备未授权，请管理员在本浏览器授权");var device=store.first(Device.class,"where e.tokenHash=?1",Crypto.hash(token)).orElseThrow(()->Api.error(403,"DEVICE_UNAUTHORIZED","设备未授权"));if(device.revoked||!device.expiresAt.isAfter(attendance.now()))throw Api.error(403,"DEVICE_UNAUTHORIZED","设备授权已撤销或过期");return device;}
    public Object status(String token){
        var d=device(token);var s=attendance.settings();var h=algorithm.health();
        boolean modelReady=Boolean.TRUE.equals(h.get("ready"));
        boolean gallerySynced=((Number)h.getOrDefault("galleryVersion",-1)).longValue()==s.galleryVersion;
        boolean ready=modelReady&&s.calibrationConfirmed&&gallerySynced;
        String message=!modelReady?"算法模型未就绪，请联系管理员检查服务":
            !s.calibrationConfirmed?"管理员需完成识别参数校准并确认":
            !gallerySynced?"人员库正在同步，请稍后刷新；持续失败请联系管理员":"请单人正对镜头";
        return Map.of("authorized",true,"deviceName",d.name,"expiresAt",d.expiresAt,
            "ready",ready,"livenessRequired",s.livenessEnabled,"message",message);
    }
    @Transactional public Map<String,Object> create(String token,String type){
        var s=attendance.guard();var d=device(token);RequestController.type(type);if(!AttendanceEngine.inWindow(attendance.rule(attendance.today()),type,attendance.now().toLocalTime()))throw Api.error(400,"OUTSIDE_WINDOW","非打卡时段");
        if(!s.calibrationConfirmed)throw Api.error(409,"CALIBRATION_REQUIRED","请管理员先完成识别阈值校准并确认参数");gallery.ensure(s);
        for(var old:store.list(PunchSession.class,"where e.status='PENDING'")){if(!old.expiresAt.isAfter(attendance.now())){old.status="EXPIRED";old.resultMessage="验证超时";}else if(old.deviceId.equals(d.id)){old.status="CANCELLED";old.resultMessage="已重新开始";algorithm.cancel(old.sessionId);}}
        if(store.list(PunchSession.class,"where e.status='PENDING' and e.expiresAt>?1",attendance.now()).size()>=2)throw Api.error(429,"DEVICE_BUSY","设备繁忙，最多同时两个会话");
        var session=new PunchSession();session.sessionId=UUID.randomUUID().toString();session.deviceId=d.id;session.type=type;session.livenessRequired=s.livenessEnabled;session.configVersion=s.configVersion;session.galleryVersion=s.galleryVersion;session.expiresAt=attendance.now().plusSeconds(60);
        var actions=new ArrayList<>(List.of("BLINK","MOUTH"));Collections.shuffle(actions,new java.security.SecureRandom());session.actions=String.join(",",actions);
        String expiresAt=session.expiresAt.atZone(attendance.zone()).toOffsetDateTime().toString();
        algorithm.createSession(Map.of("sessionId",session.sessionId,"galleryVersion",s.galleryVersion,"configVersion",s.configVersion,"livenessRequired",s.livenessEnabled,"actions",actions,"threshold",s.recognitionThreshold,"margin",s.candidateMargin,"expiresAt",expiresAt));
        store.save(session);return Map.of("sessionId",session.sessionId,"actions",actions,"livenessRequired",s.livenessEnabled,"expiresAt",expiresAt);
    }
    public PunchSession session(String id,Long deviceId){var session=store.first(PunchSession.class,"where e.sessionId=?1",id).orElseThrow(()->Api.error(404,"SESSION_NOT_FOUND","打卡会话不存在"));if(!session.deviceId.equals(deviceId))throw Api.error(403,"DEVICE_MISMATCH","会话不属于本设备");return session;}
    public void valid(PunchSession session){if(!"PENDING".equals(session.status))throw Api.error(409,"SESSION_CLOSED","会话已结束，请重新开始");if(!session.expiresAt.isAfter(attendance.now()))throw Api.error(408,"SESSION_EXPIRED","验证超时");var d=store.get(Device.class,session.deviceId);if(d.revoked||!d.expiresAt.isAfter(attendance.now()))throw Api.error(403,"DEVICE_UNAUTHORIZED","设备授权失效");var s=attendance.settings();if(s.configVersion!=session.configVersion||s.galleryVersion!=session.galleryVersion)throw Api.error(409,"SESSION_STALE","配置或人员库已变更，请重新开始");}
    @Transactional public void check(String id,Long deviceId){attendance.guard();valid(session(id,deviceId));}
    public Object result(String token,String id){return publicResult(session(id,device(token).id));}
    @Transactional public void cancel(String token,String id){attendance.guard();var session=session(id,device(token).id);if("PENDING".equals(session.status)){session.status="CANCELLED";session.resultMessage="已取消";}algorithm.cancel(id);}
    @Transactional public void failed(String id,Long deviceId,String message){attendance.guard();var session=session(id,deviceId);if("PENDING".equals(session.status)){session.status="FAILED";session.resultMessage=message.length()>200?message.substring(0,200):message;}algorithm.cancel(id);}
    @Transactional public Map<String,Object> finish(String id,Long deviceId,Map<String,Object> result){
        var settings=attendance.guard();var session=session(id,deviceId);
        if("SUCCESS".equals(session.status)||"DUPLICATE".equals(session.status))return publicResult(session);
        valid(session);LocalDateTime accepted=attendance.now();
        if(((Number)result.getOrDefault("galleryVersion",-1)).longValue()!=settings.galleryVersion||((Number)result.getOrDefault("configVersion",-1)).longValue()!=settings.configVersion)throw Api.error(409,"SESSION_STALE","算法返回过期版本");
        String live=String.valueOf(result.get("livenessStatus"));if(session.livenessRequired?!live.equals("PASSED"):!live.equals("SKIPPED"))throw Api.error(403,"LIVENESS_FAILED","动作验证失败");
        double score=((Number)result.getOrDefault("score",-1)).doubleValue(),margin=((Number)result.getOrDefault("margin",-1)).doubleValue();
        if(!Double.isFinite(score)||score<settings.recognitionThreshold||!Double.isFinite(margin))throw Api.error(403,"IDENTITY_REJECTED","未识别到已录入人员");
        long enrolled=store.list(FaceTemplate.class,"").stream().map(f->f.employeeId).distinct().filter(e->store.get(Employee.class,e).active).count();
        if(enrolled>1&&margin<settings.candidateMargin)throw Api.error(403,"IDENTITY_AMBIGUOUS","身份不稳定");
        if(!(result.get("employeeId") instanceof Number identity))throw Api.error(403,"IDENTITY_REJECTED","未识别到已录入人员");
        var employee=store.get(Employee.class,identity.longValue());if(!employee.active||!attendance.employed(employee,accepted.toLocalDate())||store.list(FaceTemplate.class,"where e.employeeId=?1",employee.id).isEmpty())throw Api.error(403,"EMPLOYEE_DISABLED","人员已停用或模板已删除");
        if(!AttendanceEngine.inWindow(attendance.rule(accepted.toLocalDate()),session.type,accepted.toLocalTime()))throw Api.error(400,"OUTSIDE_WINDOW","非打卡时段");if(attendance.leave(employee.id,accepted.toLocalDate()))throw Api.error(409,"LEAVE_CONFLICT","今日已批准整天请假，不能打卡");
        var health=gallery.ensure(settings);if(!Objects.equals(health.get("modelVersion"),result.get("modelVersion")))throw Api.error(409,"MODEL_CHANGED","模型版本变化，请重新开始");
        session.employeeId=employee.id;
        var last=store.first(PunchEvent.class,"where e.employeeId=?1 and e.type=?2 order by e.acceptedAt desc",employee.id,session.type);
        if(last.isPresent()&&last.get().acceptedAt.toLocalDate().equals(accepted.toLocalDate())&&Duration.between(last.get().acceptedAt,accepted).toMillis()<30_000){session.status="DUPLICATE";session.resultMessage="30秒内重复打卡，已保留原记录";store.flush();return publicResult(session);}
        var event=new PunchEvent();event.employeeId=employee.id;event.type=session.type;event.acceptedAt=accepted;event.source="FACE";event.deviceId=deviceId;event.sessionId=id;event.livenessRequired=session.livenessRequired;event.livenessStatus=live;event.modelVersion=result.get("modelVersion").toString();event.configVersion=settings.configVersion;event.galleryVersion=settings.galleryVersion;store.save(event);store.flush();attendance.recalculate(employee,accepted.toLocalDate());
        session.status="SUCCESS";session.resultMessage=session.type.equals("IN")?"上班签到成功":"下班签退成功";store.flush();return publicResult(session);
    }
    Map<String,Object> publicResult(PunchSession s){var out=new LinkedHashMap<String,Object>();out.put("type",s.status.equals("FAILED")?"ERROR":"RESULT");out.put("sessionId",s.sessionId);out.put("status",s.status);out.put("message",s.resultMessage==null?"等待验证":s.resultMessage);
        // Identity is visible only for three seconds, including polling/replay of a completed session.
        if(s.employeeId!=null&&s.updatedAt!=null&&!s.updatedAt.plusSeconds(3).isBefore(attendance.now())){var e=store.get(Employee.class,s.employeeId);out.put("employeeName",e.name.substring(0,1)+"*".repeat(Math.max(1,e.name.length()-1)));out.put("employeeNo","***"+e.employeeNo.substring(Math.max(0,e.employeeNo.length()-2)));}return out;}
}
