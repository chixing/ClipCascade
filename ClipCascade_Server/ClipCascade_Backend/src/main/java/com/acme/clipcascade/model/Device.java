package com.acme.clipcascade.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.validation.constraints.NotNull;

@Entity
@Table(name = "devices")
public class Device {

    @Id
    @NotNull(message = "Device ID is required")
    private String id;

    @NotNull(message = "Username is required")
    @Column(nullable = false)
    private String username;

    @Column(length = 100)
    private String friendlyName;

    @Column(length = 50)
    private String deviceType;

    @Column(length = 100)
    private String osInfo;

    @Column(length = 100)
    private String ipAddress;

    private Long firstSeen;

    private Long lastSeen;

    @Transient
    private boolean online;

    public Device() {
    }

    public Device(String id, String username) {
        this.id = id;
        this.username = username;
        this.firstSeen = System.currentTimeMillis() / 1000;
        this.lastSeen = this.firstSeen;
    }

    public Device(String id, String username, String friendlyName, String deviceType, String osInfo) {
        this(id, username, friendlyName, deviceType, osInfo, null);
    }

    public Device(String id, String username, String friendlyName, String deviceType, String osInfo, String ipAddress) {
        this.id = id;
        this.username = username;
        this.friendlyName = friendlyName;
        this.deviceType = deviceType;
        this.osInfo = osInfo;
        this.ipAddress = ipAddress;
        this.firstSeen = System.currentTimeMillis() / 1000;
        this.lastSeen = this.firstSeen;
    }

    public String getId() {
        return this.id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUsername() {
        return this.username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getFriendlyName() {
        return this.friendlyName;
    }

    public void setFriendlyName(String friendlyName) {
        this.friendlyName = friendlyName;
    }

    public String getDeviceType() {
        return this.deviceType;
    }

    public void setDeviceType(String deviceType) {
        this.deviceType = deviceType;
    }

    public String getOsInfo() {
        return this.osInfo;
    }

    public void setOsInfo(String osInfo) {
        this.osInfo = osInfo;
    }

    public Long getFirstSeen() {
        return this.firstSeen;
    }

    public void setFirstSeen(Long firstSeen) {
        this.firstSeen = firstSeen;
    }

    public Long getLastSeen() {
        return this.lastSeen;
    }

    public void setLastSeen(Long lastSeen) {
        this.lastSeen = lastSeen;
    }

    public boolean isOnline() {
        return this.online;
    }

    public void setOnline(boolean online) {
        this.online = online;
    }

    public String getIpAddress() {
        return this.ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getDisplayName() {
        if (this.friendlyName != null && !this.friendlyName.trim().isEmpty()) {
            return this.friendlyName;
        }
        StringBuilder sb = new StringBuilder();
        if (this.osInfo != null && !this.osInfo.trim().isEmpty() && !"Unknown OS".equalsIgnoreCase(this.osInfo.trim())) {
            sb.append(this.osInfo);
        } else if (this.deviceType != null && !this.deviceType.trim().isEmpty() && !"unknown".equalsIgnoreCase(this.deviceType.trim())) {
            sb.append(Character.toUpperCase(this.deviceType.charAt(0))).append(this.deviceType.substring(1));
        } else {
            sb.append("Device");
        }

        if (this.ipAddress != null && !this.ipAddress.trim().isEmpty() && !"0.0.0.0".equals(this.ipAddress)) {
            sb.append(" (").append(this.ipAddress).append(")");
        } else if (this.id != null && !this.id.isEmpty()) {
            sb.append(" (").append(this.id.substring(0, Math.min(8, this.id.length()))).append(")");
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "{" +
                " id='" + getId() + "'" +
                ", username='" + getUsername() + "'" +
                ", friendlyName='" + getFriendlyName() + "'" +
                ", deviceType='" + getDeviceType() + "'" +
                ", osInfo='" + getOsInfo() + "'" +
                ", ipAddress='" + getIpAddress() + "'" +
                ", firstSeen='" + getFirstSeen() + "'" +
                ", lastSeen='" + getLastSeen() + "'" +
                ", online='" + isOnline() + "'" +
                "}";
    }
}
