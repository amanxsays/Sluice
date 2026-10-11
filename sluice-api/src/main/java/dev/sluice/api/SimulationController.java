package dev.sluice.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Built-in demo upstream: deployed workers use a loopback HTTP call to this endpoint. */
@RestController
@ConditionalOnProperty(name = "sluice.simulation.enabled", havingValue = "true", matchIfMissing = true)
public class SimulationController {
    @PostMapping("/simulate")
    public ResponseEntity<String> simulate(
            @RequestParam(name = "latencyMs", defaultValue = "100") int latencyMs,
            @RequestParam(name = "shouldFail", defaultValue = "false") boolean shouldFail,
            @RequestParam(name = "retryAfterSeconds", defaultValue = "1") int retryAfterSeconds) throws InterruptedException {
        if (latencyMs < 0 || latencyMs > 30000 || retryAfterSeconds < 1 || retryAfterSeconds > 60) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "latencyMs must be 0–30000; retryAfterSeconds must be 1–60");
        }
        Thread.sleep(latencyMs);
        if (shouldFail) return ResponseEntity.status(429)
                .header("Retry-After", String.valueOf(retryAfterSeconds)).body("Rate limited");
        return ResponseEntity.ok("Success");
    }
}
