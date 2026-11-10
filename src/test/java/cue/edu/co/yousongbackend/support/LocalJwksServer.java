package cue.edu.co.yousongbackend.support;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Minimal JWKS endpoint for tests, standing in for an identity provider such as FusionAuth.
 *
 * <p>It lives in its own class on purpose: the server is started from a daemon thread, and that
 * thread must not call back into a test class that is still being initialized (class-initialization
 * deadlock). Daemon threads also keep it from holding the JVM open when Spring's AOT test processing
 * loads contexts without running the tests.
 */
public final class LocalJwksServer {

    private final HttpServer server;

    public LocalJwksServer(RSAKey signingKey) {
        byte[] body = new JWKSet(signingKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        HttpServer[] holder = new HttpServer[1];
        // HttpServer's dispatcher thread inherits the daemon flag of the thread that creates it.
        Thread starter = new Thread(() -> holder[0] = start(body), "jwks-server-starter");
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

    public String jwkSetUri() {
        return "http://localhost:" + server.getAddress().getPort() + "/.well-known/jwks.json";
    }

    public void stop() {
        server.stop(0);
    }

    private static HttpServer start(byte[] body) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/.well-known/jwks.json", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
