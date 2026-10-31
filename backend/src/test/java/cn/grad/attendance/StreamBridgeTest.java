package cn.grad.attendance;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.WebSocket;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class StreamBridgeTest {
    @Test void publicBridgeNeverForwardsDiagnosticsEvenWithForgedQueryAttribute()throws Exception {
        var algorithm=mock(AlgorithmClient.class);var browser=mock(WebSocketSession.class);var socket=mock(WebSocket.class);
        when(browser.getAttributes()).thenReturn(Map.of("sessionId","public","deviceId",1L));when(browser.isOpen()).thenReturn(true);
        var config=new StreamConfig(mock(KioskService.class),algorithm,new ObjectMapper(),"http://localhost:5173");
        when(algorithm.map("frame")).thenReturn(Map.of("type","PROMPT","diagnostics",Map.of("score",.9,"employeeId",123,"embedding",java.util.List.of(1,2,3)),"frameProcessed",true));
        config.new Bridge(browser).onText(socket,"frame",true);
        var sent=ArgumentCaptor.forClass(TextMessage.class);verify(browser).sendMessage(sent.capture());
        assertThat(sent.getValue().getPayload()).doesNotContain("diagnostics","score","employeeId","embedding","DIAGNOSTIC");
    }
    @Test void administratorMetricsAreWhitelistedAndRevokedLoginCannotReceiveMore()throws Exception {
        var algorithm=mock(AlgorithmClient.class);var kiosk=mock(KioskService.class);var browser=mock(WebSocketSession.class);var socket=mock(WebSocket.class);
        var login=new org.springframework.mock.web.MockHttpSession();
        var context=org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        context.setAuthentication(org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated("admin","",java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
        login.setAttribute("SPRING_SECURITY_CONTEXT",context);
        when(browser.getAttributes()).thenReturn(Map.of("sessionId","diagnostic","deviceId",1L,"diagnostic",true,"adminName","admin","loginSession",login));when(browser.isOpen()).thenReturn(true);
        var config=new StreamConfig(kiosk,algorithm,new ObjectMapper(),"http://localhost:5173");
        when(algorithm.map("frame")).thenReturn(Map.of("type","PROMPT","diagnostics",Map.of("score",.9,"employeeId",123,"embedding",java.util.List.of(1,2,3)),"frameProcessed",true));
        var bridge=config.new Bridge(browser);bridge.onText(socket,"frame",true);
        var sent=ArgumentCaptor.forClass(TextMessage.class);verify(browser,times(2)).sendMessage(sent.capture());
        assertThat(sent.getAllValues().getFirst().getPayload()).contains("DIAGNOSTIC","score").doesNotContain("employeeId","embedding");
        login.invalidate();assertThatThrownBy(()->config.diagnosticAccess(browser)).hasMessageContaining("登录已失效");
        verify(kiosk).diagnosticAccess("admin");
    }
    @Test void greetingCannotReleaseFrameAndProcessedAckSurvivesProxy()throws Exception {
        var algorithm=mock(AlgorithmClient.class);var browser=mock(WebSocketSession.class);var socket=mock(WebSocket.class);
        when(browser.getAttributes()).thenReturn(Map.of("sessionId","test","deviceId",1L));when(browser.isOpen()).thenReturn(true);
        var config=new StreamConfig(mock(KioskService.class),algorithm,new ObjectMapper(),"http://localhost:5173");
        var bridge=config.new Bridge(browser);bridge.busy=true;
        when(algorithm.map("greeting")).thenReturn(Map.of("type","PROMPT","message","hello"));
        bridge.onText(socket,"greeting",true);assertThat(bridge.busy).isTrue();
        when(algorithm.map("ack")).thenReturn(Map.of("type","PROMPT","message","blink","frameProcessed",true));
        bridge.onText(socket,"ack",true);assertThat(bridge.busy).isFalse();
        var sent=ArgumentCaptor.forClass(TextMessage.class);verify(browser,times(2)).sendMessage(sent.capture());
        assertThat(new ObjectMapper().readTree(sent.getAllValues().get(1).getPayload()).get("frameProcessed").asBoolean()).isTrue();
        bridge.onOpen(socket);verify(socket,times(3)).request(1);
        verify(browser,times(3)).sendMessage(sent.capture());
        assertThat(new ObjectMapper().readTree(sent.getValue().getPayload()).get("streamReady").asBoolean()).isTrue();
    }
}
