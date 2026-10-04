package com.acme.clipcascade;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.acme.clipcascade.model.ClipboardData;
import com.acme.clipcascade.service.ClipboardHistoryService;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClipCascadeApplicationTests {
    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate database;

    @Autowired
    private ClipboardHistoryService history;

    @Test
    void startsWithOneBootstrapAdmin() {
        assertThat(database.queryForObject(
                "SELECT COUNT(*) FROM USERS WHERE ROLE = 'ADMIN'", Long.class)).isEqualTo(1L);
    }

    @Test
    void servesHealthAndLoginPages() {
        assertThat(http.getForEntity("/health", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        var login = http.getForEntity("/login", String.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login.getBody()).contains("name=\"username\"", "name=\"_csrf\"");
    }

    @Test
    @Transactional
    void storesClipboardHistoryAndRestrictsItToItsOwner() {
        var entry = history.recordClipboard("test-owner", "test-device",
                new ClipboardData("synthetic test clipboard", "text", null));

        assertThat(entry.getId()).isNotNull();
        assertThat(history.getHistoryItem(entry.getId(), "test-owner").getPayload())
                .isEqualTo("synthetic test clipboard");
        assertThat(history.getHistoryItem(entry.getId(), "another-user")).isNull();
    }
}
