package com.acme.clipcascade.repo;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.acme.clipcascade.model.ClipboardHistory;

public interface ClipboardHistoryRepo extends JpaRepository<ClipboardHistory, Long> {

    Page<ClipboardHistory> findByUsernameOrderByCreatedAtDesc(String username, Pageable pageable);

    Page<ClipboardHistory> findByUsernameAndDeviceIdOrderByCreatedAtDesc(String username, String deviceId, Pageable pageable);

    Page<ClipboardHistory> findByUsernameAndPayloadTypeOrderByCreatedAtDesc(String username, String payloadType, Pageable pageable);

    Page<ClipboardHistory> findByUsernameAndDeviceIdAndPayloadTypeOrderByCreatedAtDesc(
            String username, String deviceId, String payloadType, Pageable pageable);

    @Query("SELECT h FROM ClipboardHistory h WHERE h.username = :username " +
           "AND (:deviceId IS NULL OR h.deviceId = :deviceId) " +
           "AND (:payloadType IS NULL OR h.payloadType = :payloadType) " +
           "AND (:fromTime IS NULL OR h.createdAt >= :fromTime) " +
           "AND (:toTime IS NULL OR h.createdAt <= :toTime) " +
           "AND (:search IS NULL OR CAST(h.payload AS string) LIKE CONCAT('%', :search, '%')) " +
           "AND (:pinned IS NULL OR h.pinned = :pinned) " +
           "ORDER BY h.createdAt DESC")
    Page<ClipboardHistory> searchHistory(
            @Param("username") String username,
            @Param("deviceId") String deviceId,
            @Param("payloadType") String payloadType,
            @Param("fromTime") Long fromTime,
            @Param("toTime") Long toTime,
            @Param("search") String search,
            @Param("pinned") Boolean pinned,
            Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ClipboardHistory h SET h.pinned = :pinned WHERE h.id = :id AND h.username = :username")
    int setPinnedByIdAndUsername(@Param("id") Long id, @Param("username") String username,
            @Param("pinned") boolean pinned);

    @Query("SELECT COUNT(h) FROM ClipboardHistory h WHERE h.username = :username")
    long countByUsername(@Param("username") String username);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM ClipboardHistory h WHERE h.id IN :ids AND h.username = :username")
    int deleteByIdsAndUsername(@Param("ids") List<Long> ids, @Param("username") String username);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM ClipboardHistory h WHERE h.username = :username AND h.createdAt < :before AND h.pinned = false")
    int deleteByUsernameAndCreatedAtBefore(@Param("username") String username, @Param("before") Long before);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM ClipboardHistory h WHERE h.username = :username AND h.deviceId = :deviceId AND h.pinned = false")
    int deleteByUsernameAndDeviceId(@Param("username") String username, @Param("deviceId") String deviceId);

    @Query("SELECT h.id FROM ClipboardHistory h WHERE h.pinned = false AND h.createdAt < :before " +
           "ORDER BY h.createdAt, h.id")
    List<Long> findExpiredUnpinnedIds(@Param("before") long before, Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM ClipboardHistory h WHERE h.id IN :ids AND h.pinned = false AND h.createdAt < :before")
    int deleteExpiredUnpinnedIds(@Param("ids") List<Long> ids, @Param("before") long before);

    void deleteByUsername(String username);

    List<ClipboardHistory> findTop10ByUsernameOrderByCreatedAtDesc(String username);
}
