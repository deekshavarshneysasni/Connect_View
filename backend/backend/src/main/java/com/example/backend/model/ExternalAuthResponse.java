package com.example.backend.model;

public class ExternalAuthResponse {
    private boolean authenticated;
    private String token;
    private String message;

    public boolean isAuthenticated() {
        return authenticated;
    }

    public String getToken() {
        return token;
    }

    public String getMessage() {
        return message;
    }

    public void setAuthenticated(boolean authenticated) {
        this.authenticated = authenticated;
    }
}
