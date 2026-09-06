package dev.sluice.core;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

public class SimulatedApiCallHandler implements JobHandler {
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public SimulatedApiCallHandler(String baseUrl) {
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
        this.baseUrl = baseUrl;
    }

    private record SimulationConfig(int latencyMs, boolean shouldFail, int retryAfterSeconds) {}

    @Override
    public void handle(Job job) throws Exception {
        SimulationConfig config = objectMapper.readValue(job.payload(), SimulationConfig.class);
        String url = baseUrl + "?latencyMs=" + config.latencyMs()
                + "&shouldFail=" + config.shouldFail()
                + "&retryAfterSeconds=" + config.retryAfterSeconds();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
            return;
        } else if (response.statusCode() == 429) {
            int retryAfter = Integer.parseInt(response.headers().firstValue("Retry-After").orElse("1"));
            throw new RateLimitedException(retryAfter);
        } else {
            throw new IllegalStateException("Unexpected status code from mock upstream: " + response.statusCode());
        }
    }
}


