package com.example.bankingrisk.local;

import com.example.bankingrisk.security.JwtTokenService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Issues JWT tokens for seeded demo users.
 * Active only in the local profile — not available in test or production.
 */
@RestController
@Profile("local")
@RequestMapping("/dev")
class DevTokenController {

    static final UUID ANALYST_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000099");

    private final JwtTokenService jwtTokenService;

    DevTokenController(JwtTokenService jwtTokenService) {
        this.jwtTokenService = jwtTokenService;
    }

    /**
     * GET /dev/token?user=customer1|customer2|analyst
     * Returns {"token":"...","userId":"...","role":"..."}
     */
    @GetMapping("/token")
    Map<String, String> getToken(@RequestParam(defaultValue = "customer1") String user) {
        return switch (user) {
            case "customer1" -> tokenResponse(LocalDataSeeder.USER_1_ID, "CUSTOMER");
            case "customer2" -> tokenResponse(LocalDataSeeder.USER_2_ID, "CUSTOMER");
            case "analyst"   -> tokenResponse(ANALYST_USER_ID, "ANALYST");
            default -> throw new IllegalArgumentException(
                    "Unknown user: '" + user + "'. Valid values: customer1, customer2, analyst");
        };
    }

    private Map<String, String> tokenResponse(UUID userId, String role) {
        String token = jwtTokenService.generateToken(userId, Set.of(role));
        return Map.of("token", token, "userId", userId.toString(), "role", role);
    }
}
