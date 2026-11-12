package cue.edu.co.yousongbackend.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * Minimal stand-in for the YouTube Data API v3 ({@code /search} and {@code /videos}) for tests.
 *
 * <p>Like {@link LocalJwksServer} it is its own class and starts from a daemon thread, so it neither
 * deadlocks class initialization of a test class nor keeps the JVM alive. Responses are scripted per path
 * ({@link #script}); without a script {@code /videos} answers from the registered {@link #video videos}
 * (unknown ids are simply absent, as in the real API). Every request is recorded.
 */
public final class LocalYoutubeServer {

    public record Recibida(String path, Map<String, String> query) {
        public String param(String name) {
            return query.get(name);
        }
    }

    private record Respuesta(int status, String body) {
    }

    private final HttpServer server;
    private final List<Recibida> recibidas = new CopyOnWriteArrayList<>();
    private final Map<String, Respuesta> guiones = new ConcurrentHashMap<>();
    private final Map<String, Long> demoras = new ConcurrentHashMap<>();
    private final Map<String, Long> demorasPorConsulta = new ConcurrentHashMap<>();
    private final Map<String, String> videos = new ConcurrentHashMap<>();

    public LocalYoutubeServer() {
        HttpServer[] holder = new HttpServer[1];
        Thread starter = new Thread(() -> holder[0] = iniciar(), "youtube-server-starter");
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
        return "http://localhost:" + server.getAddress().getPort() + "/youtube/v3";
    }

    public void stop() {
        server.stop(0);
    }

    // ---------- scripting ----------

    /** Fixed answer for a path such as {@code /search}. */
    public void script(String path, int status, String body) {
        guiones.put(path, new Respuesta(status, body));
    }

    /** Waits this long before answering requests to the path. */
    public void delay(String path, long millis) {
        demoras.put(path, millis);
    }

    /** Waits this long before answering requests to the path whose {@code q} parameter is exactly this query. */
    public void delay(String path, String query, long millis) {
        demorasPorConsulta.put(path + "\u0000" + query, millis);
    }

    /** Registers a video that {@code /videos} knows. */
    public void register(Video video) {
        videos.put(video.id, video.json());
    }

    /** Forgets scripts, delays, videos and recorded requests. Call it before every test. */
    public void reset() {
        guiones.clear();
        demoras.clear();
        demorasPorConsulta.clear();
        videos.clear();
        recibidas.clear();
    }

    // ---------- inspection ----------

    public List<Recibida> requests(String path) {
        return recibidas.stream().filter(r -> r.path().equals(path)).toList();
    }

    public int count(String path) {
        return requests(path).size();
    }

    public int count() {
        return recibidas.size();
    }

    public Recibida last(String path) {
        List<Recibida> hechas = requests(path);
        return hechas.get(hechas.size() - 1);
    }

    // ---------- canned bodies ----------

    public static String searchJson(String nextPageToken, String... videoIds) {
        StringBuilder items = new StringBuilder();
        for (String id : videoIds) {
            items.append(items.length() == 0 ? "" : ",").append("""
                    {"kind":"youtube#searchResult","id":{"kind":"youtube#video","videoId":"%s"},"snippet":{"title":"x"}}""".formatted(id));
        }
        return "{\"kind\":\"youtube#searchListResponse\""
                + (nextPageToken == null ? "" : ",\"nextPageToken\":\"" + nextPageToken + "\"")
                + ",\"items\":[" + items + "]}";
    }

    public static String errorJson(int code, String reason) {
        return """
                {"error":{"code":%d,"message":"scripted failure","errors":[{"reason":"%s","domain":"youtube.quota"}]}}"""
                .formatted(code, reason);
    }

    public static Video video(String id) {
        return new Video(id);
    }

    /** One videos.list item. Defaults describe a healthy, embeddable, public, processed video. */
    public static final class Video {
        private final String id;
        private String titulo = "Titulo de prueba";
        private String canal = "Canal de prueba";
        private String duracion = "PT3M30S";
        private String miniaturas;
        private boolean incrustable = true;
        private String privacidad = "public";
        private String subida = "processed";
        private String envivo = "none";
        private String restriccion;

        private Video(String id) {
            this.id = id;
            this.miniaturas = "{\"default\":{\"url\":\"https://img.test/" + id + "/default.jpg\"},"
                    + "\"medium\":{\"url\":\"https://img.test/" + id + "/mqdefault.jpg\"},"
                    + "\"high\":{\"url\":\"https://img.test/" + id + "/hqdefault.jpg\"}}";
        }

        public String id() {
            return id;
        }

        public Video titulo(String valor) {
            this.titulo = valor;
            return this;
        }

        public Video canal(String valor) {
            this.canal = valor;
            return this;
        }

        public Video duracion(String valor) {
            this.duracion = valor;
            return this;
        }

        public Video miniaturas(String json) {
            this.miniaturas = json;
            return this;
        }

        public Video incrustable(boolean valor) {
            this.incrustable = valor;
            return this;
        }

        public Video privacidad(String valor) {
            this.privacidad = valor;
            return this;
        }

        public Video subida(String valor) {
            this.subida = valor;
            return this;
        }

        public Video envivo(String valor) {
            this.envivo = valor;
            return this;
        }

        public Video restriccion(String json) {
            this.restriccion = json;
            return this;
        }

        public String json() {
            StringBuilder detalles = new StringBuilder();
            List<String> partes = new ArrayList<>();
            if (duracion != null) {
                partes.add("\"duration\":\"" + duracion + "\"");
            }
            if (restriccion != null) {
                partes.add("\"regionRestriction\":" + restriccion);
            }
            detalles.append("{").append(String.join(",", partes)).append("}");
            return """
                    {"kind":"youtube#video","id":"%s",
                     "snippet":{"title":"%s","channelTitle":"%s","liveBroadcastContent":"%s","thumbnails":%s},
                     "contentDetails":%s,
                     "status":{"uploadStatus":"%s","privacyStatus":"%s","embeddable":%s}}"""
                    .formatted(id, titulo, canal, envivo, miniaturas, detalles, subida, privacidad, incrustable);
        }
    }

    // ---------- server ----------

    private HttpServer iniciar() {
        try {
            HttpServer http = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            // Delayed answers must not block the next request: serve each one on its own daemon thread.
            http.setExecutor(Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "youtube-server-worker");
                t.setDaemon(true);
                return t;
            }));
            http.createContext("/youtube/v3", this::atender);
            http.start();
            return http;
        } catch (IOException e) {
            throw new IllegalStateException("Could not start the local YouTube server", e);
        }
    }

    private void atender(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath().substring("/youtube/v3".length());
        Map<String, String> query = parse(exchange.getRequestURI().getRawQuery());
        recibidas.add(new Recibida(path, query));
        try {
            Long demora = demoras.get(path);
            Long demoraConsulta = demorasPorConsulta.get(path + "\u0000" + query.get("q"));
            if (demoraConsulta != null) {
                demora = demoraConsulta;
            }
            if (demora != null) {
                Thread.sleep(demora);
            }
            Respuesta respuesta = guiones.get(path);
            if (respuesta == null && path.equals("/videos")) {
                respuesta = new Respuesta(200, videosJson(query.getOrDefault("id", "")));
            }
            if (respuesta == null) {
                respuesta = new Respuesta(404, errorJson(404, "notFound"));
            }
            byte[] cuerpo = respuesta.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(respuesta.status(), cuerpo.length);
            exchange.getResponseBody().write(cuerpo);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // The client gave up (timeout test): nothing to answer to.
        } finally {
            exchange.close();
        }
    }

    private String videosJson(String ids) {
        List<String> items = new ArrayList<>();
        for (String id : ids.split(",")) {
            String json = videos.get(id);
            if (json != null) {
                items.add(json);
            }
        }
        return "{\"kind\":\"youtube#videoListResponse\",\"items\":[" + String.join(",", items) + "]}";
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
