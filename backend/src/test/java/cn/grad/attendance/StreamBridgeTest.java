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
