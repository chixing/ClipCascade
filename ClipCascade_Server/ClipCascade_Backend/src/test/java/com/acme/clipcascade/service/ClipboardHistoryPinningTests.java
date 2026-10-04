package com.acme.clipcascade.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.acme.clipcascade.model.ClipboardHistory;
import com.acme.clipcascade.repo.ClipboardHistoryRepo;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Transactional
class ClipboardHistoryPinningTests {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-04T12:00:00Z"), ZoneOffset.UTC);
    private static final long CUTOFF = CLOCK.instant().getEpochSecond() - Duration.ofDays(30).getSeconds();

    @Autowired
    private ClipboardHistoryRepo repository;

    @Autowired
    private ClipboardHistoryService history;

    @Autowired
    private ClipboardHistoryRetentionService defaultRetention;

    @Test
    void defaultPolicyPreservesExistingHistory() {
        var old = entry("pin-owner", "device", CUTOFF - 1, false);

        assertThat(defaultRetention.getRetentionDays()).isZero();
        assertThat(defaultRetention.cleanupExpiredHistory()).isZero();
        assertThat(repository.findById(old.getId())).isPresent();
    }

    @Test
    void onlyTheOwnerCanPinAndUnpinAnEntry() {
        var own = entry("pin-owner", "device", CUTOFF - 1, false);

        assertThat(history.setPinned(own.getId(), "other-user", true)).isFalse();
        assertThat(history.getHistoryItem(own.getId(), "pin-owner").isPinned()).isFalse();
        assertThat(history.setPinned(own.getId(), "pin-owner", true)).isTrue();
        assertThat(history.getHistoryItem(own.getId(), "pin-owner").isPinned()).isTrue();
        assertThat(history.setPinned(own.getId(), "pin-owner", false)).isTrue();
        assertThat(history.getHistoryItem(own.getId(), "pin-owner").isPinned()).isFalse();
    }

    @Test
    void enabledRetentionRemovesOnlyExpiredUnpinnedEntries() {
        var expired = entry("pin-owner", "device", CUTOFF - 1, false);
        var pinned = entry("pin-owner", "device", CUTOFF - 10, true);
        var boundary = entry("pin-owner", "device", CUTOFF, false);
        var recent = entry("other-user", "device", CUTOFF + 1, false);
        var retention = new ClipboardHistoryRetentionService(repository, 30, CLOCK);

        assertThat(retention.cleanupExpiredHistory()).isEqualTo(1);
        assertThat(repository.findById(expired.getId())).isEmpty();
        assertThat(repository.findAllById(List.of(pinned.getId(), boundary.getId(), recent.getId())))
                .hasSize(3);
    }

    @Test
    void cleanupRechecksPinsAfterSelectingCandidates() {
        var expired = entry("pin-owner", "device", CUTOFF - 1, false);
        var candidates = repository.findExpiredUnpinnedIds(CUTOFF, PageRequest.of(0, 500));

        assertThat(candidates).contains(expired.getId());
        assertThat(history.setPinned(expired.getId(), "pin-owner", true)).isTrue();
        assertThat(repository.deleteExpiredUnpinnedIds(candidates, CUTOFF)).isZero();
        assertThat(repository.findById(expired.getId())).isPresent();
    }

    @Test
    void bulkAgeAndDeviceCleanupPreservePinnedEntriesAndOtherOwners() {
        var old = entry("pin-owner", "first-device", CUTOFF - 1, false);
        var pin = entry("pin-owner", "second-device", CUTOFF - 10, true);
        var recent = entry("pin-owner", "second-device", CUTOFF + 1, false);
        var other = entry("other-user", "second-device", CUTOFF - 1, false);

        assertThat(history.deleteOlderThan("pin-owner", CUTOFF)).isEqualTo(1);
        assertThat(repository.findById(old.getId())).isEmpty();
        assertThat(history.deleteByDevice("pin-owner", "second-device")).isEqualTo(1);
        assertThat(repository.findById(recent.getId())).isEmpty();
        assertThat(repository.findAllById(List.of(pin.getId(), other.getId()))).hasSize(2);
    }

    @Test
    void explicitDeletionCanRemoveOwnedPinsWithoutRemovingSomeoneElses() {
        var own = entry("pin-owner", "device", CUTOFF - 1, true);
        var other = entry("other-user", "device", CUTOFF - 1, true);

        assertThat(history.deleteByIds(List.of(own.getId(), other.getId()), "pin-owner")).isEqualTo(1);
        assertThat(repository.findById(own.getId())).isEmpty();
        assertThat(repository.findById(other.getId())).isPresent();
    }

    @Test
    void pinnedFilterIsOwnerScopedAndOptional() {
        var pin = entry("pin-owner", "device", CUTOFF, true);
        var unpinned = entry("pin-owner", "device", CUTOFF + 1, false);
        entry("other-user", "device", CUTOFF, true);

        assertThat(history.searchHistory("pin-owner", null, null, null, null, null, true, 0, 10)
                .getContent()).extracting(ClipboardHistory::getId).containsExactly(pin.getId());
        assertThat(history.searchHistory("pin-owner", null, null, null, null, null, false, 0, 10)
                .getContent()).extracting(ClipboardHistory::getId).containsExactly(unpinned.getId());
        assertThat(history.searchHistory("pin-owner", null, null, null, null, null, 0, 10)
                .getTotalElements()).isEqualTo(2);
    }

    private ClipboardHistory entry(String username, String device, long timestamp, boolean pinned) {
        var entry = new ClipboardHistory(username, device, "text", "synthetic clipboard");
        entry.setCreatedAt(timestamp);
        entry.setPinned(pinned);
        return repository.saveAndFlush(entry);
    }
}
