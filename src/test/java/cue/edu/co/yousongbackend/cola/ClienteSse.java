package cue.edu.co.yousongbackend.cola;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A real streaming client of the queue events: a dedicated {@link HttpClient} (so closing it drops the TCP
 * connection), and a daemon thread that parses the {@code text/event-stream} body into events and comments.
 * Every wait has a timeout, so a test never blocks forever.
 */
final class ClienteSse implements AutoCloseable {

    /** One dispatched SSE event. */
    record Evento(String nombre, String id, String datos) {

        JsonNode json() {
            return JSON.readTree(datos);
        }

        long colaVersion() {
            return json().get("colaVersion").asLong();
        }
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http = HttpClient.newHttpClient();
    private final BlockingQueue<Evento> eventos = new LinkedBlockingQueue<>();
    private final List<String> comentarios = new CopyOnWriteArrayList<>();
    private final HttpResponse<InputStream> respuesta;

    private ClienteSse(int port, String path, Map<String, String> cabeceras) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Accept", "text/event-stream").GET();
        cabeceras.forEach(request::header);
        try {
            respuesta = http.sendAsync(request.build(), BodyHandlers.ofInputStream()).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            http.shutdownNow();
            throw new IllegalStateException("Could not open the stream " + path, e);
        }
        if (respuesta.statusCode() == 200) {
            Thread lector = new Thread(this::leer, "sse-test-reader");
            lector.setDaemon(true);
            lector.start();
        }
    }

    static ClienteSse abrir(int port, String path) {
        return new ClienteSse(port, path, Map.of());
    }

    static ClienteSse abrir(int port, String path, Map<String, String> cabeceras) {
        return new ClienteSse(port, path, cabeceras);
    }

    int status() {
        return respuesta.statusCode();
    }

    HttpHeaders headers() {
        return respuesta.headers();
    }

    /** The body of a non-streaming answer (an error). */
    String cuerpo() {
        try (InputStream body = respuesta.body()) {
            return new String(body.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The next event, or fails after the timeout. */
    Evento siguiente(Duration espera) {
        try {
            Evento evento = eventos.poll(espera.toMillis(), TimeUnit.MILLISECONDS);
            if (evento == null) {
                throw new AssertionError("No SSE event within " + espera);
            }
            return evento;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    Evento siguiente() {
        return siguiente(Duration.ofSeconds(10));
    }

    /** Waits for an event with this {@code colaVersion}, skipping older ones; fails on a newer one or a timeout. */
    Evento hastaVersion(long version) {
        long limite = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (true) {
            Evento evento = siguiente(Duration.ofNanos(Math.max(1, limite - System.nanoTime())));
            if (evento.colaVersion() == version) {
                return evento;
            }
            if (evento.colaVersion() > version) {
                throw new AssertionError("Expected version " + version + " but got " + evento.colaVersion());
            }
        }
    }

    /** Every event that arrives within the window (possibly none). */
    List<Evento> durante(Duration ventana) {
        List<Evento> recibidos = new ArrayList<>();
        long limite = System.nanoTime() + ventana.toNanos();
        try {
            while (true) {
                long restante = limite - System.nanoTime();
                if (restante <= 0) {
                    return recibidos;
                }
                Evento evento = eventos.poll(restante, TimeUnit.NANOSECONDS);
                if (evento != null) {
                    recibidos.add(evento);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    List<String> comentarios() {
        return comentarios;
    }

    @Override
    public void close() {
        try {
            respuesta.body().close();
        } catch (IOException ignored) {
            // The connection is dropped below anyway.
        }
        http.shutdownNow();
    }

    private void leer() {
        try (BufferedReader lineas = new BufferedReader(new InputStreamReader(respuesta.body(), StandardCharsets.UTF_8))) {
            String nombre = null;
            String id = null;
            StringBuilder datos = null;
            String linea;
            while ((linea = lineas.readLine()) != null) {
                if (linea.isEmpty()) {
                    if (datos != null) {
                        eventos.add(new Evento(nombre == null ? "message" : nombre, id, datos.toString()));
                    }
                    nombre = null;
                    datos = null;
                } else if (linea.startsWith(":")) {
                    comentarios.add(linea);
                } else {
                    int dosPuntos = linea.indexOf(':');
                    String campo = dosPuntos < 0 ? linea : linea.substring(0, dosPuntos);
                    String valor = dosPuntos < 0 ? "" : linea.substring(dosPuntos + 1);
                    if (valor.startsWith(" ")) {
                        valor = valor.substring(1);
                    }
                    switch (campo) {
                        case "event" -> nombre = valor;
                        case "id" -> id = valor;
                        case "data" -> datos = datos == null ? new StringBuilder(valor) : datos.append('\n').append(valor);
                        default -> {
                            // retry and unknown fields are ignored, as a browser would.
                        }
                    }
                }
            }
        } catch (IOException ignored) {
            // Closed by the test or by the server.
        }
    }
}
