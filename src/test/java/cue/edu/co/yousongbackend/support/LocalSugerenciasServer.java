package cue.edu.co.yousongbackend.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * Stand-in for the unofficial YouTube suggest endpoint ({@code /complete/search}). Like
 * {@link LocalYoutubeServer} it is its own class and starts from a daemon thread. The answer (status, body,
 * delay) is scripted; every request is recorded with its query parameters and headers.
 */
public final class LocalSugerenciasServer {

    public record Recibida(Map<String, String> query, Map<String, List<String>> headers) {
        public String param(String name) {
            return query.get(name);
        }

        public boolean hasHeader(String name) {
            return headers.keySet().stream().anyMatch(h -> h.equalsIgnoreCase(name));
        }

        public String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> String.join(",", e.getValue())).findFirst().orElse(null);
        }
    }

    private final HttpServer server;
    private final List<Recibida> recibidas = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String body = "[\"\",[]]";
    private volatile String contentType = "text/javascript; charset=UTF-8";
    private volatile long delayMillis;

    public LocalSugerenciasServer() {
        HttpServer[] holder = new HttpServer[1];
        Thread starter = new Thread(() -> holder[0] = iniciar(), "suggest-server-starter");
        starter.setDaemon(true);
        starter.start();
        try {
            starter.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        this.server = holder[0];
    }

    public String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort() + "/complete/search";
    }

    /** Fixed answer for every request. */
    public void script(int status, String body) {
        this.status = status;
        this.body = body;
    }

    public void contentType(String value) {
        this.contentType = value;
    }

    /** Waits this long before answering. */
    public void delay(long millis) {
        this.delayMillis = millis;
    }

    /** Answers {@code ["query",["s1","s2",...]]}, the shape of the real endpoint. */
    public void suggestions(String... values) {
        StringBuilder json = new StringBuilder("[\"q\",[");
        for (int i = 0; i < values.length; i++) {
            json.append(i == 0 ? "" : ",").append('"').append(values[i].replace("\"", "\\\"")).append('"');
        }
        script(200, json.append("]]").toString());
    }

    /** Forgets the script, the delay and the recorded requests. Call it before every test. */
    public void reset() {
        status = 200;
        body = "[\"\",[]]";
        contentType = "text/javascript; charset=UTF-8";
        delayMillis = 0;
        recibidas.clear();
    }

    public int count() {
        return recibidas.size();
    }

    public List<Recibida> requests() {
        return List.copyOf(recibidas);
    }

    public Recibida last() {
        return recibidas.get(recibidas.size() - 1);
    }

    private HttpServer iniciar() {
        try {
            HttpServer http = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            http.setExecutor(Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "suggest-server-worker");
                t.setDaemon(true);
                return t;
            }));
            http.createContext("/complete/search", this::atender);
            http.start();
            return http;
        } catch (IOException e) {
            throw new IllegalStateException("Could not start the local suggest server", e);
        }
    }

    private void atender(HttpExchange exchange) throws IOException {
        recibidas.add(new Recibida(parse(exchange.getRequestURI().getRawQuery()),
                Map.copyOf(exchange.getRequestHeaders())));
        try {
            if (delayMillis > 0) {
                Thread.sleep(delayMillis);
            }
            byte[] cuerpo = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(status, cuerpo.length);
            exchange.getResponseBody().write(cuerpo);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // The client gave up (timeout test): nothing to answer to.
        } finally {
            exchange.close();
        }
    }

    private static Map<String, String> parse(String raw) {
        Map<String, String> query = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return query;
        }
        Arrays.stream(raw.split("&")).forEach(pair -> {
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            query.put(key, value);
        });
        return query;
    }
}
