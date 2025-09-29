package com.example.backend.Service;

import com.example.backend.model.ExternalAuthRequest;
import com.example.backend.model.ExternalAuthResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class ExternalAuthClient {


    @Value("${cdr.auth.api.url}")
    private String authApiUrl;
     @Value("${user_type}")
     private String userType;

    private final RestTemplate restTemplate;

    public ExternalAuthClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate; // uses the bean from RestTemplateConfig
    }
    /*@Autowired
    public ExternalAuthClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }*/


    public ExternalAuthResponse authenticate(String username, String password) {
            // Construct the request body for the external login API
            ExternalAuthRequest authRequest = new ExternalAuthRequest(username, password, userType);

            // Call the external API (e.g., POST /auth/login)
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("type", "pbx");

            HttpEntity<ExternalAuthRequest> entity = new HttpEntity<>(authRequest, headers);

            ResponseEntity<ExternalAuthResponse> response = restTemplate.exchange(
                    authApiUrl,
                    HttpMethod.POST,
                    entity,
                    ExternalAuthResponse.class
            );
            if(null != response && null != response.getStatusCode() && null != response.getBody() && response.getStatusCode().is2xxSuccessful() &! response.getBody().getToken().isBlank()){
                response.getBody().setAuthenticated(true);
            }

            return response.getBody();  // Return the token or error info
        }
    }


