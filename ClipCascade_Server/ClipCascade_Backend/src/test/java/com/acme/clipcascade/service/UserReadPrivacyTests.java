package com.acme.clipcascade.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.security.MessageDigest;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.acme.clipcascade.model.Users;
import com.acme.clipcascade.repo.UserRepo;

class UserReadPrivacyTests {
    @Test
    void everyReadReturnsPasswordFreeCopiesWithoutMutatingRepositoryEntities() throws Exception {
        var repo = mock(UserRepo.class);
        var entity = new Users("alice", "synthetic-stored-hash", "USER", false);
        when(repo.findByRoleOrderByUsernameAsc("USER")).thenReturn(List.of(entity));
        when(repo.findByRole("USER")).thenReturn(List.of(entity));
        when(repo.findAll()).thenReturn(List.of(entity));
        var service = new UserService(repo, new BCryptPasswordEncoder(), MessageDigest.getInstance("SHA3-512"));
        assertCopy(service.getUsers("USER").getFirst(), entity);
        assertCopy(service.getAllUsers().getFirst(), entity);
        assertCopy(service.getUsersPage("USER", 0, 10, "username", "asc", "alice")
                .getContent().getFirst(), entity);
        assertThat(entity.getPassword()).isEqualTo("synthetic-stored-hash");
    }

    private void assertCopy(Users response, Users entity) {
        assertThat(response).isNotSameAs(entity);
        assertThat(response.getUsername()).isEqualTo(entity.getUsername());
        assertThat(response.getRole()).isEqualTo(entity.getRole());
        assertThat(response.getEnabled()).isEqualTo(entity.getEnabled());
        assertThat(response.getPassword()).isNull();
    }
}
