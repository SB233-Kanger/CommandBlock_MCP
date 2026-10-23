package xzh.studio.commblock.mcp;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.network.chat.*;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

public class CommandBlock_MCP implements ModInitializer {
    public static final String MOD_ID = "commandblock_mcp";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private final Set<UUID> verbose = new HashSet<>();
    private final Path configPath = FabricLoader.getInstance().getConfigDir().resolve("commandblock_mcp.json");
    private volatile Session session;
    @Override public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(this::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            Session old = session; session = null; verbose.clear();
            if (old != null) { old.requests.close(); old.http.close(); }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> verbose.remove(handler.player.getUUID()));
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> dispatcher.register(Commands.literal("cbmcp")
            .then(Commands.literal("enable").requires(this::admin).executes(ctx -> enable(ctx.getSource(), true)))
            .then(Commands.literal("disable").requires(this::admin).executes(ctx -> enable(ctx.getSource(), false)))
            .then(Commands.literal("status").requires(this::admin).executes(ctx -> {
                Session current = session;
                ctx.getSource().sendSuccess(() -> Component.literal(current == null ? "MCP unavailable; check server log and configuration" : "MCP " + (current.tools.allowed() ? "enabled" : "disabled") + " at http://127.0.0.1:" + current.config.port + "/mcp; vanilla command blocks=" + ctx.getSource().getLevel().isCommandBlockEnabled()), false); return 1;
            }))
            .then(Commands.literal("verbose")
                .then(Commands.literal("on").executes(ctx -> subscribe(ctx.getSource(), true)))
                .then(Commands.literal("off").executes(ctx -> subscribe(ctx.getSource(), false))))));
    }
    private boolean admin(CommandSourceStack source) { return source.permissions().hasPermission(Permissions.COMMANDS_ADMIN); }
    private int subscribe(CommandSourceStack source, boolean on) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        UUID id = source.getPlayerOrException().getUUID(); if (on) verbose.add(id); else verbose.remove(id);
        source.sendSuccess(() -> Component.literal("MCP verbose " + (on ? "on" : "off")), false); return 1;
    }
    private int enable(CommandSourceStack source, boolean enabled) {
        Session current = session;
        if (current == null) { source.sendFailure(Component.literal("MCP unavailable; fix configuration/port and restart world")); return 0; }
        boolean old = current.config.enabled; current.config.enabled = enabled;
        try { current.config.save(configPath); }
        catch (Exception e) { current.config.enabled = old; source.sendFailure(Component.literal("Could not persist MCP enabled state")); return 0; }
        source.sendSuccess(() -> Component.literal("MCP " + (enabled ? "enabled" : "disabled") + (enabled && !current.tools.allowed() ? "; enable singleplayer cheats before use" : "")), false); return 1;
    }
    private void start(MinecraftServer server) {
        try {
            McpConfig config = McpConfig.load(configPath);
            WorldRequests requests = new WorldRequests(server::execute);
            WorldTools tools = new WorldTools(server, config);
            McpHttpServer http = new McpHttpServer(config, (name, args) -> invoke(server, requests, tools, config, name, args));
            session = new Session(server, config, requests, tools, http);
            LOGGER.info("CommandBlock MCP listening at http://127.0.0.1:{}/mcp (enabled={}); token stored in config/commandblock_mcp.json", config.port, config.enabled);
        } catch (Exception e) {
            // Exception messages from the HTTP stack can contain headers; do not log them.
            String reason = e instanceof java.net.BindException ? "configured port is already in use" : e instanceof com.google.gson.JsonParseException ? "invalid JSON configuration" : e instanceof java.io.IOException ? "invalid or unreadable configuration, or HTTP bind failure" : "HTTP/SDK initialization failed";
            LOGGER.error("CommandBlock MCP disabled: {} ({}). Check configuration and port availability.", reason, e.getClass().getSimpleName());
        }
    }
    private McpSchema.CallToolResult invoke(MinecraftServer server, WorldRequests requests, WorldTools tools, McpConfig config, String name, Map<String, Object> args) {
        try {
            return requests.call(() -> {
                if (session == null || session.server != server) throw new IllegalStateException("World session has ended");
                McpSchema.CallToolResult result;
                try {
                    Map<String, Object> data = tools.call(name, args);
                    result = response(data, "failed".equals(data.get("status")));
                } catch (Exception e) { result = failure(name, args, e); }
                broadcast(server, name, args, result); return result;
            }, config.requestTimeoutSeconds);
        } catch (Exception e) {
            McpSchema.CallToolResult result = failure(name, args, e);
            server.execute(() -> { if (session != null && session.server == server) broadcast(server, name, args, result); });
            return result;
        }
    }
    private static McpSchema.CallToolResult failure(String name, Map<String, Object> args, Exception exception) {
        Throwable e = exception instanceof ExecutionException && exception.getCause() != null ? exception.getCause() : exception;
        Map<String, Object> data = new LinkedHashMap<>(); data.put("status", "failed"); data.put("tool", name); data.put("target", args);
        data.put("reason", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()); return response(data, true);
    }
    private static McpSchema.CallToolResult response(Map<String, Object> data, boolean error) {
        return McpSchema.CallToolResult.builder().structuredContent(data).addTextContent(new com.google.gson.Gson().toJson(data)).isError(error).build();
    }
    private void broadcast(MinecraftServer server, String name, Map<String, Object> args, McpSchema.CallToolResult result) {
        @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>)result.structuredContent();
        Object requestedEntries = args.containsKey("commands") ? args.get("commands") : args.get("blocks");
        String where = args.containsKey("dimension") ? WorldTools.location(args) : requestedEntries instanceof List<?> list ? "batch " + list.size() + " targets" : "world";
        if (name.equals("query_area")) where += " to [" + args.get("endX") + ", " + args.get("endY") + ", " + args.get("endZ") + "]";
        String summary = "[MCP] " + name + " " + where + " → " + data.get("status") + (data.containsKey("reason") ? ": " + data.get("reason") : "");
        if (summary.length() > 800) summary = summary.substring(0, 800) + "…";
        MutableComponent message = Component.literal(summary);
        // Keep even a 256-block verbose message within vanilla chat component limits.
        StringBuilder detail = new StringBuilder();
        Object entries = data.get("commands") instanceof List<?> ? data.get("commands") : data.get("blocks") instanceof List<?> ? data.get("blocks") : requestedEntries;
        if (name.equals("query_area")) entries = null;
        if (entries instanceof List<?> list) {
            for (Object item : list) if (item instanceof Map<?, ?> entry) {
                detail.append(entry.get("dimension")).append(" [").append(entry.get("x")).append(", ").append(entry.get("y")).append(", ").append(entry.get("z")).append("] ")
                    .append(entry.getOrDefault("status", null) == null ? data.get("status") : entry.get("status"));
                if (entry.get("reason") != null) detail.append(": ").append(entry.get("reason"));
                detail.append('\n');
            }
        } else detail.append(new com.google.gson.Gson().toJson(data));
        String hover = detail.length() > 24000 ? detail.substring(0, 24000) + "\n…" : detail.toString();
        message.withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
        server.getPlayerList().getPlayers().stream().filter(p -> verbose.contains(p.getUUID())).forEach(p -> p.sendSystemMessage(message));
    }
    private record Session(MinecraftServer server, McpConfig config, WorldRequests requests, WorldTools tools, McpHttpServer http) {}
}
