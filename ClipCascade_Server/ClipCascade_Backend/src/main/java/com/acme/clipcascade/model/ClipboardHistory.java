package com.acme.clipcascade.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.validation.constraints.NotNull;

@Entity
@Table(name = "clipboard_history")
public class ClipboardHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull(message = "Username is required")
    @Column(nullable = false)
    private String username;

    @Column(length = 64)
    private String deviceId;

    @NotNull(message = "Payload type is required")
    @Column(nullable = false, length = 50)
    private String payloadType;

    @Lob
    @Column(columnDefinition = "CLOB")
    private String payload;

    private Long payloadSize;

    @NotNull(message = "Created at timestamp is required")
    @Column(nullable = false)
    private Long createdAt;

    @Transient
    private String deviceName;

    public ClipboardHistory() {
    }

    public ClipboardHistory(String username, String deviceId, String payloadType, String payload) {
        this.username = username;
        this.deviceId = deviceId;
        this.payloadType = payloadType;
        this.payload = payload;
        this.payloadSize = payload != null ? (long) payload.length() : 0L;
        this.createdAt = System.currentTimeMillis() / 1000;
    }

    public Long getId() {
        return this.id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return this.username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getDeviceId() {
        return this.deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getPayloadType() {
        return this.payloadType;
    }

    public void setPayloadType(String payloadType) {
        this.payloadType = payloadType;
    }

    public String getPayload() {
        return this.payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
        this.payloadSize = payload != null ? (long) payload.length() : 0L;
    }

    public Long getPayloadSize() {
        return this.payloadSize;
    }

    public void setPayloadSize(Long payloadSize) {
        this.payloadSize = payloadSize;
    }

    public Long getCreatedAt() {
        return this.createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public String getDeviceName() {
        return this.deviceName;
    }

    public void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }

    public String getPayloadPreview() {
        if (this.payload == null) {
            return "";
        }
        if ("image".equals(this.payloadType)) {
            return "[Image: " + formatSize(this.payloadSize) + "]";
        }
        if ("file".equals(this.payloadType) || "files".equals(this.payloadType)) {
            return "[File: " + formatSize(this.payloadSize) + "]";
        }
        if (this.payload.length() <= 100) {
            return this.payload;
        }
        return this.payload.substring(0, 100) + "...";
    }

    private String formatSize(Long bytes) {
        if (bytes == null || bytes == 0) {
            return "0 B";
        }
        String[] units = {"B", "KB", "MB", "GB"};
        int unitIndex = 0;
        double size = bytes;
        while (size >= 1024 && unitIndex < units.length - 1) {
            size /= 1024;
            unitIndex++;
        }
        return String.format("%.1f %s", size, units[unitIndex]);
    }

    @Override
    public String toString() {
        return "{" +
                " id='" + getId() + "'" +
                ", username='" + getUsername() + "'" +
                ", deviceId='" + getDeviceId() + "'" +
                ", payloadType='" + getPayloadType() + "'" +
                ", payloadSize='" + getPayloadSize() + "'" +
                ", createdAt='" + getCreatedAt() + "'" +
                "}";
    }
}
