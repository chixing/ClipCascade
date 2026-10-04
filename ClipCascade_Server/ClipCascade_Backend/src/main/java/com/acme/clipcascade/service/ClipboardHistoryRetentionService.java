package com.acme.clipcascade.service;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.acme.clipcascade.repo.ClipboardHistoryRepo;

@Service
public class ClipboardHistoryRetentionService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClipboardHistoryRetentionService.class);
    private static final int BATCH_SIZE = 500;
    private static final int MAX_BATCHES = 20;

    private final ClipboardHistoryRepo history;
    private final int retentionDays;
    private final Clock clock;

    @Autowired
    public ClipboardHistoryRetentionService(ClipboardHistoryRepo history,
            @Value("${CC_HISTORY_RETENTION_DAYS:0}") int retentionDays) {
        this(history, retentionDays, Clock.systemUTC());
    }

    ClipboardHistoryRetentionService(ClipboardHistoryRepo history, int retentionDays, Clock clock) {
        if (retentionDays < 0) {
            throw new IllegalArgumentException("CC_HISTORY_RETENTION_DAYS must be zero (off) or positive");
        }
        this.history = history;
        this.retentionDays = retentionDays;
        this.clock = clock;
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    public boolean isEnabled() {
        return retentionDays > 0;
    }

    /** Defaults to preserving every entry. An opted-in policy never deletes pinned clips. */
    @Scheduled(fixedDelay = 24 * 60 * 60 * 1000L, initialDelay = 60 * 1000L)
    @Transactional
    public int cleanupExpiredHistory() {
        if (!isEnabled()) {
            return 0;
        }

        long before = clock.instant().getEpochSecond() - Duration.ofDays(retentionDays).getSeconds();
        int deleted = 0;
        // Bound each cleanup run and every DELETE, even after a large existing history is opted in.
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            List<Long> ids = history.findExpiredUnpinnedIds(before, PageRequest.of(0, BATCH_SIZE));
            if (ids.isEmpty()) {
                break;
            }
            // Check pinned and age again at deletion in case an entry was pinned after selection.
            deleted += history.deleteExpiredUnpinnedIds(ids, before);
        }
        if (deleted > 0) {
            LOGGER.info("Removed {} unpinned clipboard entries older than {} days", deleted, retentionDays);
        }
        return deleted;
    }
}
