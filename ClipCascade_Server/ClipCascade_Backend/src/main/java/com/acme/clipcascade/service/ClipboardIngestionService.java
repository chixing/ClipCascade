package com.acme.clipcascade.service;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Service;

import com.acme.clipcascade.config.WebSocketEventListener;
import com.acme.clipcascade.model.ClipboardData;
import com.acme.clipcascade.model.ClipboardHistory;
import com.acme.clipcascade.model.Device;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** The fork's history/device side effect, independent of the relay representation. */
@Service
public class ClipboardIngestionService {
    private static final String RECORDED_IDS = ClipboardIngestionService.class.getName() + ".recordedTransfers";
    private static final int MAX_RECENT_IDS = 256;
    private record TransferKey(String username, String sessionId, String transferId) {}
    private final DeviceService deviceService;
    private final ClipboardHistoryService clipboardHistoryService;
    private final ObjectMapper mapper;

    public ClipboardIngestionService(DeviceService deviceService,
            ClipboardHistoryService clipboardHistoryService, ObjectMapper mapper) {
        this.deviceService = deviceService;
        this.clipboardHistoryService = clipboardHistoryService;
        this.mapper = mapper;
    }

    public void recordLegacy(String username, ClipboardData data, SimpMessageHeaderAccessor source) {
        recordSafely(username, data, source, "legacy");
    }

    public void recordBinary(String username, byte[] frame, JsonNode envelope, SimpMessageHeaderAccessor source) {
        try {
            Map<String, Object> metadata = mapper.convertValue(envelope.path("metadata"),
                    new TypeReference<Map<String, Object>>() {});
            String type = envelope.path("type").asText();
            boolean encrypted = envelope.path("encrypted").asBoolean();
            // Encryption keys remain on clients. Preserve an encrypted frame exactly, including its format.
            String payload = encrypted ? Base64.getEncoder().encodeToString(frame)
                    : legacyPayload(frame, type);
            recordSafely(username, new ClipboardData(payload, type, metadata), source,
                    encrypted ? "ccb1" : "text".equals(type) ? "utf8" : "legacy");
        } catch (Exception failure) {
            historyFailure(failure);
        }
    }

    private void recordSafely(String username, ClipboardData data, SimpMessageHeaderAccessor source, String format) {
        try {
            Object id = data.getMetadata() == null ? null : data.getMetadata().get("historyTransferId");
            Map<String, Object> attributes = source == null ? null : source.getSessionAttributes();
            if (!(id instanceof String transferId) || !transferId.matches("[A-Za-z0-9_-]{1,128}")
                    || attributes == null) {
                record(username, data, source, format);
                return;
            }
            // Retain only row IDs, scoped to this owner and WebSocket session. A duplicate
            // may add explicit clear-text evidence, but must never replace the stored row.
            synchronized (attributes) {
                @SuppressWarnings("unchecked")
                LinkedHashMap<TransferKey, Long> recorded =
                        (LinkedHashMap<TransferKey, Long>) attributes.get(RECORDED_IDS);
                TransferKey key = new TransferKey(username, source.getSessionId(), transferId);
                if (recorded != null && recorded.containsKey(key)) {
                    Long historyId = recorded.get(key);
                    if (historyId != null && "utf8".equals(format) && "text".equals(data.getType())) {
                        clipboardHistoryService.upgradeLegacyText(historyId, username, data.getPayload());
                    }
                    return;
                }
                if (recorded == null) {
                    recorded = new LinkedHashMap<>();
                    try {
                        attributes.put(RECORDED_IDS, recorded);
                    } catch (UnsupportedOperationException immutableTestOrCustomSession) {
                        record(username, data, source, format);
                        return;
                    }
                }
                ClipboardHistory saved = record(username, data, source, format);
                recorded.put(key, saved == null ? null : saved.getId());
                while (recorded.size() > MAX_RECENT_IDS) recorded.remove(recorded.keySet().iterator().next());
            }
        } catch (Exception failure) {
            // History remains best-effort and must not interrupt clipboard delivery.
            historyFailure(failure);
        }
    }

    private void historyFailure(Exception failure) {
        // Do not log payloads, encryption envelopes, account names or database exception details.
        org.slf4j.LoggerFactory.getLogger(ClipboardIngestionService.class)
                .warn("Clipboard history recording failed ({})", failure.getClass().getSimpleName());
    }

    private ClipboardHistory record(String username, ClipboardData data, SimpMessageHeaderAccessor headerAccessor, String format) {
        String deviceId = null;
        String deviceType = null;
        String osInfo = null;
        Map<String, Object> metadata = data.getMetadata();

        String friendlyName = null;
        String ipAddress = null;

        // First try to get deviceId from metadata (sent by client)
        if (metadata != null && metadata.containsKey("deviceId")) {
            Object deviceIdObj = metadata.get("deviceId");
            deviceId = deviceIdObj != null ? deviceIdObj.toString() : null;
        }
        if (metadata != null && metadata.containsKey("deviceType")) {
            Object deviceTypeObj = metadata.get("deviceType");
            deviceType = deviceTypeObj != null ? deviceTypeObj.toString() : null;
        }
        if (metadata != null && metadata.containsKey("osInfo")) {
            Object osInfoObj = metadata.get("osInfo");
            osInfo = osInfoObj != null ? osInfoObj.toString() : null;
        }
        if (metadata != null && metadata.containsKey("friendlyName")) {
            Object fnObj = metadata.get("friendlyName");
            friendlyName = fnObj != null ? fnObj.toString() : null;
        }
        if (metadata != null && metadata.containsKey("ipAddress")) {
            Object ipObj = metadata.get("ipAddress");
            if (ipObj != null && !ipObj.toString().trim().isEmpty()) {
                ipAddress = ipObj.toString().trim();
            }
        }
        if (headerAccessor != null && headerAccessor.getSessionAttributes() != null) {
            if (ipAddress == null || ipAddress.isEmpty() || "0.0.0.0".equals(ipAddress) || "172.20.0.1".equals(ipAddress)) {
                String attrIp = (String) headerAccessor.getSessionAttributes().get("ipAddress");
                if (attrIp != null && !attrIp.isEmpty()) {
                    ipAddress = attrIp;
                }
            }
            if (deviceType == null) {
                deviceType = WebSocketEventListener.inferDeviceType((String) headerAccessor.getSessionAttributes().get("userAgent"), null);
            }
            if (osInfo == null) {
                osInfo = WebSocketEventListener.inferOsInfo((String) headerAccessor.getSessionAttributes().get("userAgent"), null);
            }
        }
        if (deviceType != null && deviceType.length() > 50) {
            deviceType = deviceType.substring(0, 50);
        }
        if (osInfo != null && osInfo.length() > 100) {
            osInfo = osInfo.substring(0, 100);
        }
        if (friendlyName != null && friendlyName.length() > 100) {
            friendlyName = friendlyName.substring(0, 100);
        }

        // If no deviceId in metadata, try to get it from the WebSocket session
        if (deviceId == null || deviceId.isEmpty()) {
            String sessionId = headerAccessor != null ? headerAccessor.getSessionId() : null;
            if (sessionId != null) {
                String mappedDeviceId = deviceService.getDeviceIdForSession(sessionId);
                deviceId = mappedDeviceId;
            }
        }

        String sessionId = headerAccessor != null ? headerAccessor.getSessionId() : null;
        Device registered = deviceService.registerDevice(deviceId, username, deviceType, osInfo, ipAddress, friendlyName);
        // Registration can resolve a legacy fallback or owner-scoped ID. Use
        // that same ID for both presence and history, never the claimed raw ID.
        deviceId = registered.getId();
        if (sessionId != null) {
            deviceService.refreshDeviceForSession(deviceId, sessionId);
        }

        if ("legacy".equals(format)) {
            return clipboardHistoryService.recordClipboard(username, deviceId, data);
        } else {
            return clipboardHistoryService.recordClipboard(username, deviceId, data, format);
        }
    }

    /** Decode only explicitly unencrypted content. Reuse the existing history preview/download format. */
    private String legacyPayload(byte[] outer, String type) throws Exception {
        int outerHeader = ByteBuffer.wrap(outer, 4, 4).getInt();
        byte[] content = Arrays.copyOfRange(outer, 8 + outerHeader, outer.length);
        if (content.length < 8 || ByteBuffer.wrap(content).getInt() != 0x43434231)
            throw new IllegalArgumentException("Invalid inner binary frame");
        int length = ByteBuffer.wrap(content, 4, 4).getInt();
        if (length < 0 || length > 65536 || length > content.length - 8)
            throw new IllegalArgumentException("Invalid inner binary header");
        JsonNode inner = mapper.readTree(content, 8, length);
        if (!inner.isObject() || !type.equals(inner.path("type").asText()))
            throw new IllegalArgumentException("Mismatched binary type");
        int start = 8 + length;
        if ("text".equals(type)) {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content, start, content.length - start)).toString();
        }
        if ("image".equals(type))
            return Base64.getEncoder().encodeToString(Arrays.copyOfRange(content, start, content.length));
        if (!"files".equals(type) || !inner.path("files").isArray())
            throw new IllegalArgumentException("Invalid binary files");
        Map<String, String> files = new LinkedHashMap<>();
        for (JsonNode entry : inner.path("files")) {
            if (!entry.isArray() || entry.size() != 2 || !entry.get(0).isTextual()
                    || !entry.get(1).isIntegralNumber() || !entry.get(1).canConvertToInt())
                throw new IllegalArgumentException("Invalid binary file entry");
            String name = entry.get(0).asText();
            int size = entry.get(1).intValue();
            if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.indexOf('\0') >= 0
                    || name.equals(".") || name.equals("..") || files.containsKey(name)
                    || size < 0 || size > content.length - start)
                throw new IllegalArgumentException("Invalid binary file bounds");
            files.put(name, Base64.getEncoder().encodeToString(Arrays.copyOfRange(content, start, start + size)));
            start += size;
        }
        if (start != content.length) throw new IllegalArgumentException("Trailing binary content");
        return mapper.writeValueAsString(files);
    }
}
