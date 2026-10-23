package xzh.studio.commblock.mcp;

import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.Base64;

public final class McpConfig {
    public int port = 8765;
    public boolean enabled = false;
    public int requestTimeoutSeconds = 15;
    public String token;
    private static final com.google.gson.Gson JSON = new GsonBuilder().setPrettyPrinting().create();

    public static McpConfig load(Path path) throws IOException {
        McpConfig config = Files.exists(path) ? JSON.fromJson(Files.readString(path), McpConfig.class) : new McpConfig();
        if (config == null || config.port < 1 || config.port > 65535 || config.requestTimeoutSeconds < 1 || config.requestTimeoutSeconds > 120)
            throw new IOException("Invalid MCP port or timeout configuration");
        if (config.token == null || config.token.isBlank()) {
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            config.token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            config.save(path);
        }
        if (!config.token.matches("[A-Za-z0-9_-]{32,256}")) throw new IOException("MCP token must be 32–256 URL-safe characters");
        return config;
    }

    public void save(Path path) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Path temp = Files.createTempFile(path.toAbsolutePath().getParent(), "cbmcp-", ".tmp");
        try {
            Files.writeString(temp, JSON.toJson(this));
            try { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
}
