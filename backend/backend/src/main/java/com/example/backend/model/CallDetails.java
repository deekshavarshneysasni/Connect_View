package com.example.backend.model;

public class CallDetails {

    private int dialedCall;
    private int answeredCall;
    private int unansweredCall;
    private int answeredDuration;
    private String asr; // ASR = Answer-Seizure Ratio
    private String acd; // ACD = Average Call Duration

    // Getters and Setters
    public int getDialedCall() {
        return dialedCall;
    }

    public void setDialedCall(int dialedCall) {
        this.dialedCall = dialedCall;
    }

    public int getAnsweredCall() {
        return answeredCall;
    }

    public void setAnsweredCall(int answeredCall) {
        this.answeredCall = answeredCall;
    }

    public int getUnansweredCall() {
        return unansweredCall;
    }

    public void setUnansweredCall(int unansweredCall) {
        this.unansweredCall = unansweredCall;
    }

    public int getAnsweredDuration() {
        return answeredDuration;
    }

    public void setAnsweredDuration(int answeredDuration) {
        this.answeredDuration = answeredDuration;
    }

    public String getAsr() {
        return asr;
    }

    public void setAsr(String asr) {
        this.asr = asr;
    }

    public String getAcd() {
        return acd;
    }

    public void setAcd(String acd) {
        this.acd = acd;
    }
}
