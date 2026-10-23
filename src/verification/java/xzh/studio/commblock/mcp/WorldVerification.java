package xzh.studio.commblock.mcp;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import net.minecraft.world.level.gamerules.GameRules;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.http.HttpRequest;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Development-only fixture; never packaged in the release mod. */
public final class WorldVerification implements ModInitializer {
    public static volatile int writesUntilFailure;
    private static final net.minecraft.server.level.TicketType HTTP_FIXTURE_TICKET = new net.minecraft.server.level.TicketType(net.minecraft.server.level.TicketType.NO_TIMEOUT, net.minecraft.server.level.TicketType.FLAG_LOADING);
    private int checks;
    private boolean restorePhase;
    public void onInitialize() {
        if (!Boolean.getBoolean("cbmcp.verification")) return;
        ServerLifecycleEvents.SERVER_STARTED.register(server -> Thread.ofPlatform().name("cbmcp-verification").start(() -> {
            try {
                Files.writeString(Path.of("verification-result.txt"), "RUNNING");
                server.submit(() -> verifyWorld(server)).get(30, TimeUnit.SECONDS);
                verifyHttp();
                Files.writeString(Path.of("verification-result.txt"), "PASS " + checks + " checks; restorePhase=" + restorePhase);
            } catch (Throwable e) {
                e.printStackTrace();
                try { Files.writeString(Path.of("verification-result.txt"), "FAIL " + e); } catch (Exception ignored) {}
            } finally { server.halt(false); }
        }));
    }
    private void check(boolean condition, String reason) { if (!condition) throw new AssertionError(reason); checks++; }
    private static Map<String, Object> pos(int x, int y, int z) { return new HashMap<>(Map.of("dimension", "minecraft:overworld", "x", x, "y", y, "z", z)); }
    private Map<String, Object> place(int x, String command) { Map<String, Object> args = pos(x, 100, 0); args.put("command", command); return args; }
    private void rejected(Runnable action, String reason) { try { action.run(); } catch (IllegalArgumentException | IllegalStateException e) { checks++; return; } throw new AssertionError(reason); }
    private void verifyWorld(MinecraftServer server) {
        var level = server.overworld();
        // Keep this fixture loaded across ticks while HTTP requests are dispatched. No players are connected.
        level.getChunkSource().addTicketWithRadius(HTTP_FIXTURE_TICKET, new net.minecraft.world.level.ChunkPos(0, 0), 0);
        level.getChunk(0, 0); level.getChunk(1, 0); level.getChunk(2, 0); level.getChunk(-1, 0); level.getChunk(1, -1);
        verifySubscriptions(server);
        McpConfig config = new McpConfig(); config.enabled = true; WorldTools tools = new WorldTools(server, config);
        if (server.isSingleplayer()) {
            server.getWorldData().setAllowCommands(false);
            rejected(() -> tools.call("get_world_context", Map.of()), "integrated server cheats required");
        }
        server.getWorldData().setAllowCommands(true);
        level.getGameRules().set(GameRules.COMMAND_BLOCKS_WORK, true, server);
        restorePhase = Files.exists(Path.of("restore-marker.txt"));
        if (restorePhase) {
            var saved = tools.call("get_command_block", pos(30, 100, 0));
            check(saved.get("command").equals("say persistence"), "command survived restart");
            check(saved.get("type").equals("chain") && saved.get("facing").equals("up"), "state survived restart");
            check(saved.get("name").equals("saved") && saved.get("trackOutput").equals(false), "configuration survived restart");
        }
        for (int x = 0; x <= 31; x++) level.setBlock(new BlockPos(x, 100, 0), Blocks.AIR.defaultBlockState(), 3);
        config.enabled = false; rejected(() -> tools.call("get_world_context", Map.of()), "disabled permission"); config.enabled = true;
        check(((List<?>)tools.call("get_world_context", Map.of()).get("dimensions")).size() >= 3, "context dimensions");
        int x = 0;
        for (String type : List.of("impulse", "repeating", "chain")) for (String facing : List.of("north", "south", "east", "west", "up", "down")) {
            var args = place(x, "say test"); args.put("type", type); args.put("facing", facing);
            tools.call("place_command_block", args);
            var read = tools.call("get_command_block", pos(x, 100, 0));
            check(read.get("type").equals(type) && read.get("facing").equals(facing), "type/facing roundtrip"); x++;
        }
        var patch = pos(0, 100, 0); patch.put("conditional", true); patch.put("automatic", true); patch.put("trackOutput", false); patch.put("name", "custom");
        tools.call("update_command_block", patch);
        var commandOnly = pos(0, 100, 0); commandOnly.put("command", "say patched"); tools.call("update_command_block", commandOnly);
        var retained = tools.call("get_command_block", pos(0, 100, 0));
        check(retained.get("conditional").equals(true) && retained.get("automatic").equals(true) && retained.get("trackOutput").equals(false) && retained.get("name").equals("custom"), "patch preserves omitted fields");
        rejected(() -> tools.call("place_command_block", place(0, "say blocked")), "overwrite protection");
        var replace = place(0, "say fresh"); replace.put("replace", true); tools.call("place_command_block", replace);
        check(tools.call("get_command_block", pos(0, 100, 0)).get("conditional").equals(false), "replace uses defaults");
        var invalid = pos(0, 100, 0); invalid.put("dimension", "minecraft:missing"); rejected(() -> tools.call("get_command_block", invalid), "dimension validation");
        rejected(() -> tools.call("get_command_block", pos(0, 100000, 0)), "height validation");
        rejected(() -> tools.call("get_command_block", pos(30000000, 100, 0)), "world boundary");
        rejected(() -> tools.call("get_command_block", pos(1000000, 100, 0)), "unloaded chunk");
        // A far-away fixture guarantees the next chunk was never loaded by spawn preparation.
        level.getChunk(6250, 6250);
        BlockPos edge = new BlockPos(100015, 100, 100000); BlockPos nextChunk = edge.east();
        check(!level.hasChunkAt(nextChunk), "edge fixture next chunk unloaded");
        level.setBlock(edge, Blocks.AIR.defaultBlockState(), 16);
        var edgeArgs = pos(edge.getX(), edge.getY(), edge.getZ()); edgeArgs.put("command", "say edge"); edgeArgs.put("facing", "east");
        tools.call("place_command_block", edgeArgs);
        var edgeExecute = pos(edge.getX(), edge.getY(), edge.getZ()); edgeExecute.put("mode", "chain");
        check(tools.call("execute_command_block", edgeExecute).get("stopReason").equals("unloaded_chunk"), "chain stops before unloaded chunk");
        check(!level.hasChunkAt(nextChunk), "placement and execution never load adjacent chunk");
        var one = place(20, "say batch");
        rejected(() -> tools.call("batch_place_command_blocks", Map.of("blocks", List.of(one, one))), "duplicate detection");
        rejected(() -> tools.call("batch_place_command_blocks", Map.of("blocks", List.of(one, place(0, "say occupied")))), "whole-batch preflight");
        check(level.getBlockState(new BlockPos(20, 100, 0)).isAir(), "failed batch writes nothing");
        tools.call("batch_place_command_blocks", Map.of("blocks", List.of(one, place(21, "say batch2"))));
        check(tools.call("get_command_block", pos(21, 100, 0)).get("command").equals("say batch2"), "batch success");
        var oldEntity = ((CommandBlockEntity)level.getBlockEntity(new BlockPos(21, 100, 0))).saveWithFullMetadata(level.registryAccess());
        var overwritesEntity = place(21, "say overwritten"); overwritesEntity.put("replace", true);
        level.setBlock(new BlockPos(22, 100, 0), Blocks.STONE.defaultBlockState(), 3);
        var overwritesStone = place(22, "say overwritten"); overwritesStone.put("replace", true);
        writesUntilFailure = 2;
        rejected(() -> tools.call("batch_place_command_blocks", Map.of("blocks", List.of(overwritesEntity, overwritesStone))), "injected write failure");
        check(level.getBlockState(new BlockPos(22, 100, 0)).is(Blocks.STONE), "rollback restores non-entity block");
        check(((CommandBlockEntity)level.getBlockEntity(new BlockPos(21, 100, 0))).saveWithFullMetadata(level.registryAccess()).equals(oldEntity), "rollback restores full entity NBT");
        var first = place(24, "say first"); first.put("facing", "east");
        var second = place(25, "say second"); second.put("type", "chain"); second.put("facing", "east"); second.put("automatic", true);
        var third = place(26, "say third"); third.put("type", "chain"); third.put("automatic", true);
        tools.call("batch_place_command_blocks", Map.of("blocks", List.of(first, second, third)));
        var single = tools.call("execute_command_block", pos(24, 100, 0)); check(single.get("status").equals("executed"), "single executes");
        check(tools.call("get_command_block", pos(25, 100, 0)).get("successCount").equals(0), "single does not trigger chain");
        check(tools.call("execute_command_block", pos(24, 100, 0)).get("status").equals("skipped"), "same tick duplicate skipped");
        var chainStart = pos(25, 100, 0); chainStart.put("mode", "chain");
        check(((List<?>)tools.call("execute_command_block", chainStart).get("blocks")).size() == 2, "chain executes successors");
        var conditional = place(28, "say condition"); conditional.put("conditional", true); tools.call("place_command_block", conditional);
        check(tools.call("execute_command_block", pos(28, 100, 0)).get("status").equals("skipped"), "failed condition skipped");
        var conditionFollower = pos(28, 100, -1); conditionFollower.put("command", "say should not run"); conditionFollower.put("type", "chain"); conditionFollower.put("automatic", true); conditionFollower.put("replace", true);
        tools.call("place_command_block", conditionFollower);
        var skippedChain = pos(28, 100, 0); skippedChain.put("mode", "chain");
        check(((List<?>)tools.call("execute_command_block", skippedChain).get("blocks")).size() == 1, "conditional start stops chain");
        check(tools.call("get_command_block", pos(28, 100, -1)).get("successCount").equals(0), "skipped start leaves successor unexecuted");
        var destroysSelf = place(23, "setblock ~ ~ ~ air"); tools.call("place_command_block", destroysSelf);
        check(tools.call("execute_command_block", pos(23, 100, 0)).get("status").equals("executed"), "command removing its own block still returns outcome");
        level.getGameRules().set(GameRules.MAX_COMMAND_SEQUENCE_LENGTH, 0, server);
        // same tick guard has already fired for 26; use a fresh start.
        var fresh = place(27, "say limit"); fresh.put("facing", "west"); tools.call("place_command_block", fresh);
        var limit = pos(27, 100, 0); limit.put("mode", "chain"); check(((List<?>)tools.call("execute_command_block", limit).get("blocks")).size() == 1, "zero chain limit");
        level.getGameRules().set(GameRules.MAX_COMMAND_SEQUENCE_LENGTH, 65536, server);
        level.getGameRules().set(GameRules.COMMAND_BLOCKS_WORK, false, server);
        rejected(() -> tools.call("execute_command_block", pos(20, 100, 0)), "vanilla disabled execution");
        level.getGameRules().set(GameRules.COMMAND_BLOCKS_WORK, true, server);
        verifyReadOnlyTools(server, tools, edge, nextChunk);
        tools.call("remove_command_block", pos(20, 100, 0)); check(level.getBlockState(new BlockPos(20, 100, 0)).isAir(), "remove replaces air");
        rejected(() -> tools.call("update_command_block", pos(20, 100, 0)), "update requires existing");
        var saved = place(30, "say persistence"); saved.put("type", "chain"); saved.put("facing", "up"); saved.put("name", "saved"); saved.put("trackOutput", false); tools.call("place_command_block", saved);
        try { Files.writeString(Path.of("restore-marker.txt"), "ready"); } catch (Exception e) { throw new RuntimeException(e); }
        server.saveEverything(false, true, true);
    }
    private static Map<String, Object> area(int x, int y, int z, int endX, int endY, int endZ) {
        Map<String, Object> args = pos(x, y, z); args.putAll(Map.of("endX", endX, "endY", endY, "endZ", endZ)); return args;
    }
    private static Map<String, Object> command(int x, int y, int z, String text) {
        Map<String, Object> args = pos(x, y, z); args.put("command", text); return args;
    }
    @SuppressWarnings("unchecked")
    private void verifyReadOnlyTools(MinecraftServer server, WorldTools tools, BlockPos edge, BlockPos nextChunk) {
        var level = server.overworld();
        for (BlockPos p : BlockPos.betweenClosed(0, 140, 4, 12, 140, 8)) level.setBlock(p, Blocks.AIR.defaultBlockState(), 16);
        var region = area(4, 140, 4, 6, 140, 4);
        var empty = tools.call("query_area", region);
        check(empty.get("clear").equals(true) && empty.get("allAir").equals(true) && empty.get("noEntities").equals(true), "air is clear");
        check(empty.get("volume").equals(3L) && empty.get("blockCount").equals(0) && empty.get("entityCount").equals(0), "inclusive area counts");
        level.setBlock(new BlockPos(4, 140, 4), Blocks.STONE.defaultBlockState(), 16);
        check(tools.call("query_area", region).get("allAir").equals(false), "stone obstructs clearance");
        level.setBlock(new BlockPos(5, 140, 4), Blocks.WATER.defaultBlockState(), 16);
        level.setBlock(new BlockPos(6, 140, 4), Blocks.SHORT_GRASS.defaultBlockState(), 16);
        var occupied = tools.call("query_area", region);
        var details = (List<Map<String, Object>>)occupied.get("blocks");
        check(occupied.get("blockCount").equals(3) && occupied.get("clear").equals(false), "water and plants count as non-air");
        check(details.stream().map(b -> b.get("id")).toList().equals(List.of("minecraft:stone", "minecraft:water", "minecraft:short_grass")), "registered block IDs and order");
        check(((Map<?, ?>)details.get(1).get("properties")).get("level").equals("0"), "block state properties");
        check(tools.call("query_area", area(6, 140, 4, 4, 140, 4)).equals(occupied), "reversed endpoints normalize");
        region.put("maxBlocks", 1);
        var truncated = tools.call("query_area", region);
        check(truncated.get("blockCount").equals(3) && ((List<?>)truncated.get("blocks")).size() == 1 && truncated.get("blocksTruncated").equals(true) && truncated.get("clear").equals(false), "block detail truncation preserves totals and clearance");
        var pig = net.minecraft.world.entity.EntityTypes.PIG.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        check(pig != null, "entity fixture created");
        pig.setPos(7.1, 140, 4.5); pig.setCustomName(net.minecraft.network.chat.Component.literal("Area pig"));
        check(level.addFreshEntity(pig), "entity fixture loaded");
        var player = new RecordingPlayer(server, "AreaPlayer"); player.spectator = true; player.setPos(5.5, 140, 4.5); level.players().add(player);
        try {
            var withEntities = tools.call("query_area", region);
            var entityDetails = (List<Map<String, Object>>)withEntities.get("entities");
            check(withEntities.get("entityCount").equals(2) && withEntities.get("noEntities").equals(false), "entities and spectator player included");
            var pigDetail = entityDetails.stream().filter(e -> e.get("uuid").equals(pig.getUUID().toString())).findFirst().orElseThrow();
            check(pigDetail.get("type").equals("minecraft:pig") && pigDetail.get("name").equals("Area pig") && pigDetail.get("x").equals(7.1), "entity UUID type name and position");
            check(((Number)((Map<?, ?>)pigDetail.get("boundingBox")).get("minX")).doubleValue() < 7, "outside entity counted by intersecting bounding box");
            check(entityDetails.stream().anyMatch(e -> e.get("uuid").equals(player.getUUID().toString()) && e.get("type").equals("minecraft:player")), "player detail present");
            region.put("maxEntities", 1); var limited = tools.call("query_area", region);
            check(limited.get("entityCount").equals(2) && ((List<?>)limited.get("entities")).size() == 1 && limited.get("entitiesTruncated").equals(true), "entity detail truncation preserves totals");
            for (int x = 4; x <= 6; x++) level.setBlock(new BlockPos(x, 140, 4), Blocks.AIR.defaultBlockState(), 16);
            var airWithEntities = tools.call("query_area", region);
            check(airWithEntities.get("allAir").equals(true) && airWithEntities.get("clear").equals(false), "entities prevent clearance in air");
            level.players().remove(player);
            pig.setBoundingBox(new net.minecraft.world.phys.AABB(7, 140, 4, 8, 141, 5));
            check(tools.call("query_area", region).get("clear").equals(true), "box touching without overlap excluded");
            pig.setPos(12, 140, 4.5); pig.setBoundingBox(new net.minecraft.world.phys.AABB(6.5, 140, 4, 12.5, 141, 5));
            check(tools.call("query_area", region).get("entityCount").equals(1), "oversized box intersection counted");
        } finally { level.players().remove(player); pig.discard(); }
        rejected(() -> tools.call("query_area", area(4, 140, 4, 4, level.getMaxY() + 1, 4)), "area height checked");
        rejected(() -> tools.call("query_area", area(29999999, 140, 0, 30000000, 140, 0)), "area world boundary checked");
        rejected(() -> tools.call("query_area", area(edge.getX(), 100, edge.getZ(), nextChunk.getX(), 100, nextChunk.getZ())), "partial unloaded area rejected");
        level.getChunk(6252, 6250);
        rejected(() -> tools.call("query_area", area(edge.getX(), 100, edge.getZ(), 100032, 100, edge.getZ())), "unloaded middle chunk rejected with loaded endpoints");
        check(!level.hasChunkAt(nextChunk), "area query does not load chunks");

        List<Map<String, Object>> batch = new ArrayList<>();
        for (String text : List.of("say syntax", "/say slash", "setblock ~ ~ ~ stone", "execute as @e[type=minecraft:pig,name=NoSuchTarget] run say parsed", "", "/", "time query gametime", "unknown_command", "setblock", "time query gametime extra", "execute as @e run", "/unknown_command", "stop", "give @p minecraft:stone")) batch.add(command(4, 140, 4, text));
        var beforeState = level.getBlockState(new BlockPos(4, 140, 4));
        var beforeHistory = ((CommandBlockEntity)level.getBlockEntity(new BlockPos(25, 100, 0))).saveWithFullMetadata(level.registryAccess());
        var invalidDimension = command(4, 140, 4, "say invalid dimension"); invalidDimension.put("dimension", "minecraft:missing"); batch.add(invalidDimension);
        batch.add(command(nextChunk.getX(), 100, nextChunk.getZ(), "say unloaded")); batch.add(command(4, 140, 4, "say after failures"));
        var precheck = tools.call("precheck_commands", Map.of("commands", batch));
        var parsed = (List<Map<String, Object>>)precheck.get("commands");
        for (int i = 0; i < 7; i++) check(parsed.get(i).get("valid").equals(true), "valid syntax " + i + ": " + parsed.get(i));
        for (int i = 7; i <= 12; i++) check(parsed.get(i).get("valid").equals(false) && parsed.get(i).get("error") != null && parsed.get(i).get("cursor") instanceof Integer && parsed.get(i).get("context") != null, "invalid syntax has diagnostics " + i);
        check(parsed.get(11).get("cursor").equals(1), "diagnostic cursor includes leading slash");
        check(parsed.get(13).get("valid").equals(true), "selector target not resolved");
        check(parsed.get(14).get("valid").equals(false) && parsed.get(15).get("valid").equals(false) && parsed.get(16).get("valid").equals(true), "position errors do not abort batch");
        check(precheck.get("status").equals("failed") && precheck.get("invalidCount").equals(8) && precheck.get("count").equals(batch.size()), "mixed batch failure summary");
        check(level.getBlockState(new BlockPos(4, 140, 4)).equals(beforeState), "setblock precheck never executes");
        check(((CommandBlockEntity)level.getBlockEntity(new BlockPos(25, 100, 0))).saveWithFullMetadata(level.registryAccess()).equals(beforeHistory), "precheck leaves execution history intact");
        check(!level.hasChunkAt(nextChunk), "precheck does not load chunks");
        var nether = server.getLevel(net.minecraft.world.level.Level.NETHER); nether.getChunk(0, 0);
        var netherCommand = command(0, 100, 0, "setblock ~ ~ ~ minecraft:stone"); netherCommand.put("dimension", "minecraft:the_nether");
        check(tools.call("precheck_commands", Map.of("commands", List.of(netherCommand))).get("status").equals("ok"), "precheck supports explicit other dimension");
        var unparsedPlacement = command(8, 140, 8, "unknown_command accepted"); tools.call("place_command_block", unparsedPlacement);
        check(tools.call("get_command_block", pos(8, 140, 8)).get("command").equals("unknown_command accepted"), "placement syntax acceptance unchanged");

        List<Map<String, Object>> chain = new ArrayList<>();
        int[][] positions = {{0, 140, 4}, {1, 140, 4}, {1, 140, 5}, {0, 140, 5}};
        String[] facings = {"east", "south", "west", "north"};
        for (int i = 0; i < positions.length; i++) {
            int[] p = positions[i]; var item = command(p[0], p[1], p[2], "say read only");
            item.put("type", i == 0 ? "impulse" : "chain"); item.put("facing", facings[i]); item.put("conditional", true); chain.add(item);
        }
        tools.call("batch_place_command_blocks", Map.of("blocks", chain));
        var snapshots = chain.stream().map(item -> ((CommandBlockEntity)level.getBlockEntity(new BlockPos((int)item.get("x"), (int)item.get("y"), (int)item.get("z")))).saveWithFullMetadata(level.registryAccess())).toList();
        level.getGameRules().set(GameRules.MAX_COMMAND_SEQUENCE_LENGTH, 0, server); level.getGameRules().set(GameRules.COMMAND_BLOCKS_WORK, false, server);
        var readChain = tools.call("get_command_chain", pos(0, 140, 4));
        check(readChain.get("count").equals(4) && readChain.get("complete").equals(true) && readChain.get("stopReason").equals("end_of_chain"), "turning chain ignores power conditions and game rules");
        var readBlocks = (List<Map<String, Object>>)readChain.get("blocks");
        for (int i = 0; i < chain.size(); i++) {
            check(readBlocks.get(i).equals(tools.call("get_command_block", pos(positions[i][0], positions[i][1], positions[i][2]))), "chain reuses single-block fields " + i);
            check(((CommandBlockEntity)level.getBlockEntity(new BlockPos(positions[i][0], positions[i][1], positions[i][2]))).saveWithFullMetadata(level.registryAccess()).equals(snapshots.get(i)), "chain leaves full NBT unchanged " + i);
        }
        var capped = pos(0, 140, 4); capped.put("maxBlocks", 2); var capResult = tools.call("get_command_chain", capped);
        check(capResult.get("count").equals(2) && capResult.get("complete").equals(false) && capResult.get("stopReason").equals("max_blocks"), "chain count limit");
        capped.put("maxBlocks", 4); check(tools.call("get_command_chain", capped).get("complete").equals(true), "exact length at cap still natural tail");
        var loopPatch = pos(0, 140, 4); loopPatch.put("type", "chain"); tools.call("update_command_block", loopPatch);
        var loop = tools.call("get_command_chain", pos(0, 140, 4));
        check(loop.get("count").equals(4) && loop.get("complete").equals(false) && loop.get("stopReason").equals("loop"), "loop detected without duplicate output");
        check(tools.call("get_command_chain", pos(edge.getX(), 100, edge.getZ())).get("stopReason").equals("unloaded_chunk"), "chain read stops at unloaded chunk");
        check(!level.hasChunkAt(nextChunk), "chain read does not load chunks");
        var top = command(4, level.getMaxY(), 4, "say top"); top.put("facing", "up"); top.put("replace", true); tools.call("place_command_block", top);
        var boundary = tools.call("get_command_chain", pos(4, level.getMaxY(), 4));
        check(boundary.get("count").equals(1) && boundary.get("complete").equals(false) && boundary.get("stopReason").equals("boundary"), "chain height boundary");
        double borderSize = level.getWorldBorder().getSize();
        try {
            level.getWorldBorder().setSize(2);
            var border = command(0, 140, 0, "say border"); border.put("facing", "east"); border.put("replace", true); tools.call("place_command_block", border);
            check(tools.call("get_command_chain", pos(0, 140, 0)).get("stopReason").equals("boundary"), "chain world border");
            rejected(() -> tools.call("query_area", area(0, 140, 0, 1, 140, 0)), "area entire border extent checked");
        } finally { level.getWorldBorder().setSize(borderSize); }
        rejected(() -> tools.call("get_command_chain", pos(4, 140, 4)), "chain start must be command block");
        level.getGameRules().set(GameRules.MAX_COMMAND_SEQUENCE_LENGTH, 65536, server); level.getGameRules().set(GameRules.COMMAND_BLOCKS_WORK, true, server);
        // Existing straight execution fixture also reads as a straight chain.
        check(tools.call("get_command_chain", pos(24, 100, 0)).get("count").equals(3), "straight chain read");
    }
    private void verifyHttp() throws Exception {
        var config = McpConfig.load(Path.of("config/commandblock_mcp.json"));
        var transport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + config.port).endpoint("/mcp")
            .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + config.token)).build();
        var client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(15)).build();
        try {
            client.initialize(); check(client.listTools().tools().size() == 10, "Fabric SDK discovery");
            var result = client.callTool(new McpSchema.CallToolRequest("get_world_context", Map.of()));
            check(!result.isError() && result.structuredContent() instanceof Map<?, ?>, "Fabric world dispatch");
            var bad = client.callTool(new McpSchema.CallToolRequest("get_command_block", pos(1000000, 100, 0)));
            check(bad.isError(), "business failure isError");
            var area = client.callTool(new McpSchema.CallToolRequest("query_area", area(4, 140, 4, 6, 140, 4)));
            check(!area.isError() && ((Map<?, ?>)area.structuredContent()).get("clear").equals(true), "HTTP area dispatch: " + area);
            var mixed = client.callTool(new McpSchema.CallToolRequest("precheck_commands", Map.of("commands", List.of(command(4, 140, 4, "setblock ~ ~ ~ stone"), command(4, 140, 4, "unknown_command")))));
            check(mixed.isError() && ((List<?>)((Map<?, ?>)mixed.structuredContent()).get("commands")).size() == 2, "HTTP precheck partial failure sets isError");
            var valid = client.callTool(new McpSchema.CallToolRequest("precheck_commands", Map.of("commands", List.of(command(4, 140, 4, "")))));
            check(!valid.isError(), "HTTP empty precheck success");
            var chain = client.callTool(new McpSchema.CallToolRequest("get_command_chain", pos(0, 140, 4)));
            check(!chain.isError() && ((Map<?, ?>)chain.structuredContent()).get("stopReason").equals("loop"), "HTTP chain dispatch");
            var unavailable = client.callTool(new McpSchema.CallToolRequest("query_area", area(100015, 100, 100000, 100016, 100, 100000)));
            check(unavailable.isError() && !((Map<?, ?>)unavailable.structuredContent()).containsKey("clear"), "failed area supplies no clearance conclusion");
        } finally { client.closeGracefully(); }
    }
    private void verifySubscriptions(MinecraftServer server) {
        var first = new RecordingPlayer(server, "Subscriber"); var second = new RecordingPlayer(server, "Observer");
        var dispatcher = server.getCommands().getDispatcher();
        try {
            // Ordinary players can subscribe, but cannot change administrator settings.
            var ordinary = first.createCommandSourceStack().withPermission(permission -> false);
            dispatcher.execute("cbmcp verbose on", ordinary);
            boolean rejectedAdmin = false;
            try { dispatcher.execute("cbmcp disable", ordinary); } catch (com.mojang.brigadier.exceptions.CommandSyntaxException expected) { rejectedAdmin = true; }
            check(rejectedAdmin, "ordinary player cannot disable MCP");
            Object mod = FabricLoader.getInstance().getEntrypoints("main", ModInitializer.class).stream().filter(m -> m instanceof CommandBlock_MCP).findFirst().orElseThrow();
            var broadcast = CommandBlock_MCP.class.getDeclaredMethod("broadcast", MinecraftServer.class, String.class, Map.class, McpSchema.CallToolResult.class); broadcast.setAccessible(true);
            server.getPlayerList().getPlayers().add(first); server.getPlayerList().getPlayers().add(second);
            var result = McpSchema.CallToolResult.builder().structuredContent(Map.of("status", "failed", "reason", "test failure")).addTextContent("test failure").isError(true).build();
            first.messages.clear(); second.messages.clear();
            broadcast.invoke(mod, server, "get_command_block", pos(0, 100, 0), result);
            check(first.messages.size() == 1 && second.messages.isEmpty(), "verbose subscriptions are independent");
            check(first.messages.getFirst().contains("minecraft:overworld") && first.messages.getFirst().contains("test failure"), "verbose contains target and failure reason");
            first.messages.clear();
            var commands = List.of(command(4, 140, 4, "say parsed"), command(5, 140, 4, "invalid"));
            var failedItem = new HashMap<>(commands.get(1)); failedItem.put("status", "failed"); failedItem.put("reason", "syntax failure");
            var batchResult = McpSchema.CallToolResult.builder().structuredContent(Map.of("status", "failed", "commands", List.of(commands.get(0), failedItem))).addTextContent("batch").isError(true).build();
            broadcast.invoke(mod, server, "precheck_commands", Map.of("commands", commands), batchResult);
            check(first.messages.size() == 1 && first.messages.getFirst().contains("batch 2 targets"), "precheck verbose batch summary");
            var hover = (net.minecraft.network.chat.HoverEvent.ShowText)first.lastMessage.getStyle().getHoverEvent();
            check(hover.value().getString().contains("syntax failure") && hover.value().getString().contains("[5, 140, 4]"), "precheck verbose hover includes item location and failure");
            dispatcher.execute("cbmcp verbose off", ordinary); first.messages.clear();
            broadcast.invoke(mod, server, "get_command_block", pos(0, 100, 0), result);
            check(first.messages.isEmpty() && second.messages.isEmpty(), "verbose off unsubscribes");
        } catch (Exception e) { throw new RuntimeException(e); }
        finally { server.getPlayerList().getPlayers().remove(first); server.getPlayerList().getPlayers().remove(second); }
    }
    private static final class RecordingPlayer extends net.minecraft.server.level.ServerPlayer {
        final List<String> messages = new ArrayList<>();
        boolean spectator;
        net.minecraft.network.chat.Component lastMessage;
        RecordingPlayer(MinecraftServer server, String name) { super(server, server.overworld(), new com.mojang.authlib.GameProfile(UUID.randomUUID(), name), net.minecraft.server.level.ClientInformation.createDefault()); }
        @Override public void sendSystemMessage(net.minecraft.network.chat.Component message) { lastMessage = message; messages.add(message.getString()); }
        @Override public void sendSystemMessage(net.minecraft.network.chat.Component message, boolean overlay) { messages.add(message.getString()); }
        @Override public boolean isSpectator() { return spectator; }
    }
}
