package com.acme.clipcascade;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.acme.clipcascade.model.Device;
import com.acme.clipcascade.repo.DeviceRepo;
import com.acme.clipcascade.service.DeviceService;

@ActiveProfiles("test")
@DataJpaTest(showSql = false)
@Import(DeviceService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DeviceRegistrationTests {
    @Autowired
    private DeviceService devices;
    @Autowired
    private DeviceRepo repository;

    @Test
    void simultaneousFirstConnectionsShareOneCommittedDeviceAndRemainOnline() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Future<Device>> registrations = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                String session = "parallel-socket-" + index;
                registrations.add(pool.submit(() -> {
                    start.await();
                    var device = devices.registerDevice("parallel-browser-uuid", "parallel-owner", "web", "macOS", "192.0.2.10", null);
                    devices.markDeviceOnline(device.getId(), session);
                    return device;
                }));
            }
            start.countDown();
            List<Device> registered = new ArrayList<>();
            for (var result : registrations) {
                registered.add(result.get(15, TimeUnit.SECONDS));
            }
            String id = registered.getFirst().getId();
            assertThat(registered).extracting(Device::getId).containsOnly(id);
            assertThat(repository.findByUsername("parallel-owner")).hasSize(1);
            for (int index = 0; index < 7; index++) {
                devices.markDeviceOffline("parallel-socket-" + index);
            }
            assertThat(devices.isDeviceOnline(id)).isTrue();
            devices.markDeviceOffline("parallel-socket-7");
            assertThat(devices.isDeviceOnline(id)).isFalse();
        } finally {
            devices.deleteDevicesForUser("parallel-owner");
        }
    }

    @Test
    void simultaneousOwnersCannotTakeOverTheSameClaimedClientId() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Device> first = () -> {
                start.await();
                return devices.registerDevice("owner-collision-uuid", "collision-owner-a", "web", "Linux", null, "Original");
            };
            Callable<Device> second = () -> {
                start.await();
                return devices.registerDevice("owner-collision-uuid", "collision-owner-b", "web", "macOS", null, "Other");
            };
            var firstResult = pool.submit(first);
            var secondResult = pool.submit(second);
            start.countDown();
            Device firstDevice = firstResult.get(15, TimeUnit.SECONDS);
            Device secondDevice = secondResult.get(15, TimeUnit.SECONDS);

            assertThat(firstDevice.getId()).isNotEqualTo(secondDevice.getId());
            assertThat(repository.findById(firstDevice.getId()).orElseThrow().getUsername()).isEqualTo("collision-owner-a");
            assertThat(repository.findById(secondDevice.getId()).orElseThrow().getUsername()).isEqualTo("collision-owner-b");
        } finally {
            devices.deleteDevicesForUser("collision-owner-a");
            devices.deleteDevicesForUser("collision-owner-b");
        }
    }
}
