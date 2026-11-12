package cue.edu.co.yousongbackend.security;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Test-only endpoints, one per authorization zone, used to exercise the security rules. */
@RestController
class TestEndpoints {

    @GetMapping("/api/public/ping")
    String publicPing() {
        return "pong";
    }

    @GetMapping("/api/mi-establecimiento/ping")
    String ownerPing() {
        return "pong";
    }

    @PostMapping("/api/mi-establecimiento/ping")
    String ownerPost() {
        return "posted";
    }

    @GetMapping("/api/mi-establecimiento/me")
    String me(@AuthenticationPrincipal Jwt jwt) {
        return jwt.getSubject();
    }
}
