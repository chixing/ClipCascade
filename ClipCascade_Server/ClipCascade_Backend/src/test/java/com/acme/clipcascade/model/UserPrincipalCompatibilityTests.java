package com.acme.clipcascade.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UserPrincipalCompatibilityTests {
    @TempDir Path temporary;

    @Test
    void readsSessionsWrittenByTheDeployedLegacyPrincipal() throws Exception {
        Path source = temporary.resolve("com/acme/clipcascade/model/UserPrincipal.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.acme.clipcascade.model;
                public class UserPrincipal implements java.io.Serializable {
                    private static final long serialVersionUID = 1L;
                    private Users user;
                    public UserPrincipal(Users user) { this.user = user; }
                }
                """);
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-classpath", System.getProperty("java.class.path"),
                "-d", temporary.toString(), source.toString())).isZero();
        try (var loader = new URLClassLoader(new URL[] { temporary.toUri().toURL() },
                UserPrincipal.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals(UserPrincipal.class.getName())) {
                    Class<?> result = findLoadedClass(name);
                    if (result == null) result = findClass(name);
                    if (resolve) resolveClass(result);
                    return result;
                }
                return super.loadClass(name, resolve);
            }
        }) {
            for (boolean enabled : new boolean[] { true, false }) {
                Object legacy = loader.loadClass(UserPrincipal.class.getName()).getConstructor(Users.class)
                        .newInstance(new Users("existing-admin", "legacy-secret-hash", "ADMIN", enabled));
                UserPrincipal restored = read(write(legacy));
                assertThat(restored.getUsername()).isEqualTo("existing-admin");
                assertThat(restored.isAdmin()).isTrue();
                assertThat(restored.isEnabled()).isEqualTo(enabled);
                assertThat(restored.isAccountNonLocked()).isTrue();
                assertThat(restored.getPassword()).isNull();
                assertThat(new String(write(restored), StandardCharsets.ISO_8859_1))
                        .doesNotContain("legacy-secret-hash");
                try (var oldInput = new ObjectInputStream(new ByteArrayInputStream(write(restored))) {
                    @Override protected Class<?> resolveClass(ObjectStreamClass descriptor)
                            throws java.io.IOException, ClassNotFoundException {
                        return descriptor.getName().equals(UserPrincipal.class.getName())
                                ? loader.loadClass(descriptor.getName()) : super.resolveClass(descriptor);
                    }
                }) {
                    Object rollbackPrincipal = oldInput.readObject();
                    var field = rollbackPrincipal.getClass().getDeclaredField("user");
                    field.setAccessible(true);
                    Users rollbackUser = (Users) field.get(rollbackPrincipal);
                    assertThat(rollbackUser.getUsername()).isEqualTo("existing-admin");
                    assertThat(rollbackUser.getRole()).isEqualTo("ADMIN");
                    assertThat(rollbackUser.getEnabled()).isEqualTo(enabled);
                    assertThat(rollbackUser.getPassword()).isNull();
                }
            }
        }
    }

    @Test
    void newSessionsKeepIdentityWithoutPersistingCredentials() throws Exception {
        var principal = new UserPrincipal(new Users("new-user", "new-secret-hash", "USER"), null);
        assertThat(principal.getPassword()).isEqualTo("new-secret-hash");
        byte[] serialized = write(principal);
        assertThat(new String(serialized, StandardCharsets.ISO_8859_1)).doesNotContain("new-secret-hash");
        UserPrincipal restored = read(serialized);
        assertThat(restored.getUsername()).isEqualTo("new-user");
        assertThat(restored.isUser()).isTrue();
        assertThat(restored.isEnabled()).isTrue();
        assertThat(restored.getPassword()).isNull();
        principal.eraseCredentials();
        assertThat(principal.getPassword()).isNull();
    }

    private byte[] write(Object value) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var output = new ObjectOutputStream(bytes)) { output.writeObject(value); }
        return bytes.toByteArray();
    }

    private UserPrincipal read(byte[] bytes) throws Exception {
        try (var input = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return (UserPrincipal) input.readObject();
        }
    }
}
