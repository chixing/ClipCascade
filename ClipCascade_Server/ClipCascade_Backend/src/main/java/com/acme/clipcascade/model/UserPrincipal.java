package com.acme.clipcascade.model;

import java.io.Serial;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamField;
import java.util.Collection;
import java.util.Collections;

import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import com.acme.clipcascade.constants.RoleConstants;
import com.acme.clipcascade.service.BruteForceProtectionService;

public class UserPrincipal implements UserDetails, CredentialsContainer {

    @Serial
    private static final long serialVersionUID = 1L;

    // Read sessions written before upstream flattened the principal. The legacy
    // projection stays password-free and lets the recovery image read new sessions.
    @Serial
    private static final ObjectStreamField[] serialPersistentFields = {
            new ObjectStreamField("user", Users.class),
            new ObjectStreamField("username", String.class),
            new ObjectStreamField("role", String.class),
            new ObjectStreamField("enabled", boolean.class)
    };

    private String username;
    private String role;
    private boolean enabled;
    // Neither password hashes nor application services belong in persisted sessions.
    private transient String password;
    private final transient BruteForceProtectionService bruteForceProtectionService;

    public UserPrincipal(
            Users user,
            BruteForceProtectionService bruteForceProtectionService) {

        this.username = user.getUsername();
        this.role = user.getRole();
        this.enabled = user.getEnabled();
        this.password = user.getPassword();
        this.bruteForceProtectionService = bruteForceProtectionService;
    }

    @Serial
    private void readObject(ObjectInputStream input) throws IOException, ClassNotFoundException {
        ObjectInputStream.GetField fields = input.readFields();
        username = (String) fields.get("username", null);
        role = (String) fields.get("role", null);
        enabled = fields.get("enabled", false);
        if (username == null) {
            Users legacy = (Users) fields.get("user", null);
            if (legacy == null) throw new java.io.InvalidObjectException("Session principal has no identity");
            username = legacy.getUsername();
            role = legacy.getRole();
            enabled = legacy.getEnabled();
        }
        if (username == null || role == null) {
            throw new java.io.InvalidObjectException("Session principal identity is incomplete");
        }
    }

    @Serial
    private void writeObject(ObjectOutputStream output) throws IOException {
        ObjectOutputStream.PutField fields = output.putFields();
        fields.put("user", new Users(username, null, role, enabled));
        fields.put("username", username);
        fields.put("role", role);
        fields.put("enabled", enabled);
        output.writeFields();
    }

    @Override
    public boolean isAccountNonLocked() {

        // validate attempt using brute force protection
        return bruteForceProtectionService == null
                || bruteForceProtectionService.recordAndValidateAttempt(username);
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // Return a collection of roles
        return Collections.singleton(new SimpleGrantedAuthority(role));
    }

    @Override
    public void eraseCredentials() {
        this.password = null;
    }

    public boolean isAdmin() {
        return this.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority()
                        .strip()
                        .equalsIgnoreCase(RoleConstants.ADMIN));
    }

    public boolean isUser() {
        return this.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority()
                        .strip()
                        .equalsIgnoreCase(RoleConstants.USER));
    }
}
