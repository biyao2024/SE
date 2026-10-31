package cn.grad.attendance;

import static cn.grad.attendance.Domain.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class GalleryService {
    final Store store;final AttendanceService attendance;final AlgorithmClient algorithm;final Crypto crypto;final ObjectMapper json;
    public GalleryService(Store store,AttendanceService attendance,AlgorithmClient algorithm,Crypto crypto,ObjectMapper json){this.store=store;this.attendance=attendance;this.algorithm=algorithm;this.crypto=crypto;this.json=json;}
    public Map<String,Object> ensure(Settings settings) {
        var health=algorithm.health();if(!Boolean.TRUE.equals(health.get("ready")))throw Api.error(503,"ALGORITHM_UNAVAILABLE","算法模型未就绪，请检查模型文件和服务");
        for(var face:store.list(FaceTemplate.class,""))if(store.get(Employee.class,face.employeeId).active&&!Objects.equals(face.modelVersion,health.get("modelVersion")))throw Api.error(503,"MODEL_TEMPLATE_MISMATCH","模型已更换；请先删除旧模型模板，再重新录入人脸");
        if(((Number)health.getOrDefault("galleryVersion",-1)).longValue()!=settings.galleryVersion){
            var employees=new ArrayList<Map<String,Object>>();
            for(var employee:store.list(Employee.class,"where e.active=true")){
                var templates=new ArrayList<List<Double>>();
                for(var face:store.list(FaceTemplate.class,"where e.employeeId=?1",employee.id))try{templates.add(json.readValue(crypto.decrypt(face.encryptedVector,employee.id),new TypeReference<List<Double>>(){}));}catch(Exception e){throw Api.error(503,"TEMPLATE_DECRYPTION_FAILED","模板无法解密，请检查原加密密钥");}
                if(!templates.isEmpty())employees.add(Map.of("employeeId",employee.id,"templates",templates));
            }
            algorithm.putGallery(Map.of("version",settings.galleryVersion,"employees",employees));health=algorithm.health();
            if(((Number)health.getOrDefault("galleryVersion",-1)).longValue()!=settings.galleryVersion)throw Api.error(503,"GALLERY_UNAVAILABLE","人员库版本未同步");
        }
        return health;
    }
    @Transactional public void sync(){ensure(attendance.guard());}
    @Scheduled(initialDelay=15_000,fixedDelay=30_000) @Transactional public void retrySync(){if(store.list(Settings.class,"").isEmpty())return;try{ensure(attendance.guard());}catch(Api.Failure ignored){}}
    @Transactional public Object enroll(Long id,List<MultipartFile> files,boolean consent,Long actor) {
        var settings=attendance.guard();var employee=store.get(Employee.class,id);if(!employee.active)throw Api.error(400,"EMPLOYEE_DISABLED","停用员工不能录入");if(!consent||files.size()!=3)throw Api.error(400,"INVALID_ENROLLMENT","须确认已告知并获得同意，同时采集3张图像");
        ensure(settings);var extracted=algorithm.extract(files,id);
        Object raw=extracted.get("embeddings");if(!(raw instanceof List<?> embeddings)||embeddings.size()!=3)throw Api.error(503,"INVALID_EMBEDDINGS","算法未返回三份有效模板");
        for(var old:store.list(FaceTemplate.class,"where e.employeeId=?1",id))store.remove(old);
        String batch=UUID.randomUUID().toString();for(var embedding:(List<?>)raw){var face=new FaceTemplate();face.employeeId=id;try{face.encryptedVector=crypto.encrypt(json.writeValueAsString(embedding),id);}catch(Exception ex){throw new IllegalStateException(ex);}face.modelVersion=extracted.get("modelVersion").toString();face.batch=batch;face.consent=true;store.save(face);}
        settings.galleryVersion++;invalidate();attendance.audit(actor,"ENROLL_FACE",id.toString(),"现场核验/告知同意；三份模板；batch="+batch);
        // New gallery is published after this transaction commits (scheduled sync or next session).
        return Map.of("employeeId",id,"templates",3,"galleryVersion",settings.galleryVersion);
    }
    @Transactional public void delete(Long id,Long actor){var s=attendance.guard();store.get(Employee.class,id);for(var old:store.list(FaceTemplate.class,"where e.employeeId=?1",id))store.remove(old);s.galleryVersion++;invalidate();attendance.audit(actor,"DELETE_FACE",id.toString(),"删除全部加密模板");}
    void invalidate(){for(var session:store.list(PunchSession.class,"where e.status='PENDING'")){session.status="CANCELLED";session.resultMessage="人员库变更，请重新开始";}}
}
