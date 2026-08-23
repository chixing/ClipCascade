package com.acme.clipcascade.service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.acme.clipcascade.model.Device;
import com.acme.clipcascade.repo.DeviceRepo;

@Service
public class DeviceService {

    private final DeviceRepo deviceRepo;

    // In-memory tracking of online devices: deviceId -> sessionId
    private final Map<String, String> onlineDevices = new ConcurrentHashMap<>();
    // Reverse mapping: sessionId -> deviceId
    private final Map<String, String> sessionToDevice = new ConcurrentHashMap<>();

    public DeviceService(DeviceRepo deviceRepo) {
        this.deviceRepo = deviceRepo;
    }

    @Transactional
    public Device registerDevice(String deviceId, String username, String deviceType, String osInfo) {
        return registerDevice(deviceId, username, deviceType, osInfo, null, null);
    }

    @Transactional
    public Device registerDevice(String deviceId, String username, String deviceType, String osInfo, String ipAddress, String friendlyName) {
        Device device = deviceRepo.findById(deviceId).orElse(null);

        if (device == null) {
            device = new Device(deviceId, username);
            device.setDeviceType(deviceType);
            device.setOsInfo(osInfo);
            device.setIpAddress(ipAddress);
            if (friendlyName != null && !friendlyName.trim().isEmpty()) {
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
            if (friendlyName != null && !friendlyName.trim().isEmpty()) {
                device.setFriendlyName(friendlyName.trim());
            }
        }

        return deviceRepo.save(device);
    }

    public void markDeviceOnline(String deviceId, String sessionId) {
        onlineDevices.put(deviceId, sessionId);
        sessionToDevice.put(sessionId, deviceId);

        // Update last seen in database
        deviceRepo.findById(deviceId).ifPresent(device -> {
            device.setLastSeen(System.currentTimeMillis() / 1000);
            deviceRepo.save(device);
        });
    }

    public void markDeviceOffline(String sessionId) {
        String deviceId = sessionToDevice.remove(sessionId);
        if (deviceId != null) {
            onlineDevices.remove(deviceId);
        }
    }

    public boolean isDeviceOnline(String deviceId) {
        return onlineDevices.containsKey(deviceId);
    }

    public Set<String> getOnlineDeviceIds() {
        return onlineDevices.keySet();
    }

    public String getDeviceIdForSession(String sessionId) {
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
    public boolean deleteDevice(String deviceId, String username) {
        int deleted = deviceRepo.deleteByIdAndUsername(deviceId, username);
        onlineDevices.remove(deviceId);
        return deleted > 0;
    }

    @Transactional
    public void deleteDevicesForUser(String username) {
        deviceRepo.deleteByUsername(username);
    }

    public long getOnlineDeviceCount() {
        return onlineDevices.size();
    }

    public long getTotalDeviceCount() {
        return deviceRepo.count();
    }
}
