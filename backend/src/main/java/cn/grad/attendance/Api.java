package cn.grad.attendance;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.dao.DataIntegrityViolationException;

public final class Api {
    private Api() {}
    public record Envelope(String code,String message,Object data,String requestId) {}
    public static String requestId() {String id=org.slf4j.MDC.get("requestId");return id==null?UUID.randomUUID().toString():id;}
    public static Envelope ok(Object data) { return new Envelope("OK","操作成功",data,requestId()); }
    public static Map<String,Object> page(List<?> rows,int page,int size) {
        int p=Math.max(0,page),s=Math.max(1,Math.min(100,size));
        int from=(int)Math.min((long)p*s,rows.size());
        return Map.of("items",rows.subList(from,Math.min(from+s,rows.size())),"total",rows.size(),"page",p,"size",s);
    }
    public static Failure error(int status,String code,String message) { return new Failure(status,code,message); }
    public static class Failure extends RuntimeException {
        public final int status; public final String code;
        Failure(int status,String code,String message) { super(message);this.status=status;this.code=code; }
    }
    @RestControllerAdvice public static class Errors {
        @ExceptionHandler(Failure.class) ResponseEntity<Envelope> business(Failure e) { return ResponseEntity.status(e.status).body(new Envelope(e.code,e.getMessage(),null,requestId())); }
        @ExceptionHandler({DataIntegrityViolationException.class,jakarta.persistence.OptimisticLockException.class,org.springframework.orm.ObjectOptimisticLockingFailureException.class})
        ResponseEntity<Envelope> conflict(Exception e) { return business(error(409,"CONFLICT","记录已存在或已被其他操作更新，请刷新重试")); }
        @ExceptionHandler({IllegalArgumentException.class,org.springframework.http.converter.HttpMessageNotReadableException.class,org.springframework.web.bind.MethodArgumentNotValidException.class,org.springframework.web.multipart.MaxUploadSizeExceededException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
        ResponseEntity<Envelope> invalid(Exception e) { return business(error(400,"INVALID_INPUT","输入格式错误、字段缺失或图像超过限制")); }
        @ExceptionHandler(Exception.class) ResponseEntity<Envelope> other(Exception e,HttpServletRequest request) {
            String id=requestId();
            org.slf4j.LoggerFactory.getLogger(Errors.class).error("request={} method={} path={} error={}",id,request.getMethod(),request.getRequestURI(),e.getClass().getSimpleName());
            return ResponseEntity.status(500).body(new Envelope("INTERNAL_ERROR","服务暂时无法处理，请通过请求编号联系管理员",null,id));
        }
    }
    public static String required(Map<String,Object> body,String key,int max) {
        Object val=body.get(key);String s=val instanceof String?(String)val:"";
        if(s.isBlank()||s.length()>max) throw error(400,"INVALID_INPUT",key+" 不能为空且不得超过 "+max+" 个字符");return s.trim();
    }
    public static Long number(Map<String,Object> body,String key) { Object v=body.get(key);return v==null?null:Long.valueOf(v.toString()); }
    public static String password(Map<String,Object> body,String key) {Object v=body.get(key);if(!(v instanceof String s)||s.isEmpty()||s.length()>100)throw error(400,"INVALID_INPUT","密码格式不正确");return s;}
    public static boolean bool(Map<String,Object> body,String key,boolean fallback) { Object v=body.get(key);return v==null?fallback:Boolean.parseBoolean(v.toString()); }
}
