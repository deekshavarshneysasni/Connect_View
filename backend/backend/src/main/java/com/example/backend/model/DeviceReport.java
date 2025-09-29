package com.example.backend.model;

public class DeviceReport {
    private String macAddress;
    private String sn;
    private String deviceName;
    private String siteName;
    private String deviceModel;
    private String firmwareVersion;
    private int status;            // 0-offline, 1-online, -1-abnormal
    private int pushConfiguration; // isSynchronized (0/1)
    private String lastConfigTime; // lastTime

    // New fields for Account 1
    private String account1UserId;
    private String account1SipServer;

    // Getters + Setters
    public String getMacAddress() { return macAddress; }
    public void setMacAddress(String macAddress) { this.macAddress = macAddress; }

    public String getSn() { return sn; }
    public void setSn(String sn) { this.sn = sn; }

    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }

    public String getSiteName() { return siteName; }
    public void setSiteName(String siteName) { this.siteName = siteName; }

    public String getDeviceModel() { return deviceModel; }
    public void setDeviceModel(String deviceModel) { this.deviceModel = deviceModel; }

    public String getFirmwareVersion() { return firmwareVersion; }
    public void setFirmwareVersion(String firmwareVersion) { this.firmwareVersion = firmwareVersion; }

    public int getStatus() { return status; }
    public void setStatus(int status) { this.status = status; }

    public int getPushConfiguration() { return pushConfiguration; }
    public void setPushConfiguration(int pushConfiguration) { this.pushConfiguration = pushConfiguration; }

    public String getLastConfigTime() { return lastConfigTime; }
    public void setLastConfigTime(String lastConfigTime) { this.lastConfigTime = lastConfigTime; }

    public String getAccount1UserId() { return account1UserId; }
    public void setAccount1UserId(String account1UserId) { this.account1UserId = account1UserId; }

    public String getAccount1SipServer() { return account1SipServer; }
    public void setAccount1SipServer(String account1SipServer) { this.account1SipServer = account1SipServer; }
}
