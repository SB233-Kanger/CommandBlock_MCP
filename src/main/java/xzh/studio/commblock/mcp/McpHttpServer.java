package xzh.studio.commblock.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.*;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.ee11.servlet.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BiFunction;

public final class McpHttpServer implements AutoCloseable {
    private final Server http = new Server();
    private McpSyncServer mcp;
    public McpHttpServer(McpConfig config, BiFunction<String, Map<String, Object>, McpSchema.CallToolResult> calls) throws Exception {
        try {
            ServerConnector connector = new ServerConnector(http);
            connector.setHost("127.0.0.1"); connector.setPort(config.port); http.addConnector(connector);
            var transport = HttpServletStreamableServerTransportProvider.builder().jsonMapper(McpJsonDefaults.getMapper()).mcpEndpoint("/mcp").build();
            mcp = McpServer.sync(transport).serverInfo("commandblock-mcp", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build()).build();
            for (String name : ToolSchemas.NAMES) {
                var tool = McpSchema.Tool.builder().name(name).description(description(name)).inputSchema(ToolSchemas.schema(name)).build();
                mcp.addTool(new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> calls.apply(name, request.arguments() == null ? Map.of() : request.arguments())));
            }
            ServletContextHandler context = new ServletContextHandler(); context.setContextPath("/");
            Filter filter = (request, response, chain) -> {
                HttpServletRequest req = (HttpServletRequest)request; HttpServletResponse res = (HttpServletResponse)response;
                String authHeader = req.getHeader("Authorization");
                byte[] expected = ("Bearer " + config.token).getBytes(StandardCharsets.UTF_8);
                if (authHeader == null || !MessageDigest.isEqual(expected, authHeader.getBytes(StandardCharsets.UTF_8))) {
                    res.setHeader("WWW-Authenticate", "Bearer"); res.setStatus(401); return;
                }
                String host = req.getHeader("Host");
                if (host == null || !(host.equals("127.0.0.1:" + config.port) || host.equals("localhost:" + config.port))) { res.setStatus(403); return; }
                String origin = req.getHeader("Origin");
                if (origin != null && !(origin.equals("http://127.0.0.1:" + config.port) || origin.equals("http://localhost:" + config.port))) { res.setStatus(403); return; }
                chain.doFilter(request, response);
            };
            FilterHolder auth = new FilterHolder(filter); auth.setAsyncSupported(true);
            context.addFilter(auth, "/*", EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC));
            ServletHolder servlet = new ServletHolder(transport); servlet.setAsyncSupported(true); context.addServlet(servlet, "/mcp");
            http.setHandler(context); http.start();
        } catch (Exception e) { close(); throw e; }
    }
    private static String description(String name) {
        return switch (name) {
            case "get_world_context" -> "Get dimensions, online players, positions and permissions.";
            case "get_command_block" -> "Read configuration and execution output at explicit dimension and integer coordinates.";
            case "place_command_block" -> "Place in a loaded chunk. Defaults: impulse, north, unconditional, redstone required, output tracked. Occupied targets require replace=true.";
            case "update_command_block" -> "Patch an existing command block; omitted fields retain values.";
            case "remove_command_block" -> "Replace an existing command block with air.";
            case "batch_place_command_blocks" -> "Preflight and place 1–256 blocks. Duplicate coordinates forbidden. Configure all before automatic activation. Restore original targets on write failure.";
            case "query_area" -> "Read an inclusive cuboid (normalized endpoints, at most 32768 blocks) in loaded chunks. Count all non-air blocks and intersecting entities including players. Details default to 256 each, max 1024. Clearance is a snapshot, not a reservation.";
            case "precheck_commands" -> "Parse 1–256 commands at explicit positions using vanilla command block permissions without executing or resolving selector targets. Empty commands are valid; one leading slash is allowed. Syntax validity does not guarantee runtime success.";
            case "get_command_chain" -> "Read the start and successive chain command blocks following each facing, including turns, without executing. Ignore power, conditions and game rule chain limits. maxBlocks defaults to 4096 (maximum 4096); stop at tail, loop, limit, boundary or unloaded chunk.";
            default -> "Execute once: mode=single (default) or chain. Preserve conditions, same-tick guard, vanilla chain length and activation; no recurring task.";
        };
    }
    public void close() {
        try { if (mcp != null) mcp.close(); } catch (Exception ignored) {}
        try { http.stop(); } catch (Exception ignored) {}
    }
}
