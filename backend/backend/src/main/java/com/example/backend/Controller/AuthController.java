package com.example.backend.Controller;

import com.example.backend.Service.ExternalAuthClient;

import com.example.backend.model.ExternalAuthRequest;
import com.example.backend.model.ExternalAuthResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;


@RestController
@RequestMapping("/pbx")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final ExternalAuthClient externalAuthClient;

    public AuthController(AuthenticationManager authenticationManager, ExternalAuthClient externalAuthClient) {
        this.authenticationManager = authenticationManager;
        this.externalAuthClient = externalAuthClient;
    }

    @PostMapping("/auth/login")
    public ResponseEntity<?> login(@RequestBody ExternalAuthRequest loginRequest, HttpSession session) {

        // Call the external API to authenticate
        try {
            ExternalAuthResponse externalResponse = externalAuthClient.authenticate(
                    loginRequest.getUsername(),
                    loginRequest.getPassword()
            );

            // Check if authentication was successful
            if (externalResponse != null && externalResponse.isAuthenticated()) {
                // If successful, save the token
                // we could store this token in the session
                session.setAttribute("auth_token", externalResponse.getToken());
                return ResponseEntity.ok().body(externalResponse);
            }
        }catch (Exception ex){
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Invalid credential : please provide valid Credentials.");
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body("Invalid credential : please provide valid Credentials.");

    }

}


