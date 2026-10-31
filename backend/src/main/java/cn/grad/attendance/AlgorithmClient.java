package cn.grad.attendance;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

@Component
public class AlgorithmClient {
    final String base,key;final RestClient rest;final ObjectMapper json;
    public AlgorithmClient(@Value("${app.algorithm-url}") String base,@Value("${app.internal-key}") String key,ObjectMapper json) {
        this.base=base;this.key=key;this.json=json;
        URI uri=URI.create(base);if(!Set.of("127.0.0.1","localhost","::1").contains(uri.getHost()))throw new IllegalStateException("Algorithm service must use a loopback address");
        // Uvicorn serves HTTP/1.1; avoid the JDK client's default h2c upgrade on loopback HTTP.
        var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build());factory.setReadTimeout(Duration.ofSeconds(15));
        rest=RestClient.builder().baseUrl(base).defaultHeader("X-Internal-Key",key).requestFactory(factory).requestInterceptor((request,body,execution)->{request.getHeaders().set("X-Request-ID",Api.requestId());return execution.execute(request,body);}).build();
    }
    @SuppressWarnings("unchecked") Map<String,Object> map(String text) {try{return json.readValue(text,Map.class);}catch(Exception e){throw Api.error(503,"ALGORITHM_UNAVAILABLE","算法响应格式错误");}}
    public Map<String,Object> health() {try{return map(rest.get().uri("/internal/health").retrieve().body(String.class));}catch(Exception e){return Map.of("ready",false,"galleryVersion",-1,"detail","算法服务不可用");}}
    public void putGallery(Object gallery) {try{rest.put().uri("/internal/gallery").body(gallery).retrieve().toBodilessEntity();}catch(Exception e){throw Api.error(503,"GALLERY_UNAVAILABLE","人员库同步失败，暂不能打卡");}}
    public Map<String,Object> extract(List<MultipartFile> files,Long employeeId) {
        var parts=new LinkedMultiValueMap<String,Object>();
        try{for(var file:files){if(file.isEmpty()||file.getSize()>200*1024)throw Api.error(400,"FRAME_TOO_LARGE","单张图像须在200KB以内");byte[] bytes=file.getBytes();var headers=new HttpHeaders();headers.setContentType(MediaType.IMAGE_JPEG);parts.add("files",new HttpEntity<>(new ByteArrayResource(bytes){@Override public String getFilename(){return "capture.jpg";}},headers));}parts.add("employeeId",employeeId.toString());return map(rest.post().uri("/internal/face/extract").contentType(MediaType.MULTIPART_FORM_DATA).body(parts).retrieve().body(String.class));}
        catch(Api.Failure e){throw e;}catch(org.springframework.web.client.HttpStatusCodeException e){var data=map(e.getResponseBodyAsString());Object detail=data.getOrDefault("message",data.get("detail"));throw Api.error(e.getStatusCode().value(),String.valueOf(data.getOrDefault("code","FACE_REJECTED")),detail instanceof String?detail.toString():"人脸质量、身份一致性或重复检查未通过");}catch(Exception e){throw Api.error(503,"ALGORITHM_UNAVAILABLE","算法服务不可用，未保存人脸模板");}
    }
    public void createSession(Object body) {try{rest.post().uri("/internal/sessions").body(body).retrieve().toBodilessEntity();}catch(org.springframework.web.client.HttpClientErrorException.TooManyRequests e){throw Api.error(429,"DEVICE_BUSY","设备繁忙，请稍后重试");}catch(Exception e){throw Api.error(503,"ALGORITHM_UNAVAILABLE","算法服务未准备好或动作模型不可用");}}
    public void cancel(String sessionId) {try{rest.delete().uri("/internal/sessions/{id}",sessionId).retrieve().toBodilessEntity();}catch(Exception ignored){}}
    public java.util.concurrent.CompletableFuture<WebSocket> connect(String id,WebSocket.Listener listener) {return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build().newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5)).header("X-Internal-Key",key).header("X-Request-ID",Api.requestId()).buildAsync(URI.create(base.replaceFirst("^http","ws")+"/internal/sessions/"+id+"/stream"),listener);}
}
