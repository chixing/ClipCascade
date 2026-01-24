package com.acme.clipcascade.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.acme.clipcascade.model.ClipboardData;
import com.acme.clipcascade.model.ClipboardHistory;
import com.acme.clipcascade.model.Device;
import com.acme.clipcascade.repo.ClipboardHistoryRepo;
import com.acme.clipcascade.repo.DeviceRepo;

@Service
public class ClipboardHistoryService {

    private final ClipboardHistoryRepo clipboardHistoryRepo;
    private final DeviceRepo deviceRepo;

    public ClipboardHistoryService(ClipboardHistoryRepo clipboardHistoryRepo, DeviceRepo deviceRepo) {
        this.clipboardHistoryRepo = clipboardHistoryRepo;
        this.deviceRepo = deviceRepo;
    }

    @Transactional
    public ClipboardHistory recordClipboard(String username, String deviceId, ClipboardData clipboardData) {
        String payloadType = clipboardData.getType() != null ? clipboardData.getType() : "text";
        String payload = clipboardData.getPayload();

        ClipboardHistory history = new ClipboardHistory(username, deviceId, payloadType, payload);

        return clipboardHistoryRepo.save(history);
    }

    public Page<ClipboardHistory> searchHistory(
            String username,
            String deviceId,
            String payloadType,
            Long fromTime,
            Long toTime,
            String search,
            int page,
            int size) {

        Pageable pageable = PageRequest.of(page, size);

        Page<ClipboardHistory> results = clipboardHistoryRepo.searchHistory(
                username,
                deviceId,
                payloadType,
                fromTime,
                toTime,
                search,
                pageable);

        // Enrich with device names
        enrichWithDeviceNames(results.getContent());

        return results;
    }

    public ClipboardHistory getHistoryItem(Long id, String username) {
        return clipboardHistoryRepo.findById(id)
                .filter(h -> h.getUsername().equals(username))
                .map(h -> {
                    enrichWithDeviceNames(List.of(h));
                    return h;
                })
                .orElse(null);
    }

    public List<ClipboardHistory> getRecentHistory(String username) {
        List<ClipboardHistory> history = clipboardHistoryRepo.findTop10ByUsernameOrderByCreatedAtDesc(username);
        enrichWithDeviceNames(history);
        return history;
    }

    @Transactional
    public int deleteByIds(List<Long> ids, String username) {
        return clipboardHistoryRepo.deleteByIdsAndUsername(ids, username);
    }

    @Transactional
    public int deleteOlderThan(String username, Long beforeTimestamp) {
        return clipboardHistoryRepo.deleteByUsernameAndCreatedAtBefore(username, beforeTimestamp);
    }

    @Transactional
    public int deleteByDevice(String username, String deviceId) {
        return clipboardHistoryRepo.deleteByUsernameAndDeviceId(username, deviceId);
    }

    @Transactional
    public void deleteAllForUser(String username) {
        clipboardHistoryRepo.deleteByUsername(username);
    }

    public long countForUser(String username) {
        return clipboardHistoryRepo.countByUsername(username);
    }

    private void enrichWithDeviceNames(List<ClipboardHistory> historyList) {
        // Collect unique device IDs
        List<String> deviceIds = historyList.stream()
                .map(ClipboardHistory::getDeviceId)
                .filter(id -> id != null && !id.isEmpty())
                .distinct()
                .collect(Collectors.toList());

        // Fetch devices and create a map
        Map<String, String> deviceNameMap = new java.util.HashMap<>();
        if (!deviceIds.isEmpty()) {
            List<Device> devices = deviceRepo.findAllById(deviceIds);
            for (Device device : devices) {
                deviceNameMap.put(device.getId(), device.getDisplayName());
            }
        }

        // Enrich history items
        for (ClipboardHistory history : historyList) {
            String deviceId = history.getDeviceId();
            if (deviceId != null && !deviceId.isEmpty()) {
                String name = deviceNameMap.get(deviceId);
                if (name != null) {
                    history.setDeviceName(name);
                } else {
                    // Device not in DB, use truncated ID
                    history.setDeviceName(deviceId.substring(0, Math.min(8, deviceId.length())));
                }
            } else {
                history.setDeviceName("Web Client");
            }
        }
    }
}
