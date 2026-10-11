package dev.sluice.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "sluice.lab.enabled", havingValue = "true", matchIfMissing = true)
public class TestLabController {
    private final TestLabService lab;
    public TestLabController(TestLabService lab) { this.lab = lab; }
    @PostMapping("/tests/runs") public TestLabService.Snapshot start(@RequestBody TestLabService.Options options) { return lab.start(options); }
    @GetMapping("/tests/runs/current") public TestLabService.Snapshot current() { return lab.current(); }
    @GetMapping("/tests/runs/{id}") public TestLabService.Snapshot get(@PathVariable("id") String id) { return lab.get(id); }
}
