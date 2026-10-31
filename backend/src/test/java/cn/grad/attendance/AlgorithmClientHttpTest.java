package cn.grad.attendance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class AlgorithmClientHttpTest {
    private final ObjectMapper json = new ObjectMapper();
    private final BlockingQueue<ReceivedRequest> requests = new LinkedBlockingQueue<>();
    private HttpServer server;
    private AlgorithmClient client;

    @BeforeEach
    void startHttpReceiver() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/", exchange -> {
            try (exchange) {
                requests.add(new ReceivedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getProtocol(), exchange.getRequestHeaders(), exchange.getRequestBody().readAllBytes()));
                byte[] response = "{\"ready\":true,\"galleryVersion\":7}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();
        client = new AlgorithmClient("http://127.0.0.1:" + server.getAddress().getPort(), "synthetic-internal-key", json);
    }

    @AfterEach
    void stopHttpReceiver() {
        if (server != null) server.stop(0);
    }

    @Test
    void healthRequestDoesNotAttemptH2cUpgrade() throws Exception {
        assertThat(client.health()).containsEntry("ready", true).containsEntry("galleryVersion", 7);
        ReceivedRequest request = takeHttp11Request("GET", "/internal/health");
        assertThat(request.body()).isEmpty();
    }

    @Test
    void galleryPutDeliversCompleteJsonWithoutH2cUpgrade() throws Exception {
        List<Double> template = Collections.nCopies(512, 0.125);
        Map<String, Object> gallery = Map.of("version", 7,
            "employees", List.of(Map.of("employeeId", 42, "templates", List.of(template, template, template))));

        client.putGallery(gallery);

        ReceivedRequest request = takeHttp11Request("PUT", "/internal/gallery");
        assertThat(request.headers().getFirst("Content-Type")).startsWith("application/json");
        assertThat(json.readTree(request.body())).isEqualTo(json.valueToTree(gallery));
    }

    @Test
    void sessionPostDeliversCompleteJsonWithoutH2cUpgrade() throws Exception {
        Map<String, Object> session = Map.of("sessionId", "synthetic-session", "galleryVersion", 7,
            "configVersion", 2, "livenessRequired", true, "actions", List.of("BLINK", "MOUTH"),
            "threshold", 0.55, "margin", 0.08, "expiresAt", "2026-10-24T12:00:00+08:00");

        client.createSession(session);

        ReceivedRequest request = takeHttp11Request("POST", "/internal/sessions");
        assertThat(request.headers().getFirst("Content-Type")).startsWith("application/json");
        assertThat(json.readTree(request.body())).isEqualTo(json.valueToTree(session));
    }

    private ReceivedRequest takeHttp11Request(String method, String path) throws Exception {
        ReceivedRequest request = requests.poll(2, TimeUnit.SECONDS);
        assertThat(request).as("the local HTTP receiver captured the request").isNotNull();
        assertThat(request.method()).isEqualTo(method);
        assertThat(request.path()).isEqualTo(path);
        assertThat(request.protocol()).isEqualTo("HTTP/1.1");
        assertThat(request.headers().getFirst("Upgrade")).as("Uvicorn does not support h2c upgrades").isNull();
        assertThat(request.headers().getFirst("HTTP2-Settings")).isNull();
        assertThat(request.headers().getFirst("X-Internal-Key")).isEqualTo("synthetic-internal-key");
        assertThat(request.headers().getFirst("X-Request-ID")).isNotBlank();
        return request;
    }

    private record ReceivedRequest(String method, String path, String protocol, Headers headers, byte[] body) {}
}
