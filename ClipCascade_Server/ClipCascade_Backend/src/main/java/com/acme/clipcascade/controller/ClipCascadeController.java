package com.acme.clipcascade.controller;

import java.io.IOException;
import java.security.Principal;
import java.util.Collections;
import java.util.List;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.net.URLConnection;

import org.springframework.data.domain.Page;

import javax.imageio.ImageIO;

import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;

import com.acme.clipcascade.config.ClipCascadeProperties;
import com.acme.clipcascade.config.WebSocketEventListener;
import com.acme.clipcascade.constants.RoleConstants;
import com.acme.clipcascade.constants.ServerConstants;
import com.acme.clipcascade.model.ClipboardData;
import com.acme.clipcascade.model.ClipboardHistory;
import com.acme.clipcascade.model.Device;
import com.acme.clipcascade.model.UserPrincipal;
import com.acme.clipcascade.model.Users;
import com.acme.clipcascade.service.BruteForceProtectionService;
import com.acme.clipcascade.service.CaptchaService;
import com.acme.clipcascade.service.ClipboardHistoryService;
import com.acme.clipcascade.service.DeviceService;
import com.acme.clipcascade.service.DonationService;
import com.acme.clipcascade.service.FacadeUserService;
import com.acme.clipcascade.service.SessionService;
import com.acme.clipcascade.service.UserInfoService;
import com.acme.clipcascade.service.UserService;
import com.acme.clipcascade.service.WebSocketStatsService;
import com.acme.clipcascade.utils.ResponseEntityUtil;
import com.acme.clipcascade.utils.TimeUtility;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.transaction.Transactional;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class ClipCascadeController {

    private final ClipCascadeProperties clipCascadeProperties;
    private final UserService userService;
    private final UserInfoService userInfoService;
    private final SessionService sessionService;
    private final FacadeUserService facadeUserService;
    private final SimpMessagingTemplate simpMessagingTemplate;
    private final CaptchaService captchaService;
    private final BruteForceProtectionService bruteForceProtectionService;
    private final WebSocketStatsService webSocketStatsService;
    private final DonationService donationService;
    private final DeviceService deviceService;
    private final ClipboardHistoryService clipboardHistoryService;

    public ClipCascadeController(
            ClipCascadeProperties clipCascadeProperties,
            FacadeUserService facadeUserService,
            UserService userService,
            @Nullable SimpMessagingTemplate simpMessagingTemplate,
            CaptchaService captchaService,
            UserInfoService userInfoService,
            SessionService sessionService,
            BruteForceProtectionService bruteForceProtectionService,
            WebSocketStatsService webSocketStatsService,
            DonationService donationService,
            DeviceService deviceService,
            ClipboardHistoryService clipboardHistoryService) {

        this.clipCascadeProperties = clipCascadeProperties;
        this.facadeUserService = facadeUserService;
        this.userService = userService;
        this.simpMessagingTemplate = simpMessagingTemplate;
        this.captchaService = captchaService;
        this.userInfoService = userInfoService;
        this.sessionService = sessionService;
        this.bruteForceProtectionService = bruteForceProtectionService;
        this.webSocketStatsService = webSocketStatsService;
        this.donationService = donationService;
        this.deviceService = deviceService;
        this.clipboardHistoryService = clipboardHistoryService;
    }

    @PostConstruct
    @Transactional
    public void initializeDatabase() {
        // insert default admin user if table is empty
        facadeUserService.insertDefaultAdminUserIfEmpty();

        // make sure only one admin user exists
        Users adminUser = userService.verifyAdminExistence();

        // delete inactive users except admin
        facadeUserService.deleteInactiveUsers(sessionService, Collections.singleton(adminUser));

        // delete users which are marked for deletion
        userInfoService.purgeDeletedUsers();

        // initialize donation url
        donationService.initializeDonationUrl();
    }

    @GetMapping("/login")
    public String login(Model model) {
        model.addAttribute("signupEnabled", clipCascadeProperties.isSignupEnabled());
        model.addAttribute("donationsEnabled", clipCascadeProperties.isDonationsEnabled());
        return "login"; // login.html
    }

    @GetMapping("/signup")
    public String signup() {
        if (clipCascadeProperties.isSignupEnabled()) {
            return "signup"; // signup.html
        } else {
            return "redirect:/"; // Redirect to the home page
        }
    }

    @GetMapping("/help")
    public String help() {
        return "redirect:" + ServerConstants.HELP_URL;
    }

    @GetMapping("/donate")
    public String donate() {
        if (clipCascadeProperties.isDonationsEnabled()) {
            return "redirect:" + donationService.getDonationUrl();
        } else {
            return "redirect:/";
        }
    }

    @GetMapping("/logout")
    public String logout() {
        return "logout"; // logout.html
    }

    @GetMapping("/admin/advance")
    public String advance(
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            Model model) {

        if (userPrincipal.isAdmin()) {
            model.addAttribute("envVariables", clipCascadeProperties);
            return "advance"; // advance.html
        } else {
            return "redirect:/"; // Redirect to the home page
        }
    }

    /**
     * Handles private messages sent to specific users over WebSocket.
     *
     * Clients send messages to "/app/cliptext" (application-level destination).
     * The server sends responses to "/user/{sessionId}/queue/cliptext"
     * (user-specific queue).
     */
    @MessageMapping("/cliptext")
    public void sendPrivateMessage(
            Principal principal,
            ClipboardData clipboardData,
            org.springframework.messaging.simp.SimpMessageHeaderAccessor headerAccessor) {

        if (clipCascadeProperties.isP2pEnabled()) {
            return;
        }

        // Extract the custom UserPrincipal object from the Principal
        UserPrincipal userPrincipal = (UserPrincipal) ((UsernamePasswordAuthenticationToken) principal)
                .getPrincipal();

        // Prepare a message object with the received payload, type and metadata
        // Default type is "text" if none is specified
        ClipboardData messageToSend = new ClipboardData(
                clipboardData.getPayload(),
                (clipboardData.getType() == null) ? "text" : clipboardData.getType(),
                clipboardData.getMetadata());

        // Record clipboard history
        try {
            String deviceId = null;
            String deviceType = null;
            String osInfo = null;
            Map<String, Object> metadata = clipboardData.getMetadata();

            String friendlyName = null;
            String ipAddress = null;

            // First try to get deviceId from metadata (sent by client)
            if (metadata != null && metadata.containsKey("deviceId")) {
                Object deviceIdObj = metadata.get("deviceId");
                deviceId = deviceIdObj != null ? deviceIdObj.toString() : null;
            }
            if (metadata != null && metadata.containsKey("deviceType")) {
                Object deviceTypeObj = metadata.get("deviceType");
                deviceType = deviceTypeObj != null ? deviceTypeObj.toString() : null;
            }
            if (metadata != null && metadata.containsKey("osInfo")) {
                Object osInfoObj = metadata.get("osInfo");
                osInfo = osInfoObj != null ? osInfoObj.toString() : null;
            }
            if (metadata != null && metadata.containsKey("friendlyName")) {
                Object fnObj = metadata.get("friendlyName");
                friendlyName = fnObj != null ? fnObj.toString() : null;
            }
            if (metadata != null && metadata.containsKey("ipAddress")) {
                Object ipObj = metadata.get("ipAddress");
                if (ipObj != null && !ipObj.toString().trim().isEmpty()) {
                    ipAddress = ipObj.toString().trim();
                }
            }
            if (headerAccessor != null && headerAccessor.getSessionAttributes() != null) {
                if (ipAddress == null || ipAddress.isEmpty() || "0.0.0.0".equals(ipAddress) || "172.20.0.1".equals(ipAddress)) {
                    String attrIp = (String) headerAccessor.getSessionAttributes().get("ipAddress");
                    if (attrIp != null && !attrIp.isEmpty()) {
                        ipAddress = attrIp;
                    }
                }
                if (deviceType == null) {
                    deviceType = WebSocketEventListener.inferDeviceType((String) headerAccessor.getSessionAttributes().get("userAgent"), null);
                }
                if (osInfo == null) {
                    osInfo = WebSocketEventListener.inferOsInfo((String) headerAccessor.getSessionAttributes().get("userAgent"), null);
                }
            }
            if (deviceType != null && deviceType.length() > 50) {
                deviceType = deviceType.substring(0, 50);
            }
            if (osInfo != null && osInfo.length() > 100) {
                osInfo = osInfo.substring(0, 100);
            }
            if (friendlyName != null && friendlyName.length() > 100) {
                friendlyName = friendlyName.substring(0, 100);
            }

            // If no deviceId in metadata, try to get it from the WebSocket session
            if (deviceId == null || deviceId.isEmpty()) {
                String sessionId = headerAccessor != null ? headerAccessor.getSessionId() : null;
                if (sessionId != null) {
                    String mappedDeviceId = deviceService.getDeviceIdForSession(sessionId);
                    deviceId = (mappedDeviceId != null && !mappedDeviceId.isEmpty()) ? mappedDeviceId : sessionId;
                }
            }

            String sessionId = headerAccessor != null ? headerAccessor.getSessionId() : null;
            if (deviceId != null && !deviceId.isEmpty()) {
                deviceService.registerDevice(deviceId, userPrincipal.getUsername(), deviceType, osInfo, ipAddress, friendlyName);
                if (sessionId != null) {
                    deviceService.markDeviceOnline(deviceId, sessionId);
                }
            }

            clipboardHistoryService.recordClipboard(
                    userPrincipal.getUsername(),
                    deviceId,
                    messageToSend);
        } catch (Exception e) {
            // Don't fail the message relay - history recording is non-critical
        }

        /**
         * Send the message to the user's specific queue:
         * - Destination: "/user/{username}/queue/cliptext"
         * - Spring resolves the username using the Principal associated with the
         * session.
         * - Internally, Spring maps the WebSocket session ID to the username and routes
         * messages accordingly.
         * - By default username is the unique identifier for the user.
         */
        simpMessagingTemplate.convertAndSendToUser(
                userPrincipal.getUsername(),
                "/queue/cliptext",
                messageToSend);
    }

    @GetMapping("/captcha")
    public void getCaptcha(
            HttpServletResponse response,
            HttpSession session) throws IOException {

        if (!clipCascadeProperties.isSignupEnabled())
            return;

        // Generate captcha and store answer in session
        BufferedImage captchaImage = captchaService.generateCaptcha(
                200,
                50,
                5,
                6,
                session,
                ServerConstants.CAPTCHA_SESSION_ID,
                ServerConstants.CAPTCHA_CASE_SENSITIVE);

        // Set the response headers
        response.setContentType("image/png");

        // Write the image to the response output stream
        ImageIO.write(captchaImage, "PNG", response.getOutputStream());
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntityUtil.executeWithResponse(() -> "OK");
    }

    @GetMapping("/ping")
    public ResponseEntity<?> ping() {
        return ResponseEntityUtil.executeWithResponse(() -> "pong");
    }

    @GetMapping("/donation-status")
    public ResponseEntity<?> getDonationStatus() {
        return ResponseEntityUtil.executeWithResponse(() -> {
            if (clipCascadeProperties.isDonationsEnabled()) {
                return Collections.singletonMap("enabled", true);
            } else {
                return Collections.singletonMap("enabled", false);
            }
        });
    }

    @GetMapping("/server-mode")
    public ResponseEntity<?> serverMode() {
        return ResponseEntityUtil.executeWithResponse(() -> {
            if (clipCascadeProperties.isP2pEnabled()) {
                return Collections.singletonMap("mode", "P2P");
            } else {
                return Collections.singletonMap("mode", "P2S");
            }
        });
    }

    @GetMapping("/stun-url")
    public ResponseEntity<?> getStunUrl() {
        return ResponseEntityUtil.executeWithResponse(() -> {
            if (clipCascadeProperties.isP2pEnabled()) {
                return Collections.singletonMap("url", clipCascadeProperties.getP2pStunUrl());
            } else {
                return Collections.singletonMap("url", "");
            }
        });
    }

    @GetMapping("/csrf-token")
    public ResponseEntity<?> getCsrfToken(HttpServletRequest request) {
        // Get the CSRF token from the request
        return ResponseEntityUtil.executeWithResponse(
                () -> (CsrfToken) request.getAttribute("_csrf"));
    }

    @GetMapping("/max-size")
    public ResponseEntity<?> getMaxSizeAllowed() {
        return ResponseEntityUtil.executeWithResponse(
                () -> Collections.singletonMap("maxsize",
                        clipCascadeProperties.getMaxMessageSizeInBytes()));
    }

    @GetMapping("/max-message-size")
    public ResponseEntity<?> getMaxMessageSizeLimit() {
        return ResponseEntityUtil.executeWithResponse(
                () -> Collections.singletonMap("maxmessagesize",
                        clipCascadeProperties.getMaxMessageSizeInMiB()));
    }

    @GetMapping("/whoami")
    public ResponseEntity<?> whoami(@AuthenticationPrincipal UserPrincipal userPrincipal) {

        return ResponseEntityUtil.executeWithResponse(() -> {
            Map<String, String> response = new HashMap<>();
            response.put("username", userPrincipal.getUsername());
            response.put("role", userPrincipal
                    .getAuthorities()
                    .stream()
                    .findFirst()
                    .map(GrantedAuthority::getAuthority)
                    .orElse(""));
            return response;
        });
    }

    @GetMapping("/validate-session")
    public ResponseEntity<?> validateSession() {
        return ResponseEntityUtil.executeWithResponse(() -> "OK");
    }

    @GetMapping("/admin/server-version")
    public ResponseEntity<?> getServerVersion(
            @AuthenticationPrincipal UserPrincipal userPrincipal) {

        return ResponseEntityUtil.conditionalExecuteOrError(
                userPrincipal.isAdmin(),
                () -> ResponseEntityUtil.executeWithResponse(
                        () -> Collections.singletonMap("version", ServerConstants.APP_VERSION)),
                "Forbidden");
    }

    @GetMapping("/admin/latest-server-version")
    public ResponseEntity<?> getLatestServerVersion(
            @AuthenticationPrincipal UserPrincipal userPrincipal) {

        return ResponseEntityUtil.conditionalExecuteOrError(
                userPrincipal.isAdmin(),
                () -> ResponseEntityUtil.executeWithResponse(
                        () -> {
                            try {
                                // get latest version
                                RestTemplate restTemplate = new RestTemplate();
                                String versionJson = restTemplate.getForObject(
                                        ServerConstants.VERSION_URL,
                                        String.class);

                                // convert json string to map
                                ObjectMapper objectMapper = new ObjectMapper();
                                return objectMapper.readValue(versionJson, Map.class);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        }),
                "Forbidden");
    }

    @GetMapping("/admin/websocket-stats")
    public ResponseEntity<?> getWebSocketStats(
            @AuthenticationPrincipal UserPrincipal userPrincipal) {

        return ResponseEntityUtil.conditionalExecuteOrError(
                userPrincipal.isAdmin(),
                () -> ResponseEntityUtil
                        .executeWithResponse(() -> webSocketStatsService.getWebSocketStats()),
                "Forbidden");
    }

    @GetMapping("/admin/all-users")
    public ResponseEntity<?> getAllUsers(@AuthenticationPrincipal UserPrincipal userPrincipal) {

        return ResponseEntityUtil.conditionalExecuteOrError(
                userPrincipal.isAdmin(),
                () -> ResponseEntityUtil.executeWithResponse(() -> userService.getAllUsers()),
                "Forbidden");
    }

    @GetMapping("/admin/users")
    public ResponseEntity<?> getUsers(@AuthenticationPrincipal UserPrincipal userPrincipal) {

        return ResponseEntityUtil.conditionalExecuteOrError(
                userPrincipal.isAdmin(),
                () -> ResponseEntityUtil
                        .executeWithResponse(() -> userService.getUsers(RoleConstants.USER)),
                "Forbidden");
    }

    @GetMapping("/admin/user-details")
    public ResponseEntity<?> getUserDetails(@AuthenticationPrincipal UserPrincipal userPrincipal) {

        return ResponseEntityUtil.conditionalExecuteOrError(
                userPrincipal.isAdmin(),
                () -> ResponseEntityUtil.executeWithResponse(
                        () -> userInfoService.getAllUserDetails()),
                "Forbidden");
    }

    @GetMapping("/admin/bfa-snapshot-file")
    @ResponseBody
    public byte[] getBfaSnapshotFile(
            @AuthenticationPrincipal UserPrincipal userPrincipal) throws JsonProcessingException {

        if (!userPrincipal.isAdmin()) {
            return null;
        }

        return bruteForceProtectionService.getTrackerFile();
    }

    @GetMapping("/admin/bfa-snapshot")
    public ResponseEntity<?> getBfaSnapshot(@AuthenticationPrincipal UserPrincipal userPrincipal) {

        return ResponseEntityUtil.conditionalExecuteOrError(
                userPrincipal.isAdmin(),
                () -> ResponseEntityUtil.executeWithResponse(
                        () -> bruteForceProtectionService.getTracker()),
                "Forbidden");
    }

    @GetMapping("/admin/server-time")
    public ResponseEntity<?> getServerTime(
            @AuthenticationPrincipal UserPrincipal userPrincipal) {

        return ResponseEntityUtil.conditionalExecuteOrError(
                userPrincipal.isAdmin(),
                () -> ResponseEntityUtil.executeWithResponse(
                        () -> "Epoch time: " + TimeUtility.getCurrentTimeInSeconds()),
                "Forbidden");
    }

    @PostMapping("/signup")
    @Transactional
    public ResponseEntity<String> signup(
            HttpSession session,
            String username,
            String password,
            String captchaInput) {

        return ResponseEntityUtil.conditionalExecuteOrError(
                clipCascadeProperties.isSignupEnabled(),
                () ->

                ResponseEntityUtil.conditionalExecuteOrError(
                        captchaService.validateCaptcha(
                                captchaInput,
                                ServerConstants.CAPTCHA_CASE_SENSITIVE,
                                session,
                                ServerConstants.CAPTCHA_SESSION_ID),
                        () ->

                        ResponseEntityUtil.buildResponse(
                                facadeUserService.registerUser(
                                        new Users(
                                                username,
                                                password,
                                                RoleConstants.USER)) != null,
                                "User registered successfully",
                                "Invalid user or password")

                        ,
                        "Captcha validation failed")

                ,
                "Signup is disabled");
    }

    @DeleteMapping("/admin/delete-user")
    @Transactional
    public ResponseEntity<String> deleteUser(
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            @RequestBody Map<String, String> payload) {

        return ResponseEntityUtil.conditionalExecuteOrError(
                userPrincipal.isAdmin(),
                () -> ResponseEntityUtil.conditionalExecuteOrError(
                        userService.isNonAdminUser(payload.get("username")),
                        () -> ResponseEntityUtil.buildResponse(
                                facadeUserService.deleteUser(payload.get("username"),
                                        sessionService),
                                "User deleted successfully",
                                "Invalid user or deletion failed"),
                        "admin user cannot be deleted"),
                "Forbidden");
    }

    @DeleteMapping("/admin/logout-user-session")
    public ResponseEntity<String> logoutUserSession(
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            @RequestBody Map<String, String> payload) {

        return ResponseEntityUtil.conditionalExecuteOrError(
                userPrincipal.isAdmin(),
                () -> ResponseEntityUtil.executeWithResponse(
                        () -> sessionService.logoutAllSessions(payload.get("username"))),
                "Forbidden");
    }

    @DeleteMapping("/logout-session")
    public ResponseEntity<String> logoutSession(
            @AuthenticationPrincipal UserPrincipal userPrincipal) {

        return ResponseEntityUtil.executeWithResponse(
                () -> sessionService.logoutAllSessions(userPrincipal.getUsername()));
    }

    @PutMapping("/admin/toggle-user-status")
    @Transactional
    public ResponseEntity<String> toggleUserStatus(
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            @RequestBody Map<String, String> payload) {

        return ResponseEntityUtil.conditionalExecuteOrError(
                userPrincipal.isAdmin(),
                () -> {
                    boolean enabled = Boolean.parseBoolean(payload.get("enabled"));
                    return ResponseEntityUtil.buildResponse(
                            facadeUserService.updateUserStatus(
                                    payload.get("username"),
                                    enabled,
                                    sessionService) != null,
                            enabled ? "User enabled successfully"
                                    : "User disabled successfully",
                            "Invalid user or operation failed");
                },
                "Forbidden");
    }

    @PostMapping("/admin/register-user")
    @Transactional
    public ResponseEntity<String> registerNewUser(
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            @RequestBody Users user) {

        return ResponseEntityUtil.conditionalExecuteOrError(
                userPrincipal.isAdmin(),
                () -> ResponseEntityUtil.conditionalExecuteOrError(
                        !user.getRole().equals(RoleConstants.ADMIN),
                        () -> ResponseEntityUtil.buildResponse(
                                facadeUserService.registerUser(user) != null,
                                "User registered successfully",
                                "User already exists or invalid user"),
                        "Role must be user"),
                "Forbidden");
    }

    @PutMapping("/update-password")
    @Transactional
    public ResponseEntity<String> updatePassword(
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            @RequestBody Map<String, String> payload) {

        return ResponseEntityUtil.buildResponse(
                facadeUserService.updatePassword(
                        userPrincipal.getUsername(),
                        payload.get("newPassword")) != null,
                "Password updated successfully",
                "Invalid user or password");
    }

    @PutMapping("/admin/update-user-password")
    @Transactional
    public ResponseEntity<String> updateUserPassword(
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            @RequestBody Map<String, String> payload) {

        return ResponseEntityUtil.conditionalExecuteOrError(
                userPrincipal.isAdmin(),
                () -> ResponseEntityUtil.buildResponse(
                        facadeUserService.updatePassword(
                                payload.get("username"),
                                payload.get("newPassword")) != null,
                        "Password updated successfully",
                        "Invalid user or password"),
                "Forbidden");
    }

    @PutMapping("/admin/update-username")
    @Transactional
    public ResponseEntity<String> updateUsername(
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            @RequestBody Map<String, String> payload) {

        return ResponseEntityUtil.conditionalExecuteOrError(
                userPrincipal.isAdmin(),
                () -> ResponseEntityUtil.buildResponse(
                        facadeUserService.updateUsername(
                                payload.get("oldUsername"),
                                payload.get("newUsername"),
                                userPrincipal.getUsername(),
                                sessionService) != null,
                        "Username updated successfully",
                        "Invalid user or username"),
                "Forbidden");
    }

    @PutMapping("/admin/unlock-user")
    @Transactional
    public ResponseEntity<String> unlockUser(
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            @RequestBody Map<String, String> payload) {

        return ResponseEntityUtil.conditionalExecuteOrError(
                userPrincipal.isAdmin(),
                () -> ResponseEntityUtil.buildResponse(
                        bruteForceProtectionService
                                .unlockUser(payload.get("username")) != null,
                        "User unlocked successfully",
                        "Invalid user or operation failed"),
                "Forbidden");
    }

    // ==================== Device Endpoints ====================

    @GetMapping("/admin/devices")
    public ResponseEntity<?> getDevices(@AuthenticationPrincipal UserPrincipal userPrincipal) {
        return ResponseEntityUtil.conditionalExecuteOrError(
                userPrincipal.isAdmin(),
                () -> ResponseEntityUtil.executeWithResponse(
                        () -> deviceService.getDevicesForUser(userPrincipal.getUsername())),
                "Forbidden");
    }

    @PutMapping("/admin/devices/{deviceId}/name")
    public ResponseEntity<?> updateDeviceName(
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            @PathVariable String deviceId,
            @RequestBody Map<String, String> payload) {

        return ResponseEntityUtil.conditionalExecuteOrError(
                userPrincipal.isAdmin(),
                () -> ResponseEntityUtil.buildResponse(
                        deviceService.updateFriendlyName(
                                deviceId,
                                userPrincipal.getUsername(),
                                payload.get("friendlyName")),
                        "Device name updated successfully",
                        "Failed to update device name"),
                "Forbidden");
    }

    @DeleteMapping("/admin/devices/{deviceId}")
    public ResponseEntity<?> deleteDevice(
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            @PathVariable String deviceId) {

        return ResponseEntityUtil.conditionalExecuteOrError(
                userPrincipal.isAdmin(),
                () -> ResponseEntityUtil.buildResponse(
                        deviceService.deleteDevice(deviceId, userPrincipal.getUsername()),
                        "Device deleted successfully",
                        "Failed to delete device"),
                "Forbidden");
    }

    // ==================== Clipboard History Endpoints ====================

    @GetMapping("/admin/clipboard-history")
    public ResponseEntity<?> getClipboardHistory(
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            @RequestParam(required = false) String deviceId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        // Normalize empty strings to null
        final String normalizedDeviceId = (deviceId != null && !deviceId.trim().isEmpty()) ? deviceId.trim() : null;
        String normalizedType = (type != null && !type.trim().isEmpty()) ? type.trim() : null;
        if ("file".equalsIgnoreCase(normalizedType)) {
            normalizedType = "files";
        }
        final String normalizedTypeFinal = normalizedType;
        final String normalizedSearch = (q != null && !q.trim().isEmpty()) ? q.trim() : null;

        return ResponseEntityUtil.conditionalExecuteOrError(
                userPrincipal.isAdmin(),
                () -> ResponseEntityUtil.executeWithResponse(
                        () -> clipboardHistoryService.searchHistory(
                                userPrincipal.getUsername(),
                                normalizedDeviceId,
                                normalizedTypeFinal,
                                from,
                                to,
                                normalizedSearch,
                                page,
                                size)),
                "Forbidden");
    }

    @GetMapping("/admin/clipboard-history/{id}")
    public ResponseEntity<?> getClipboardHistoryItem(
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            @PathVariable Long id) {

        return ResponseEntityUtil.conditionalExecuteOrError(
                userPrincipal.isAdmin(),
                () -> ResponseEntityUtil.executeWithResponse(
                        () -> clipboardHistoryService.getHistoryItem(id, userPrincipal.getUsername())),
                "Forbidden");
    }

    @GetMapping("/admin/clipboard-history/{id}/download")
    public ResponseEntity<byte[]> downloadClipboardHistoryItem(
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            @PathVariable Long id,
            @RequestParam(required = false) String file) {

        if (!userPrincipal.isAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        ClipboardHistory item = clipboardHistoryService.getHistoryItem(id, userPrincipal.getUsername());
        if (item == null || item.getPayload() == null) {
            return ResponseEntity.notFound().build();
        }

        DecodedPayload decoded = decodePayload(item, file);
        if (decoded.data == null || decoded.data.length == 0) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(decoded.contentType));
        headers.setContentDispositionFormData("attachment", decoded.filename);
        return new ResponseEntity<>(decoded.data, headers, HttpStatus.OK);
    }

    @DeleteMapping("/admin/clipboard-history")
    @Transactional
    public ResponseEntity<?> deleteClipboardHistory(
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            @RequestParam(required = false) List<Long> ids,
            @RequestParam(required = false) String deviceId,
            @RequestParam(required = false) Long olderThanDays) {

        return ResponseEntityUtil.conditionalExecuteOrError(
                userPrincipal.isAdmin(),
                () -> {
                    int deleted = 0;
                    String username = userPrincipal.getUsername();

                    if (ids != null && !ids.isEmpty()) {
                        deleted = clipboardHistoryService.deleteByIds(ids, username);
                    } else if (deviceId != null) {
                        deleted = clipboardHistoryService.deleteByDevice(username, deviceId);
                    } else if (olderThanDays != null) {
                        long cutoffTime = (System.currentTimeMillis() / 1000) - (olderThanDays * 24 * 60 * 60);
                        deleted = clipboardHistoryService.deleteOlderThan(username, cutoffTime);
                    }

                    final int deletedCount = deleted;
                    return ResponseEntityUtil.executeWithResponse(
                            () -> Collections.singletonMap("deleted", deletedCount));
                },
                "Forbidden");
    }

    @GetMapping("/admin/clipboard-history/stats")
    public ResponseEntity<?> getClipboardHistoryStats(@AuthenticationPrincipal UserPrincipal userPrincipal) {
        return ResponseEntityUtil.conditionalExecuteOrError(
                userPrincipal.isAdmin(),
                () -> ResponseEntityUtil.executeWithResponse(
                        () -> {
                            Map<String, Object> stats = new HashMap<>();
                            stats.put("totalItems", clipboardHistoryService.countForUser(userPrincipal.getUsername()));
                            stats.put("onlineDevices", deviceService.getOnlineDeviceCount());
                            stats.put("totalDevices", deviceService.getTotalDeviceCount());
                            return stats;
                        }),
                "Forbidden");
    }

    @GetMapping("/admin/dashboard")
    public String dashboard(@AuthenticationPrincipal UserPrincipal userPrincipal) {
        if (userPrincipal.isAdmin()) {
            return "dashboard";
        } else {
            return "redirect:/";
        }
    }

    private static final class DecodedPayload {
        private final byte[] data;
        private final String contentType;
        private final String filename;

        private DecodedPayload(byte[] data, String contentType, String filename) {
            this.data = data;
            this.contentType = contentType;
            this.filename = filename;
        }
    }

    private DecodedPayload decodePayload(ClipboardHistory item, String fileName) {
        String payload = item.getPayload();
        String payloadType = item.getPayloadType() != null ? item.getPayloadType().toLowerCase() : "file";
        String contentType = "application/octet-stream";
        String extension = "bin";

        if ("text".equals(payloadType)) {
            if (looksLikeBase64(payload)) {
                byte[] decoded = safeBase64Decode(payload);
                return new DecodedPayload(decoded, contentType,
                        "clipboard-" + item.getId() + "." + extension);
            }
            contentType = "text/plain";
            extension = "txt";
            return new DecodedPayload(payload.getBytes(StandardCharsets.UTF_8), contentType,
                    "clipboard-" + item.getId() + "." + extension);
        }

        if ("files".equals(payloadType) || "file".equals(payloadType)) {
            DecodedPayload filesPayload = decodeFilesPayload(item, fileName);
            if (filesPayload != null) {
                return filesPayload;
            }
            return new DecodedPayload(new byte[0], contentType, "clipboard-" + item.getId() + ".bin");
        }

        if (payload.startsWith("data:")) {
            int commaIndex = payload.indexOf(',');
            if (commaIndex > 0) {
                String meta = payload.substring(5, commaIndex);
                String dataPart = payload.substring(commaIndex + 1);
                boolean isBase64 = meta.contains(";base64");
                String mime = meta.split(";")[0];
                if (mime != null && !mime.isBlank()) {
                    contentType = mime;
                    extension = extensionFromContentType(mime, payloadType);
                } else {
                    extension = defaultExtension(payloadType);
                }
                if (isBase64) {
                    byte[] decoded = safeBase64Decode(dataPart);
                    return new DecodedPayload(decoded, contentType, "clipboard-" + item.getId() + "." + extension);
                }
                return new DecodedPayload(dataPart.getBytes(StandardCharsets.UTF_8), contentType,
                        "clipboard-" + item.getId() + "." + extension);
            }
        }

        if ("image".equals(payloadType)) {
            contentType = "image/png";
            extension = "png";
        } else {
            extension = defaultExtension(payloadType);
        }

        byte[] decoded = safeBase64Decode(payload);
        return new DecodedPayload(decoded, contentType, "clipboard-" + item.getId() + "." + extension);
    }

    private DecodedPayload decodeFilesPayload(ClipboardHistory item, String fileName) {
        String payload = item.getPayload();
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, String> fileMap = objectMapper.readValue(payload, Map.class);
            if (fileMap == null || fileMap.isEmpty()) {
                return null;
            }
            String selectedName = fileName;
            if (selectedName == null || selectedName.isBlank()) {
                if (fileMap.size() == 1) {
                    selectedName = fileMap.keySet().iterator().next();
                } else {
                    return null;
                }
            }
            String encoded = fileMap.get(selectedName);
            if (encoded == null) {
                return null;
            }
            byte[] decoded = safeBase64Decode(encoded);
            String contentType = URLConnection.guessContentTypeFromName(selectedName);
            if (contentType == null || contentType.isBlank()) {
                contentType = "application/octet-stream";
            }
            return new DecodedPayload(decoded, contentType, selectedName);
        } catch (Exception ex) {
            return null;
        }
    }

    private byte[] safeBase64Decode(String payload) {
        try {
            return Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException ex) {
            return payload.getBytes(StandardCharsets.UTF_8);
        }
    }

    private String defaultExtension(String payloadType) {
        if ("image".equals(payloadType)) {
            return "png";
        }
        if ("file".equals(payloadType)) {
            return "bin";
        }
        return "txt";
    }

    private String extensionFromContentType(String contentType, String payloadType) {
        if (contentType == null) {
            return defaultExtension(payloadType);
        }
        if (contentType.contains("png")) return "png";
        if (contentType.contains("jpeg")) return "jpg";
        if (contentType.contains("jpg")) return "jpg";
        if (contentType.contains("gif")) return "gif";
        if (contentType.contains("webp")) return "webp";
        if (contentType.contains("svg")) return "svg";
        if (contentType.contains("pdf")) return "pdf";
        if (contentType.contains("zip")) return "zip";
        if (contentType.contains("json")) return "json";
        if (contentType.contains("text")) return "txt";
        return defaultExtension(payloadType);
    }

    private boolean looksLikeBase64(String payload) {
        if (payload == null || payload.length() < 200) {
            return false;
        }
        if (payload.startsWith("data:")) {
            return true;
        }
        if (payload.length() % 4 != 0) {
            return false;
        }
        return payload.matches("^[A-Za-z0-9+/=\\r\\n]+$");
    }

}
