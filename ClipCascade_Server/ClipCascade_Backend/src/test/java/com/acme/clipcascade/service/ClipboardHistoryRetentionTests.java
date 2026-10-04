package com.acme.clipcascade.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;

import com.acme.clipcascade.repo.ClipboardHistoryRepo;

class ClipboardHistoryRetentionTests {
    private final ClipboardHistoryRepo repository = mock(ClipboardHistoryRepo.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-04T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void disabledRetentionDoesNotReadOrDeleteHistory() {
        var retention = new ClipboardHistoryRetentionService(repository, 0, clock);

        assertThat(retention.isEnabled()).isFalse();
        assertThat(retention.cleanupExpiredHistory()).isZero();
        verifyNoInteractions(repository);
    }

    @Test
    void negativeRetentionIsRejectedInsteadOfDeletingRecentHistory() {
        assertThatThrownBy(() -> new ClipboardHistoryRetentionService(repository, -1, clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CC_HISTORY_RETENTION_DAYS");
        verifyNoInteractions(repository);
    }

    @Test
    void oneCleanupRunHasBoundedWorkEvenWithMoreExpiredRows() {
        List<Long> ids = LongStream.rangeClosed(1, 500).boxed().toList();
        when(repository.findExpiredUnpinnedIds(anyLong(), any())).thenReturn(ids);
        when(repository.deleteExpiredUnpinnedIds(any(), anyLong())).thenReturn(500);
        var retention = new ClipboardHistoryRetentionService(repository, 30, clock);

        assertThat(retention.cleanupExpiredHistory()).isEqualTo(10_000);
        verify(repository, times(20)).findExpiredUnpinnedIds(anyLong(), any());
        verify(repository, times(20)).deleteExpiredUnpinnedIds(any(), anyLong());
    }
}
