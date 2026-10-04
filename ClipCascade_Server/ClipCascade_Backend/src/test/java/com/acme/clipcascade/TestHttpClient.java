package com.acme.clipcascade;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

import com.acme.clipcascade.service.UserService;

record TestHttpClient(String username, int port, HttpClient client, CookieManager cookies) {
    static TestHttpClient login(UserService users, int port, String role) throws Exception {
        String username = "http-test-" + UUID.randomUUID();
        String password = "Synthetic clipboard HTTP test password";
        users.doubleHashAndCreateUser(username, password, role, true);
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
        TestHttpClient browser = new TestHttpClient(username, port, client, cookies);
        var loginPage = browser.get("/login");
        assertThat(loginPage.statusCode()).isEqualTo(200);
        var token = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(loginPage.body());
        assertThat(token.find()).as("login includes the session-backed CSRF token").isTrue();
        String hashedPassword = HexFormat.of().formatHex(MessageDigest.getInstance("SHA3-512")
                .digest(password.getBytes(StandardCharsets.UTF_8)));
        String form = "username=" + encode(username) + "&password=" + encode(hashedPassword)
                + "&_csrf=" + encode(token.group(1));
        var response = client.send(HttpRequest.newBuilder(browser.url("/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location").orElseThrow()).doesNotContain("error");
        assertThat(browser.get("/whoami").body()).contains(username);
        return browser;
    }

    HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(url(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    URI url(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
