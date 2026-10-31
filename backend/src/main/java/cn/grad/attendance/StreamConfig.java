package cn.grad.attendance;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.*;
import org.springframework.web.socket.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

@Configuration @EnableWebSocket
public class StreamConfig implements WebSocketConfigurer {
    final KioskService kiosk;final AlgorithmClient algorithm;final ObjectMapper json;final String[] origins;
    final ConcurrentHashMap<String,Bridge> active=new ConcurrentHashMap<>();
    public StreamConfig(KioskService kiosk,AlgorithmClient algorithm,ObjectMapper json,@Value("${app.allowed-origins}") String origins){this.kiosk=kiosk;this.algorithm=algorithm;this.json=json;this.origins=Arrays.stream(origins.split(",")).map(String::trim).toArray(String[]::new);}
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry){
        registry.addHandler(new Handler(),"/api/v1/kiosk/stream").setAllowedOrigins(origins).addInterceptors(new HandshakeInterceptor(){
            public boolean beforeHandshake(ServerHttpRequest request,ServerHttpResponse response,WebSocketHandler handler,Map<String,Object> attributes){
                try{String origin=request.getHeaders().getOrigin();if(origin==null||!Arrays.asList(origins).contains(origin))return false;
                    String id=UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst("sessionId");UUID.fromString(id);
                    var servlet=((ServletServerHttpRequest)request).getServletRequest();String token=null;if(servlet.getCookies()!=null)for(var cookie:servlet.getCookies())if(cookie.getName().equals("PUNCH_DEVICE"))token=cookie.getValue();
                    var device=kiosk.device(token);kiosk.check(id,device.id);attributes.put("sessionId",id);attributes.put("deviceId",device.id);return true;
                }catch(Exception e){response.setStatusCode(org.springframework.http.HttpStatus.FORBIDDEN);return false;}
            }
            public void afterHandshake(ServerHttpRequest request,ServerHttpResponse response,WebSocketHandler handler,Exception exception){}
        });
    }
    class Bridge implements WebSocket.Listener {
        final String id;final Long deviceId;final WebSocketSession browser;final AtomicBoolean finished=new AtomicBoolean();final StringBuilder incoming=new StringBuilder();
        volatile WebSocket internal;volatile boolean busy;long firstFrameNanos;int frames,bytes;
        Bridge(WebSocketSession browser){this.browser=browser;id=(String)browser.getAttributes().get("sessionId");deviceId=(Long)browser.getAttributes().get("deviceId");}
        void send(Object event){try{synchronized(browser){if(browser.isOpen())browser.sendMessage(new TextMessage(json.writeValueAsString(event)));}}catch(Exception e){close();}}
        void error(String message){if(finished.compareAndSet(false,true)){try{kiosk.failed(id,deviceId,message);}catch(Exception ignored){}send(Map.of("type","ERROR","message",message));close();}}
        void close(){active.remove(id,this);if(internal!=null)internal.sendClose(WebSocket.NORMAL_CLOSURE,"done");try{if(browser.isOpen())browser.close();}catch(Exception ignored){}}
        public void onOpen(WebSocket socket){internal=socket;socket.request(1);send(Map.of("type","PROMPT","message","请自然睁眼、闭嘴并正对镜头","action","BASELINE"));}
        public CompletionStage<?> onText(WebSocket socket,CharSequence data,boolean last){
            incoming.append(data);if(incoming.length()>16000){error("算法响应超过限制");return CompletableFuture.completedFuture(null);}
            if(last){String text=incoming.toString();incoming.setLength(0);try{var event=algorithm.map(text);String type=String.valueOf(event.get("type"));if(type.equals("RESULT")){if(finished.compareAndSet(false,true)){try{var result=kiosk.finish(id,deviceId,event);send(result);}catch(Api.Failure e){kiosk.failed(id,deviceId,e.getMessage());send(Map.of("type","ERROR","message",e.getMessage()));}finally{close();}}}else if(type.equals("ERROR")){error(String.valueOf(event.getOrDefault("message","动作验证失败")));}else{busy=false;send(Map.of("type","PROMPT","message",event.getOrDefault("message","请保持正对镜头"),"action",event.getOrDefault("action","BASELINE")));}}catch(Exception e){error("算法响应异常，请重试");}}
            socket.request(1);return CompletableFuture.completedFuture(null);
        }
        public CompletionStage<?> onClose(WebSocket socket,int code,String reason){if(!finished.get())error("算法连接中断，请重新开始");return CompletableFuture.completedFuture(null);}
        public void onError(WebSocket socket,Throwable error){error("算法服务不可用");}
    }
    class Handler extends BinaryWebSocketHandler {
        @Override public void afterConnectionEstablished(WebSocketSession session){session.setBinaryMessageSizeLimit(200*1024);String id=(String)session.getAttributes().get("sessionId");var bridge=new Bridge(session);if(active.putIfAbsent(id,bridge)!=null){bridge.send(Map.of("type","ERROR","message","该会话已有连接"));try{session.close();}catch(Exception ignored){}return;}
            algorithm.connect(id,bridge).whenComplete((socket,error)->{if(error!=null)bridge.error("算法服务不可用");});
            CompletableFuture.delayedExecutor(60,TimeUnit.SECONDS).execute(()->{if(!bridge.finished.get())bridge.error("验证超时");});
        }
        @Override protected void handleBinaryMessage(WebSocketSession session,BinaryMessage message){var b=active.get((String)session.getAttributes().get("sessionId"));if(b==null||b.browser!=session||b.finished.get())return;
            if(!message.isLast()){b.error("不接受分片帧");return;}
            try{kiosk.check(b.id,b.deviceId);int size=message.getPayloadLength();if(size<4||size>200*1024||++b.frames>80||(b.bytes+=size)>12*1024*1024){b.error("采集超过体积或帧数限制");return;}
                long now=System.nanoTime();if(b.firstFrameNanos==0){b.firstFrameNanos=now;CompletableFuture.delayedExecutor(12,TimeUnit.SECONDS).execute(()->{if(!b.finished.get())b.error("动作验证超时");});}
                if(now-b.firstFrameNanos>Duration.ofSeconds(12).toNanos()){b.error("动作验证超时");return;}
                if(b.internal==null||b.busy)return;b.busy=true;byte[] bytes=new byte[size];message.getPayload().get(bytes);b.internal.sendBinary(ByteBuffer.wrap(bytes),true).whenComplete((socket,error)->{if(error!=null)b.error("算法连接中断");});
            }catch(Api.Failure e){b.error(e.getMessage());}catch(Exception e){b.error("验证失败，请重新开始");}
        }
        @Override public void afterConnectionClosed(WebSocketSession session,CloseStatus status){var b=active.get((String)session.getAttributes().get("sessionId"));if(b!=null&&b.browser==session){if(!b.finished.get())b.error("网络中断或采集已取消");else b.close();}}
        @Override public void handleTransportError(WebSocketSession session,Throwable error){var b=active.get((String)session.getAttributes().get("sessionId"));if(b!=null&&b.browser==session)b.error("画面传输失败，请重新开始");}
    }
}
