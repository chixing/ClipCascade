package com.acme.clipcascade.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.acme.clipcascade.model.Device;

public interface DeviceRepo extends JpaRepository<Device, String> {

    List<Device> findByUsername(String username);

    List<Device> findByUsernameOrderByLastSeenDesc(String username);

    Device findByIdAndUsername(String id, String username);

    @Modifying
    @Query("UPDATE Device d SET d.lastSeen = :lastSeen WHERE d.id = :id")
    void updateLastSeen(@Param("id") String id, @Param("lastSeen") Long lastSeen);

    @Modifying
    @Query("UPDATE Device d SET d.friendlyName = :friendlyName WHERE d.id = :id AND d.username = :username")
    int updateFriendlyName(@Param("id") String id, @Param("username") String username, @Param("friendlyName") String friendlyName);

    void deleteByUsername(String username);
}
