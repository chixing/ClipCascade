package com.acme.clipcascade.service;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.acme.clipcascade.model.Device;
import com.acme.clipcascade.repo.DeviceRepo;

@Service
public class DeviceService {

    private final DeviceRepo deviceRepo;

    // A device can have overlapping reconnects or several browser tabs.
    // Access to both maps is synchronized so disconnects remove only their own session.
    private final Map<String, Set<String>> onlineDevices = new HashMap<>();
    private final Map<String, String> sessionToDevice = new HashMap<>();

    public DeviceService(DeviceRepo deviceRepo) {
        this.deviceRepo = deviceRepo;
    }

    public Device registerDevice(String deviceId, String username, String deviceType, String osInfo) {
        return registerDevice(deviceId, username, deviceType, osInfo, null, null);
    }

    public synchronized Device registerDevice(String deviceId, String username, String deviceType, String osInfo, String ipAddress, String friendlyName) {
        deviceId = normalize(deviceId, Integer.MAX_VALUE);
        if (deviceId != null && deviceId.length() > 64) {
            deviceId = "client-" + UUID.nameUUIDFromBytes(deviceId.getBytes(StandardCharsets.UTF_8));
        }
        deviceType = normalize(deviceType, 50);
        osInfo = normalize(osInfo, 100);
        // Truncating an address would make a different, misleading identity.
        ipAddress = ipAddress != null && ipAddress.trim().length() > 100 ? null : normalize(ipAddress, 100);
        friendlyName = normalize(friendlyName, 100);

        // Only stock clients without a persistent ID use the legacy IP-based key.
        // Keep its format unchanged so existing names and history remain associated.
        boolean hasPersistentId = deviceId != null;
        if (!hasPersistentId) {
            if (ipAddress != null && !ipAddress.trim().isEmpty() && !ipAddress.equals("127.0.0.1") && !ipAddress.equals("localhost")) {
                String raw = username + ":" + ipAddress + ":" + (deviceType != null ? deviceType.toLowerCase(Locale.ROOT) : "generic");
                deviceId = "dev-" + UUID.nameUUIDFromBytes(raw.getBytes(StandardCharsets.UTF_8));
            } else {
                deviceId = "dev-" + UUID.randomUUID();
            }
        }

        Device device = deviceRepo.findById(deviceId).orElse(null);
        if ((hasPersistentId && device == null) || (device != null && !username.equals(device.getUsername()))) {
            // Namespace every NEW client-supplied ID. A concurrent registration
            // by another owner cannot insert the same assigned primary key before
            // this transaction commits. Preserve raw IDs only for confirmed owners.
            String ownerKey = username.length() + ":" + username + ":client-id:" + deviceId;
            deviceId = "dev-" + UUID.nameUUIDFromBytes(ownerKey.getBytes(StandardCharsets.UTF_8));
            device = deviceRepo.findById(deviceId).orElse(null);
            if (device != null && !username.equals(device.getUsername())) {
                throw new IllegalArgumentException("Device ID belongs to another user");
            }
        }

        if (device == null) {
            device = new Device(deviceId, username);
            device.setDeviceType(deviceType);
            device.setOsInfo(osInfo);
            device.setIpAddress(ipAddress);
            if (friendlyName != null) {
                device.setFriendlyName(friendlyName.trim());
            }
        } else {
            device.setLastSeen(System.currentTimeMillis() / 1000);
            if (deviceType != null && !deviceType.trim().isEmpty()) {
                device.setDeviceType(deviceType.trim());
            }
            if (osInfo != null && !osInfo.trim().isEmpty()) {
                device.setOsInfo(osInfo.trim());
            }
            if (ipAddress != null && !ipAddress.trim().isEmpty()) {
                device.setIpAddress(ipAddress.trim());
            }
            if ((device.getFriendlyName() == null || device.getFriendlyName().isBlank()) && friendlyName != null) {
                device.setFriendlyName(friendlyName.trim());
            }
        }

        // Registration callers do not open a surrounding transaction. Let the
        // repository commit before returning, while this monitor is still held,
        // so the next connection can see the newly inserted row.
        return deviceRepo.saveAndFlush(device);
    }

    public synchronized void markDeviceOnline(String deviceId, String sessionId) {
        if (deviceId == null || sessionId == null) {
            return;
        }
        String previousDeviceId = sessionToDevice.put(sessionId, deviceId);
        if (previousDeviceId != null && !previousDeviceId.equals(deviceId)) {
            removeOnlineSession(previousDeviceId, sessionId);
        }
        onlineDevices.computeIfAbsent(deviceId, ignored -> new HashSet<>()).add(sessionId);

        // Update last seen in database
        deviceRepo.findById(deviceId).ifPresent(device -> {
            device.setLastSeen(System.currentTimeMillis() / 1000);
            deviceRepo.save(device);
        });
    }

    public synchronized boolean refreshDeviceForSession(String deviceId, String sessionId) {
        // Queued messages and CONNECTED notifications can arrive after a close.
        // Only an initial CONNECT may introduce a session into presence tracking.
        if (!sessionToDevice.containsKey(sessionId)) {
            return false;
        }
        markDeviceOnline(deviceId, sessionId);
        return true;
    }

    public synchronized void markDeviceOffline(String sessionId) {
        String deviceId = sessionToDevice.remove(sessionId);
        if (deviceId != null) {
            removeOnlineSession(deviceId, sessionId);
        }
    }

    private void removeOnlineSession(String deviceId, String sessionId) {
        Set<String> sessions = onlineDevices.get(deviceId);
        if (sessions != null) {
            sessions.remove(sessionId);
            if (sessions.isEmpty()) {
                onlineDevices.remove(deviceId);
            }
        }
    }

    private static String normalize(String value, int maximumLength) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.substring(0, Math.min(trimmed.length(), maximumLength));
    }

    public synchronized boolean isDeviceOnline(String deviceId) {
        return onlineDevices.containsKey(deviceId);
    }

    public synchronized Set<String> getOnlineDeviceIds() {
        return Set.copyOf(onlineDevices.keySet());
    }

    public synchronized String getDeviceIdForSession(String sessionId) {
        return sessionToDevice.get(sessionId);
    }

    public List<Device> getDevicesForUser(String username) {
        List<Device> devices = deviceRepo.findByUsernameOrderByLastSeenDesc(username);
        for (Device device : devices) {
            device.setOnline(isDeviceOnline(device.getId()));
        }
        return devices;
    }

    public Device getDevice(String deviceId) {
        return deviceRepo.findById(deviceId).orElse(null);
    }

    @Transactional
    public boolean updateFriendlyName(String deviceId, String username, String friendlyName) {
        int updated = deviceRepo.updateFriendlyName(deviceId, username, friendlyName);
        return updated > 0;
    }

    @Transactional
    public synchronized boolean deleteDevice(String deviceId, String username) {
        int deleted = deviceRepo.deleteByIdAndUsername(deviceId, username);
        if (deleted > 0) {
            clearOnlineDevice(deviceId);
        }
        return deleted > 0;
    }

    @Transactional
    public synchronized void deleteDevicesForUser(String username) {
        List<Device> devices = deviceRepo.findByUsername(username);
        deviceRepo.deleteByUsername(username);
        devices.forEach(device -> clearOnlineDevice(device.getId()));
    }

    private void clearOnlineDevice(String deviceId) {
        onlineDevices.remove(deviceId);
        sessionToDevice.entrySet().removeIf(entry -> deviceId.equals(entry.getValue()));
    }

    public synchronized long getOnlineDeviceCount() {
        return onlineDevices.size();
    }

    public long getTotalDeviceCount() {
        return deviceRepo.count();
    }
}
