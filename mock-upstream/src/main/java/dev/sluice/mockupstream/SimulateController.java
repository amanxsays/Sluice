package dev.sluice.mockupstream;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SimulateController {
    @PostMapping("/simulate")
    public ResponseEntity<String> simulate(@RequestParam int latencyMs, @RequestParam boolean shouldFail, @RequestParam int retryAfterSeconds) throws InterruptedException {
        Thread.sleep(latencyMs);
        if (shouldFail) {
            return ResponseEntity.status(429)
                    .header("Retry-After", String.valueOf(retryAfterSeconds))
                    .body("Rate limited");
        }
        return ResponseEntity.ok("Success");
    }
}
