// src/main/java/com/example/backend/Controller/UserController.java
package com.example.backend.Controller;

import com.example.backend.Service.UserService;
import com.example.backend.model.ExternalAuthRequest;
import com.example.backend.model.User;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;
    public UserController(UserService userService) { this.userService = userService; }

    @PatchMapping("/filtered-report")
    public ResponseEntity<?> getFilteredUsers(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authHeader,
            @CookieValue(name = "pbx_token", required = false) String cookieToken,
            @RequestBody ExternalAuthRequest loginRequest
    ) {
        // Prefer Authorization: Bearer <token>, else fall back to cookie
        String token = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7).trim();
        } else if (cookieToken != null && !cookieToken.isBlank()) {
            token = cookieToken.trim();
        }

        if (token == null || token.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body("Unauthorized User - No auth token in header/cookie");
        }

        try {
            List<User> filteredData = userService.getFilteredUsers(token,loginRequest.getUsername(),loginRequest.getPassword());
            return ResponseEntity.ok(filteredData);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body("Unauthorized User - Token is invalid");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to fetch CDR");
        }
    }
}
