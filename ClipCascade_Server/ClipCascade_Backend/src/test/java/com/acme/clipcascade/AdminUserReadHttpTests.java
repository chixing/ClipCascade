package com.acme.clipcascade;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.acme.clipcascade.constants.RoleConstants;
import com.acme.clipcascade.service.UserInfoService;
import com.acme.clipcascade.service.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:clipcascade-admin-read-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.open-in-view=true"
})
class AdminUserReadHttpTests {
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate database;
    @Autowired private UserService users;
    @Autowired private UserInfoService userInfo;
    @Autowired private ObjectMapper json;

    @Test
    void adminReadsKeepHashesIntactAndPersistAuthenticatedSessionsWithoutExposingPasswords() throws Exception {
        var admin = TestHttpClient.login(users, port, RoleConstants.ADMIN);
        var member = TestHttpClient.login(users, port, RoleConstants.USER);
        userInfo.registerNewUser(admin.username());
        userInfo.registerNewUser(member.username());
        Map<String, String> before = hashes();
        assertThat(before.get(admin.username())).isNotBlank();
        assertThat(before.get(member.username())).isNotBlank();

        for (String path : new String[] { "/admin/users", "/admin/all-users",
                "/admin/users/page?size=100", "/admin/user-details/page?size=100" }) {
            var response = admin.get(path);
            assertThat(response.statusCode()).as(path).isEqualTo(200);
            assertThat(response.body()).contains(member.username());
            assertNoPassword(json.readTree(response.body()));
            for (String hash : before.values()) {
                assertThat(response.body()).doesNotContain(hash);
            }
            // HTTP completion includes the session filter's JDBC save, unlike MockMvc.
            assertThat(hashes()).as("hashes after " + path).isEqualTo(before);
            assertThat(database.queryForObject("""
                    SELECT COUNT(*) FROM SPRING_SESSION_ATTRIBUTES a
                    JOIN SPRING_SESSION s ON s.PRIMARY_ID = a.SESSION_PRIMARY_ID
                    WHERE s.PRINCIPAL_NAME = ? AND a.ATTRIBUTE_NAME = 'SPRING_SECURITY_CONTEXT'
                    """, Long.class, admin.username())).isEqualTo(1L);
        }
        // A fresh client restores the persisted authentication solely from the cookie.
        var cookie = admin.cookies().getCookieStore().getCookies().stream()
                .filter(c -> "JSESSIONID".equals(c.getName())).findFirst().orElseThrow();
        var restored = HttpClient.newHttpClient().send(HttpRequest.newBuilder(admin.url("/whoami"))
                .header("Cookie", "JSESSIONID=" + cookie.getValue()).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(restored.statusCode()).isEqualTo(200);
        assertThat(restored.body()).contains(admin.username());
    }

    private Map<String, String> hashes() {
        return database.query("SELECT username, password FROM users",
                (row, index) -> Map.entry(row.getString(1), row.getString(2)))
                .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private void assertNoPassword(JsonNode node) {
        if (node.isObject()) {
            node.fields().forEachRemaining(field -> {
                if ("password".equals(field.getKey())) {
                    assertThat(field.getValue().isNull()).isTrue();
                }
                assertNoPassword(field.getValue());
            });
        } else if (node.isArray()) {
            node.forEach(this::assertNoPassword);
        }
    }
}
