package com.example.backend.model;

import java.util.List;

public class ApiResponse {

    private int statusCode;
    private String message;
    private CallDetails callDetails;
    private List<User> data;

    // Getters and Setters
    public int getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(int statusCode) {
        this.statusCode = statusCode;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public CallDetails getCallDetails() {
        return callDetails;
    }

    public void setCallDetails(CallDetails callDetails) {
        this.callDetails = callDetails;
    }

    public List<User> getData() {
        return data;
    }

    public void setData(List<User> data) {
        this.data = data;
    }
}
