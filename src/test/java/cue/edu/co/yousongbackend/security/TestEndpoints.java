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

    @GetMapping("/api/staff/ping")
    String staffPing() {
        return "pong";
    }

    @PostMapping("/api/staff/ping")
    String staffPost() {
        return "posted";
    }

    @GetMapping("/api/staff/me")
    String me(@AuthenticationPrincipal Jwt jwt) {
        return jwt.getSubject();
    }

    @GetMapping("/api/admin/ping")
    String adminPing() {
        return "pong";
    }
}
