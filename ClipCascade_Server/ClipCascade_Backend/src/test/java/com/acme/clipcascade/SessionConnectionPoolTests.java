package com.acme.clipcascade;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.acme.clipcascade.constants.RoleConstants;
import com.acme.clipcascade.service.UserService;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:session-pool;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.connection-timeout=2000",
        "spring.jpa.open-in-view=true"
})
@Import(SessionConnectionPoolTests.ProbeConfiguration.class)
class SessionConnectionPoolTests {
    @LocalServerPort int port;
    @Autowired UserService users;

    @Test
    void sessionWritesReuseOpenEntityManagerConnectionsUnderConcurrency() throws Exception {
        var login = TestHttpClient.login(users, port, RoleConstants.ADMIN);
        var otherLogin = TestHttpClient.login(users, port, RoleConstants.ADMIN);
        long start = System.nanoTime();
        try (var executor = Executors.newFixedThreadPool(2)) {
            // Each pair holds both pool connections through open-in-view before
            // response commit saves its session. A second connection per save
            // deterministically exhausts this pool instead of relying on timing.
            for (int pair = 0; pair < 5; pair++) {
                var first = executor.submit(() -> login.get("/test/session-pool"));
                var second = executor.submit(() -> otherLogin.get("/test/session-pool"));
                assertThat(first.get(8, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
                assertThat(second.get(8, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            }
        }
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(8));
    }

    @TestConfiguration
    static class ProbeConfiguration {
        @Bean PoolProbe poolProbe() { return new PoolProbe(); }
    }

    @RestController
    static class PoolProbe {
        @PersistenceContext EntityManager entityManager;
        private final CyclicBarrier bothConnectionsHeld = new CyclicBarrier(2);

        @GetMapping("/test/session-pool")
        Map<String, Boolean> get(HttpServletRequest request) throws Exception {
            entityManager.createNativeQuery("SELECT COUNT(*) FROM USERS").getSingleResult();
            request.getSession().setAttribute("pool-probe", System.nanoTime());
            bothConnectionsHeld.await(5, TimeUnit.SECONDS);
            return Map.of("ok", true);
        }
    }
}
