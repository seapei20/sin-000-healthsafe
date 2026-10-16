package co.wethinkcode.healthsafe;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Minimal JSON-over-HTTP client for the direct service-to-service calls.
 *
 * <p>Duplicated into each participating service's own source tree, since these are
 * independent Maven projects with no shared parent pom — the same convention as
 * {@code co.wethinkcode.healthsafe.mq.MqConfig}.
 */
public final class HttpJson {

    /** Shared Jackson mapper (jackson-databind is also what Javalin uses for ctx.json). */
    public static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    private HttpJson() {
    }

    /**
     * GETs {@code url}. Callers map the status code themselves; a refused or timed
     * out connection surfaces as {@link IOException} and should become a 502/503.
     */
    public static HttpResponse<String> get(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
