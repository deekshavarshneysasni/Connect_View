package com.example.backend.model;
import com.fasterxml.jackson.annotation.JsonProperty;

public class User {
    private String caller;

    @JsonProperty("endpoint")
    private String callee;

    @JsonProperty("call_type")
    private String callType;

    @JsonProperty("date_from")
    private String startTime;

    @JsonProperty("date_to")
    private String endTime;

    @JsonProperty("sessiontime")
    private String sessionTime;


    @JsonProperty("bridgetime")
    private String bridgeTime;

    @JsonProperty("termdescription")
    private String callStatus;

    private String disposition;

    @JsonProperty("dtmf")
    private String Dtmf;

    private String category;

    private String subCategory;

    private String uuid;

    public String getCaller() {
        return caller;
    }

    public void setCaller(String caller) {
        this.caller = caller;
    }

    public String getCallStatus() {
        return callStatus;
    }

    public void setCallStatus(String callStatus) {
        this.callStatus = callStatus;
    }

    public String getCallee() {
        return callee;
    }

    public void setCallee(String callee) {
        this.callee = callee;
    }

    public String getCallType() {
        return callType;
    }

    public void setCallType(String callType) {
        this.callType = callType;
    }

    public String getStartTime() {
        return startTime;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

    public String getEndTime() {
        return endTime;
    }

    public void setEndTime(String endTime) {
        this.endTime = endTime;
    }

    public String getSessionTime() {
        return sessionTime;
    }

    public void setSessionTime(String sessionTime) {
        this.sessionTime = sessionTime;
    }

    public String getBridgeTime() {
        return bridgeTime;
    }

    public void setBridgeTime(String bridgeTime) {
        this.bridgeTime = bridgeTime;
    }

    public String getDisposition() {
        return disposition;
    }

    public void setDisposition(String disposition) {
        this.disposition = disposition;
    }

    public String getDtmf() {
        return Dtmf;
    }

    public void setDtmf(String Dtmf) {
        this.Dtmf = Dtmf;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getSubCategory() {
        return subCategory;
    }

    public void setSubCategory(String subCategory) {
        this.subCategory = subCategory;
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }


}
