package com.example.backend.Service;
import com.example.backend.model.ApiResponse;
import com.example.backend.model.ExternalAuthRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Service
public class ExternalApiService {



    @Value("${cdr.report.api.url}")
    private String cdrApiUrl;

    private final RestTemplate restTemplate;

    public ExternalApiService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate; // uses the bean from RestTemplateConfig
    }

    public ApiResponse getUsersFromApi(String token, String username, String password) {


        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("type", "pbx");
        headers.set("x-access-token", token);
        ExternalAuthRequest authRequest = new ExternalAuthRequest(username, password, "");

        HttpEntity<ExternalAuthRequest> entity = new HttpEntity<>(authRequest, headers);

            ResponseEntity<ApiResponse> response = restTemplate.exchange(
                    cdrApiUrl,
                    HttpMethod.PATCH,
                    entity,
                    ApiResponse.class  // This tells RestTemplate to map the response to ApiResponse
            );

            return response.getBody();
        }
}

