package dev.kauzes.mizan.common.web;

import dev.kauzes.mizan.common.correlation.CorrelationContext;
import dev.kauzes.mizan.common.error.ConflictException;
import dev.kauzes.mizan.common.error.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
public class TestApplication {

    @RestController
    @RequestMapping("/test")
    static class TestController {

        @GetMapping("/not-found")
        String notFound() {
            throw new NotFoundException("no payment with that reference");
        }

        @GetMapping("/conflict")
        String conflict() {
            throw new ConflictException("that payment was already captured");
        }

        @GetMapping("/boom")
        String boom() {
            throw new IllegalStateException("connection pool exhausted on shard 7");
        }

        @GetMapping("/correlation")
        String correlation() {
            return CorrelationContext.currentOrEmpty();
        }

        @PostMapping("/validate")
        String validate(@Valid @RequestBody Payload payload) {
            return payload.reference();
        }
    }

    record Payload(@NotBlank String reference, @Positive long amount) {
    }
}
