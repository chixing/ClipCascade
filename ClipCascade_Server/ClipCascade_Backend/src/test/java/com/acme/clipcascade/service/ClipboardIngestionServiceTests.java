package com.acme.clipcascade.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;

import com.acme.clipcascade.model.ClipboardData;
import com.acme.clipcascade.model.Device;
import com.fasterxml.jackson.databind.ObjectMapper;

class ClipboardIngestionServiceTests {
    private final DeviceService devices = mock(DeviceService.class);
    private final ClipboardHistoryService history = mock(ClipboardHistoryService.class);
    private final ObjectMapper json = new ObjectMapper();
    private final ClipboardIngestionService ingestion = new ClipboardIngestionService(devices, history, json);

    ClipboardIngestionServiceTests() {
        when(devices.registerDevice(any(), any(), any(), any(), any(), any()))
                .thenReturn(new Device("owner-scoped-device", "owner"));
    }

    @Test
    void failedHistoryCanRetryAndCannotInterruptRelayCaller() {
        var headers = source();
        var data = new ClipboardData("synthetic", "text", Map.of("historyTransferId", "transfer-1"));
        when(history.recordClipboard(anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("synthetic unavailable store")).thenReturn(null);
        ingestion.recordLegacy("owner", data, headers);
        ingestion.recordLegacy("owner", data, headers);
        ingestion.recordLegacy("owner", data, headers);
        verify(history, times(2)).recordClipboard("owner", "owner-scoped-device", data);
    }

    @Test
    void transferCacheIsBoundedAndDoesNotCoalesceOldClientsWithoutIds() {
        var headers = source();
        for (int index = 0; index < 300; index++) {
            ingestion.recordLegacy("owner", new ClipboardData("synthetic", "text",
                    Map.of("historyTransferId", "transfer-" + index)), headers);
        }
        assertThat(headers.getSessionAttributes().values().stream().filter(Set.class::isInstance)
                .map(Set.class::cast).mapToInt(Set::size).sum()).isEqualTo(256);
        var oldClient = new ClipboardData("repeated copy", "text", null);
        ingestion.recordLegacy("owner", oldClient, headers);
        ingestion.recordLegacy("owner", oldClient, headers);
        verify(history, times(2)).recordClipboard("owner", "owner-scoped-device", oldClient);
    }

    @Test
    void invalidClearBinaryCannotBecomeMisleadingHistory() throws Exception {
        var envelope = json.valueToTree(Map.of("type", "files", "encrypted", false,
                "metadata", Map.of("contentSizeBytes", 3)));
        byte[] invalidInnerHeader = json.writeValueAsBytes(Map.of("type", "files", "files",
                new Object[] { new Object[] { "../escape", 3 } }));
        byte[] inner = ByteBuffer.allocate(8 + invalidInnerHeader.length + 3).putInt(0x43434231)
                .putInt(invalidInnerHeader.length).put(invalidInnerHeader).put(new byte[3]).array();
        byte[] outerHeader = json.writeValueAsBytes(envelope);
        byte[] outer = ByteBuffer.allocate(8 + outerHeader.length + inner.length).putInt(0x43434231)
                .putInt(outerHeader.length).put(outerHeader).put(inner).array();
        ingestion.recordBinary("owner", outer, envelope, source());
        verifyNoInteractions(history);
    }

    @Test
    void validDeviceMetadataUsesOwnerScopedIdForBothFormats() throws Exception {
        var envelope = json.valueToTree(Map.of("type", "text", "encrypted", true,
                "metadata", Map.of("contentSizeBytes", 3, "deviceId", "claimed-id")));
        ingestion.recordBinary("owner", new byte[] {1, 2, 3}, envelope, source());
        verify(history).recordClipboard(eq("owner"), eq("owner-scoped-device"), any(), eq("ccb1"));
    }

    private SimpMessageHeaderAccessor source() {
        var headers = SimpMessageHeaderAccessor.create();
        headers.setSessionId("synthetic-session");
        headers.setSessionAttributes(new HashMap<>());
        return headers;
    }
}
