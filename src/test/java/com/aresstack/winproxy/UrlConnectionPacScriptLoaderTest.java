package com.aresstack.winproxy;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cache headers and conditional reload against a local HTTP server. */
class UrlConnectionPacScriptLoaderTest {

    private static final String SCRIPT = "function FindProxyForURL(url, host) { return 'DIRECT'; }";

    private HttpServer server;
    private final AtomicInteger fullDownloads = new AtomicInteger();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/wpad.dat", exchange -> {
            exchange.getResponseHeaders().add("Cache-Control", "max-age=1800");
            exchange.getResponseHeaders().add("ETag", "\"v1\"");
            if ("\"v1\"".equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
                exchange.sendResponseHeaders(304, -1);
                exchange.close();
                return;
            }
            fullDownloads.incrementAndGet();
            byte[] body = SCRIPT.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            OutputStream out = exchange.getResponseBody();
            out.write(body);
            out.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/wpad.dat";
    }

    @Test
    void readsCacheHeadersAndRevalidatesWithTheEtag() throws Exception {
        String url = start();
        UrlConnectionPacScriptLoader loader = new UrlConnectionPacScriptLoader();

        PacScript first = loader.load(url, null);
        PacScript second = loader.load(url, first);

        assertFalse(first.isNotModified());
        assertEquals(SCRIPT + "\n", first.getContent());
        assertEquals(1_800_000L, first.getMaxAgeMillis());
        assertEquals("\"v1\"", first.getEtag());
        assertTrue(second.isNotModified());
        assertEquals(first.getContent(), second.getContent());
        assertEquals(1, fullDownloads.get());
    }
}
