package com.acme.clipcascade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.acme.clipcascade.model.Device;
import com.acme.clipcascade.repo.DeviceRepo;
import com.acme.clipcascade.service.DeviceService;

class DeviceServiceTests {
    private final Map<String, Device> records = new HashMap<>();
    private DeviceRepo repository;
    private DeviceService devices;

    @BeforeEach
    void setUp() {
        repository = mock(DeviceRepo.class);
        when(repository.findById(anyString())).thenAnswer(call -> Optional.ofNullable(records.get(call.getArgument(0))));
        when(repository.saveAndFlush(any(Device.class))).thenAnswer(call -> {
            Device device = call.getArgument(0);
            records.put(device.getId(), device);
            return device;
        });
        devices = new DeviceService(repository);
    }

    @Test
    void closingAnOlderConnectionKeepsTheReconnectedDeviceOnline() {
        devices.markDeviceOnline("browser-id", "old-socket");
        devices.markDeviceOnline("browser-id", "new-socket");

        devices.markDeviceOffline("old-socket");
        devices.markDeviceOffline("old-socket"); // Spring can deliver disconnect more than once.

        assertThat(devices.isDeviceOnline("browser-id")).isTrue();
        assertThat(devices.getDeviceIdForSession("new-socket")).isEqualTo("browser-id");
        assertThat(devices.getOnlineDeviceCount()).isEqualTo(1);

        devices.markDeviceOffline("new-socket");
        assertThat(devices.isDeviceOnline("browser-id")).isFalse();
    }

    @Test
    void aQueuedRefreshCannotResurrectAClosedSocketOrDisplaceItsReplacement() {
        devices.markDeviceOnline("browser-id", "old-socket");
        devices.markDeviceOnline("browser-id", "new-socket");
        devices.markDeviceOffline("old-socket");

        assertThat(devices.refreshDeviceForSession("different-claimed-device", "old-socket")).isFalse();
        assertThat(devices.getDeviceIdForSession("old-socket")).isNull();
        assertThat(devices.isDeviceOnline("different-claimed-device")).isFalse();
        assertThat(devices.isDeviceOnline("browser-id")).isTrue();
        devices.markDeviceOffline("new-socket");
        assertThat(devices.refreshDeviceForSession("browser-id", "new-socket")).isFalse();
        assertThat(devices.getOnlineDeviceCount()).isZero();
    }

    @Test
    void remappingOneSessionDoesNotLeaveItsOldDeviceOnline() {
        devices.markDeviceOnline("old-id", "socket");
        devices.markDeviceOnline("new-id", "socket");

        assertThat(devices.isDeviceOnline("old-id")).isFalse();
        assertThat(devices.isDeviceOnline("new-id")).isTrue();
        devices.markDeviceOffline("socket");
        assertThat(devices.getOnlineDeviceCount()).isZero();
    }

    @Test
    void persistentClientIdsStayDistinctBehindTheSameAddress() {
        var first = devices.registerDevice("73004f53-631b-4126-b9c5-9013d9c72331", "owner", "web", "macOS", "192.0.2.10", "First");
        var second = devices.registerDevice("1d2349c8-bac2-4bcd-963c-ec4fbd3f0734", "owner", "web", "macOS", "192.0.2.10", "Second");

        assertThat(first.getId()).isNotEqualTo(second.getId());
        assertThat(devices.registerDevice("73004f53-631b-4126-b9c5-9013d9c72331", "owner", "web", "macOS", "192.0.2.10", null).getId())
                .isEqualTo(first.getId());
        assertThat(records).hasSize(2);
        verify(repository, never()).delete(any(Device.class));
    }

    @Test
    void aStockClientRetainsTheExistingLegacyIdAndName() {
        String legacyId = "dev-" + UUID.nameUUIDFromBytes("owner:192.0.2.10:desktop".getBytes(StandardCharsets.UTF_8));
        var legacy = new Device(legacyId, "owner", "Chi's Mac", "desktop", "macOS", "192.0.2.10");
        records.put(legacyId, legacy);

        var registered = devices.registerDevice(null, "owner", "desktop", "macOS", "192.0.2.10", null);

        assertThat(registered.getId()).isEqualTo(legacyId);
        assertThat(registered.getFriendlyName()).isEqualTo("Chi's Mac");
        assertThat(records).hasSize(1);
    }

    @Test
    void anExplicitClientIdDoesNotDeleteHistoricalDevicesSharingItsAddress() {
        var legacy = new Device("dev-old", "owner", "Original name", "web", "macOS", "192.0.2.10");
        records.put(legacy.getId(), legacy);

        devices.registerDevice("persistent-browser", "owner", "web", "macOS", "192.0.2.10", null);

        assertThat(records).containsKey("dev-old");
        assertThat(legacy.getFriendlyName()).isEqualTo("Original name");
        verify(repository, never()).delete(any(Device.class));
    }

    @Test
    void aClaimedIdCannotUpdateAnotherUsersDevice() {
        var original = devices.registerDevice("shared-client-id", "first-owner", "desktop", "Linux", "192.0.2.1", "Original");
        var other = devices.registerDevice("shared-client-id", "second-owner", "web", "macOS", "192.0.2.2", "Other");
        var reconnect = devices.registerDevice("shared-client-id", "second-owner", "web", "macOS", "192.0.2.2", null);

        assertThat(other.getId()).isNotEqualTo(original.getId());
        assertThat(reconnect.getId()).isEqualTo(other.getId());
        assertThat(original.getUsername()).isEqualTo("first-owner");
        assertThat(original.getFriendlyName()).isEqualTo("Original");
        assertThat(original.getIpAddress()).isEqualTo("192.0.2.1");
    }

    @Test
    void ownersChooseDifferentIdsEvenWhenNeitherRegistrationIsVisibleYet() {
        // Model the read-before-commit window: both registrations initially see
        // an empty database. Each must choose an owner-specific primary key.
        when(repository.findById(anyString())).thenReturn(Optional.empty());
        var first = devices.registerDevice("same-browser-uuid", "first-owner", "web", null, null, null);
        var second = devices.registerDevice("same-browser-uuid", "second-owner", "web", null, null, null);

        assertThat(first.getId()).isNotEqualTo(second.getId());
        assertThat(records).hasSize(2);
    }

    @Test
    void anExistingExplicitIdStillBelongsToItsOriginalOwner() {
        var existing = new Device("existing-browser-uuid", "owner");
        records.put(existing.getId(), existing);

        var registered = devices.registerDevice("existing-browser-uuid", "owner", "web", null, null, null);

        assertThat(registered.getId()).isEqualTo(existing.getId());
        assertThat(records).hasSize(1);
    }

    @Test
    void aRejectedDeleteCannotChangeSomeoneElsesPresence() {
        devices.markDeviceOnline("someone-elses-id", "socket");

        assertThat(devices.deleteDevice("someone-elses-id", "other-owner")).isFalse();
        assertThat(devices.isDeviceOnline("someone-elses-id")).isTrue();
    }

    @Test
    void generatedIdsAndOversizedMetadataRemainUsable() {
        assertThat(devices.registerDevice(null, "owner", "desktop", null, null, null).getId()).startsWith("dev-");
        var longMetadata = devices.registerDevice("x".repeat(1000), "owner", "t".repeat(100), "o".repeat(200), "i".repeat(200), "n".repeat(200));
        var reconnect = devices.registerDevice("x".repeat(1000), "owner", null, null, null, null);
        assertThat(longMetadata.getId().length()).isLessThanOrEqualTo(64);
        assertThat(reconnect.getId()).isEqualTo(longMetadata.getId());
        assertThat(longMetadata.getDeviceType()).hasSize(50);
        assertThat(longMetadata.getOsInfo()).hasSize(100);
        assertThat(longMetadata.getFriendlyName()).hasSize(100);
        assertThat(longMetadata.getIpAddress()).isNull();
    }

    @Test
    void reconnectMetadataPreservesANameEditedOnTheDashboard() {
        var device = devices.registerDevice("browser-uuid", "owner", "web", null, null, "Web Browser");
        device.setFriendlyName("Chi's Mac");

        var reconnect = devices.registerDevice("browser-uuid", "owner", "web", null, null, "Web Browser");

        assertThat(reconnect.getFriendlyName()).isEqualTo("Chi's Mac");
    }
}
