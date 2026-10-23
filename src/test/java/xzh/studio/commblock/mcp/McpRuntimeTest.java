package xzh.studio.commblock.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import static org.junit.jupiter.api.Assertions.*;

class McpRuntimeTest {
    @TempDir Path directory;
    @Test void configPersistsAndRejectsInvalid() throws Exception {
        Path path = directory.resolve("config.json"); McpConfig config = McpConfig.load(path);
        assertFalse(config.enabled); assertTrue(config.token.length() >= 32);
        config.enabled = true; config.save(path);
        McpConfig loaded = McpConfig.load(path); assertEquals(config.token, loaded.token); assertTrue(loaded.enabled);
        Files.writeString(path, "{\"port\":0}"); assertThrows(Exception.class, () -> McpConfig.load(path));
    }
    @Test void timeoutNeverRunsQueuedMutation() throws Exception {
        List<Runnable> queue = new ArrayList<>(); AtomicInteger writes = new AtomicInteger();
        try (WorldRequests requests = new WorldRequests(queue::add)) {
            assertThrows(TimeoutException.class, () -> requests.call(writes::incrementAndGet, 1));
            queue.getFirst().run(); assertEquals(0, writes.get());
        }
    }
    @Test void stopCancelsQueuedMutation() throws Exception {
        BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>(); AtomicInteger writes = new AtomicInteger();
        WorldRequests requests = new WorldRequests(queue::add);
        CompletableFuture<Object> call = CompletableFuture.supplyAsync(() -> {
            try { return requests.call(writes::incrementAndGet, 10); } catch (Exception e) { return e; }
        });
        Runnable queued = queue.poll(5, TimeUnit.SECONDS); assertNotNull(queued);
        requests.close(); assertInstanceOf(CancellationException.class, call.get(5, TimeUnit.SECONDS));
        queued.run(); assertEquals(0, writes.get());
    }
    @Test void schemasRejectCoercionAndOversizeBatch() {
        Map<String, Object> args = new HashMap<>(Map.of("dimension", "minecraft:overworld", "x", 1, "y", 64, "z", 1, "command", "say test"));
        ToolSchemas.validate("place_command_block", args);
        for (Object bad : List.of(1.5, 2147483648L, "1")) { args.put("x", bad); assertThrows(IllegalArgumentException.class, () -> ToolSchemas.validate("place_command_block", args)); }
        args.put("x", 1); args.put("replace", "true"); assertThrows(IllegalArgumentException.class, () -> ToolSchemas.validate("place_command_block", args));
        assertThrows(IllegalArgumentException.class, () -> ToolSchemas.validate("batch_place_command_blocks", Map.of("blocks", Collections.nCopies(257, args))));
    }
    private static Map<String, Object> position() { return new HashMap<>(Map.of("dimension", "minecraft:overworld", "x", 0, "y", 100, "z", 0)); }
    private static Map<String, Object> area() {
        Map<String, Object> args = position(); args.putAll(Map.of("endX", 31, "endY", 131, "endZ", 31)); return args;
    }
    @Test void areaSchemaAndInclusiveVolumeLimits() {
        Map<String, Object> args = area(); ToolSchemas.validate("query_area", args);
        assertEquals(32768, ToolSchemas.areaVolume(args));
        args.put("x", 31); args.put("endX", 0); ToolSchemas.validate("query_area", args);
        args.put("endY", 132); assertThrows(IllegalArgumentException.class, () -> ToolSchemas.validate("query_area", args));
        Map<String, Object> extreme = area(); extreme.put("x", Integer.MIN_VALUE); extreme.put("endX", Integer.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> ToolSchemas.validate("query_area", extreme));
        Map<String, Object> limits = area(); limits.put("maxBlocks", 1); limits.put("maxEntities", 1024); ToolSchemas.validate("query_area", limits);
        for (String field : List.of("maxBlocks", "maxEntities")) {
            for (Object bad : List.of(0, -1, 1025, 1.5, "256", true, Double.NaN, Double.POSITIVE_INFINITY, 2147483648L)) {
                Map<String, Object> invalid = area(); invalid.put(field, bad);
                assertThrows(IllegalArgumentException.class, () -> ToolSchemas.validate("query_area", invalid));
            }
        }
        for (String field : List.of("dimension", "x", "y", "z", "endX", "endY", "endZ")) {
            Map<String, Object> missing = area(); missing.remove(field); assertThrows(IllegalArgumentException.class, () -> ToolSchemas.validate("query_area", missing));
            Map<String, Object> wrong = area(); wrong.put(field, null); assertThrows(IllegalArgumentException.class, () -> ToolSchemas.validate("query_area", wrong));
        }
        Map<String, Object> unknown = area(); unknown.put("replace", true); assertThrows(IllegalArgumentException.class, () -> ToolSchemas.validate("query_area", unknown));
        assertEquals(256, ((Map<?, ?>)ToolSchemas.schema("query_area").properties().get("maxBlocks")).get("default"));
    }
    @Test void precheckValidatesItsOwnArrayItemSchema() {
        Map<String, Object> item = position(); item.put("command", "");
        ToolSchemas.validate("precheck_commands", Map.of("commands", List.of(item)));
        ToolSchemas.validate("precheck_commands", Map.of("commands", Collections.nCopies(256, item)));
        for (Object bad : List.of(List.of(), Collections.nCopies(257, item), List.of("say hello"), "say hello")) {
            assertThrows(IllegalArgumentException.class, () -> ToolSchemas.validate("precheck_commands", Map.of("commands", bad)));
        }
        assertThrows(IllegalArgumentException.class, () -> ToolSchemas.validate("precheck_commands", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> ToolSchemas.validate("precheck_commands", Map.of("commands", List.of(item), "blocks", List.of(item))));
        for (String field : List.of("type", "facing", "automatic", "replace", "mode", "unknown")) {
            Map<String, Object> invalid = new HashMap<>(item); invalid.put(field, true);
            assertThrows(IllegalArgumentException.class, () -> ToolSchemas.validate("precheck_commands", Map.of("commands", List.of(item, invalid))));
        }
        for (String field : item.keySet()) {
            Map<String, Object> missing = new HashMap<>(item); missing.remove(field);
            assertThrows(IllegalArgumentException.class, () -> ToolSchemas.validate("precheck_commands", Map.of("commands", List.of(missing))));
            Map<String, Object> wrong = new HashMap<>(item); wrong.put(field, field.equals("command") || field.equals("dimension") ? 1 : "1");
            assertThrows(IllegalArgumentException.class, () -> ToolSchemas.validate("precheck_commands", Map.of("commands", List.of(wrong))));
        }
        item.put("command", "a".repeat(32768)); assertThrows(IllegalArgumentException.class, () -> ToolSchemas.validate("precheck_commands", Map.of("commands", List.of(item))));
        item.put("command", "a".repeat(32767)); ToolSchemas.validate("precheck_commands", Map.of("commands", List.of(item)));
        Map<String, Object> place = position(); place.put("command", "unknown command remains accepted"); place.put("automatic", true);
        ToolSchemas.validate("batch_place_command_blocks", Map.of("blocks", List.of(place)));
    }
    @Test void chainSchemaEnforcesLimitsAndFields() {
        ToolSchemas.validate("get_command_chain", position());
        for (int count : List.of(1, 4096)) { Map<String, Object> valid = position(); valid.put("maxBlocks", count); ToolSchemas.validate("get_command_chain", valid); }
        for (Object bad : List.of(0, -1, 4097, 2.5, "4096", false)) {
            Map<String, Object> invalid = position(); invalid.put("maxBlocks", bad); assertThrows(IllegalArgumentException.class, () -> ToolSchemas.validate("get_command_chain", invalid));
        }
        Map<String, Object> unknown = position(); unknown.put("mode", "chain"); assertThrows(IllegalArgumentException.class, () -> ToolSchemas.validate("get_command_chain", unknown));
        assertEquals(4096, ((Map<?, ?>)ToolSchemas.schema("get_command_chain").properties().get("maxBlocks")).get("default"));
        assertThrows(IllegalArgumentException.class, () -> ToolSchemas.validate("unknown", Map.of()));
    }
    @Test void officialSdkHttpLifecycleAndAuthentication() throws Exception {
        McpConfig config = new McpConfig(); config.token = "test-token-abcdefghijklmnopqrstuvwxyz";
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { config.port = socket.getLocalPort(); }
        String base = "http://127.0.0.1:" + config.port;
        try (McpHttpServer server = new McpHttpServer(config, (name, args) -> McpSchema.CallToolResult.builder().structuredContent(Map.of("tool", name)).addTextContent("ok").isError(false).build())) {
            try (HttpClient http = HttpClient.newHttpClient()) {
                URI uri = URI.create(base + "/mcp");
                assertEquals(401, http.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
                assertEquals(401, http.send(HttpRequest.newBuilder(uri).header("Authorization", "Bearer wrong").GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
                assertEquals(403, http.send(HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + config.token).header("Origin", "https://evil.invalid").GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
            }
            var transport = HttpClientStreamableHttpTransport.builder(base).endpoint("/mcp")
                .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + config.token)).build();
            var client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(10)).build();
            try {
                assertEquals("commandblock-mcp", client.initialize().serverInfo().name());
                var discovered = client.listTools().tools();
                assertEquals(10, discovered.size());
                assertEquals(new HashSet<>(ToolSchemas.NAMES), new HashSet<>(discovered.stream().map(McpSchema.Tool::name).toList()));
                for (var tool : discovered) {
                    var expected = ToolSchemas.schema(tool.name());
                    assertEquals(Map.of("type", "object", "properties", expected.properties(), "required", expected.required(), "additionalProperties", false), tool.inputSchema());
                }
                var result = client.callTool(new McpSchema.CallToolRequest("get_world_context", Map.of()));
                assertFalse(result.isError()); assertEquals("get_world_context", ((Map<?, ?>)result.structuredContent()).get("tool"));
            } finally { client.closeGracefully(); }
            assertThrows(Exception.class, () -> new McpHttpServer(config, (name, args) -> null));
        }
        // Port and servlet threads were released; a new world can bind the same port.
        try (McpHttpServer reopened = new McpHttpServer(config, (name, args) -> null)) {}
    }
}
