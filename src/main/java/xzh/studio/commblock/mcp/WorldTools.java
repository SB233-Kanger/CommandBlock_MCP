package xzh.studio.commblock.mcp;

import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.Commands;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BaseCommandBlock;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import java.util.*;

/** All methods are called exclusively on the bound Minecraft server thread. */
public final class WorldTools {
    private final MinecraftServer server;
    private final McpConfig config;
    public WorldTools(MinecraftServer server, McpConfig config) { this.server = server; this.config = config; }
    public boolean allowed() { return config.enabled && (!server.isSingleplayer() || server.getWorldData().isAllowCommands()); }

    @SuppressWarnings("unchecked")
    public Map<String, Object> call(String name, Map<String, Object> args) {
        ToolSchemas.validate(name, args);
        if (!allowed()) throw new IllegalStateException("MCP disabled or singleplayer cheats not enabled");
        if (name.equals("get_world_context")) return context();
        if (name.equals("batch_place_command_blocks")) return place((List<Map<String, Object>>)args.get("blocks"));
        if (name.equals("precheck_commands")) return precheck((List<Map<String, Object>>)args.get("commands"));
        if (name.equals("query_area")) return queryArea(args);
        Target target = target(args);
        return switch (name) {
            case "get_command_block" -> read(target);
            case "get_command_chain" -> readChain(target, ((Number)args.getOrDefault("maxBlocks", 4096)).intValue());
            case "place_command_block" -> place(List.of(args));
            case "update_command_block" -> update(target, args);
            case "remove_command_block" -> {
                commandEntity(target);
                Block source = target.level.getBlockState(target.pos).getBlock();
                if (!target.level.setBlock(target.pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE)) throw new IllegalStateException("Block removal failed");
                notifyNeighbors(target, source); notifyComparators(target, source);
                yield result(target, "removed");
            }
            case "execute_command_block" -> execute(target, (String)args.getOrDefault("mode", "single"));
            default -> throw new IllegalArgumentException("Unknown tool");
        };
    }

    private Map<String, Object> context() {
        List<Object> dimensions = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) dimensions.add(Map.of("id", level.dimension().identifier().toString(), "minY", level.getMinY(), "maxY", level.getMaxY(), "commandBlocksEnabled", level.isCommandBlockEnabled()));
        List<Object> players = new ArrayList<>();
        server.getPlayerList().getPlayers().forEach(player -> players.add(Map.of("uuid", player.getUUID().toString(), "name", player.getName().getString(), "dimension", player.level().dimension().identifier().toString(), "x", player.getX(), "y", player.getY(), "z", player.getZ())));
        return Map.of("status", "ok", "dimensions", dimensions, "players", players, "mcpEnabled", config.enabled, "singleplayer", server.isSingleplayer(), "cheatsEnabled", server.getWorldData().isAllowCommands());
    }
    private ServerLevel level(Map<String, Object> args) {
        Identifier id = Identifier.tryParse((String)args.get("dimension"));
        if (id == null) throw new IllegalArgumentException("Invalid dimension ID");
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
        if (level == null) throw new IllegalArgumentException("Unknown dimension: " + id);
        return level;
    }
    private Target target(Map<String, Object> args) {
        ServerLevel level = level(args);
        BlockPos pos = new BlockPos(((Number)args.get("x")).intValue(), ((Number)args.get("y")).intValue(), ((Number)args.get("z")).intValue());
        validatePosition(level, pos);
        if (!level.hasChunkAt(pos)) throw new IllegalArgumentException("Chunk is not loaded; MCP does not load chunks");
        return new Target(level, pos);
    }
    private void validatePosition(ServerLevel level, BlockPos pos) {
        if (level.isOutsideBuildHeight(pos)) throw new IllegalArgumentException("Outside build height");
        if (!withinBoundary(level, pos)) throw new IllegalArgumentException("Outside world boundary");
    }
    private boolean withinBoundary(ServerLevel level, BlockPos pos) {
        return !level.isOutsideBuildHeight(pos) && level.getWorldBorder().isWithinBounds(pos) && Math.abs((long)pos.getX()) < 30000000 && Math.abs((long)pos.getZ()) < 30000000;
    }
    private Map<String, Object> queryArea(Map<String, Object> args) {
        ServerLevel level = level(args);
        int minX = Math.min(((Number)args.get("x")).intValue(), ((Number)args.get("endX")).intValue());
        int minY = Math.min(((Number)args.get("y")).intValue(), ((Number)args.get("endY")).intValue());
        int minZ = Math.min(((Number)args.get("z")).intValue(), ((Number)args.get("endZ")).intValue());
        int maxX = Math.max(((Number)args.get("x")).intValue(), ((Number)args.get("endX")).intValue());
        int maxY = Math.max(((Number)args.get("y")).intValue(), ((Number)args.get("endY")).intValue());
        int maxZ = Math.max(((Number)args.get("z")).intValue(), ((Number)args.get("endZ")).intValue());
        // Validate the full rectangular extent before inspecting blocks or claiming clearance.
        for (int x : new int[]{minX, maxX}) for (int y : new int[]{minY, maxY}) for (int z : new int[]{minZ, maxZ}) validatePosition(level, new BlockPos(x, y, z));
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
            if (!level.hasChunkAt(new BlockPos(cx << 4, minY, cz << 4))) throw new IllegalArgumentException("Chunk is not loaded; MCP does not load chunks");
        }
        int maxBlocks = ((Number)args.getOrDefault("maxBlocks", 256)).intValue(), maxEntities = ((Number)args.getOrDefault("maxEntities", 256)).intValue();
        List<Map<String, Object>> blocks = new ArrayList<>(), entities = new ArrayList<>();
        int blockCount = 0, entityCount = 0;
        for (BlockPos pos : BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ)) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) continue;
            blockCount++;
            if (blocks.size() < maxBlocks) {
                Map<String, Object> entry = result(new Target(level, pos), "ok");
                entry.put("id", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
                Map<String, String> properties = new LinkedHashMap<>();
                for (Property<?> property : state.getProperties()) properties.put(property.getName(), propertyValue(state, property));
                entry.put("properties", properties); blocks.add(entry);
            }
        }
        AABB box = new AABB(minX, minY, minZ, (double)maxX + 1, (double)maxY + 1, (double)maxZ + 1);
        // Scan loaded entities directly so spectator players and oversized intersecting boxes count.
        Set<UUID> seen = new HashSet<>(); List<Entity> candidates = new ArrayList<>();
        level.getAllEntities().forEach(candidates::add); candidates.addAll(level.players());
        for (Entity entity : candidates) {
            if (entity.isRemoved() || !seen.add(entity.getUUID()) || !entity.getBoundingBox().intersects(box)) continue;
            entityCount++;
            if (entities.size() < maxEntities) entities.add(Map.of("uuid", entity.getUUID().toString(), "type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
                "name", entity.getName().getString(), "x", entity.getX(), "y", entity.getY(), "z", entity.getZ(), "boundingBox", bounds(entity.getBoundingBox())));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok"); out.put("dimension", level.dimension().identifier().toString());
        out.put("bounds", Map.of("x", minX, "y", minY, "z", minZ, "endX", maxX, "endY", maxY, "endZ", maxZ)); out.put("volume", ToolSchemas.areaVolume(args));
        out.put("allAir", blockCount == 0); out.put("noEntities", entityCount == 0); out.put("clear", blockCount == 0 && entityCount == 0);
        out.put("blockCount", blockCount); out.put("entityCount", entityCount); out.put("blocks", blocks); out.put("entities", entities);
        out.put("blocksTruncated", blockCount > blocks.size()); out.put("entitiesTruncated", entityCount > entities.size());
        return out;
    }
    private static <T extends Comparable<T>> String propertyValue(BlockState state, Property<T> property) { return property.getName(state.getValue(property)); }
    private static Map<String, Object> bounds(AABB box) {
        return Map.of("minX", box.minX, "minY", box.minY, "minZ", box.minZ, "maxX", box.maxX, "maxY", box.maxY, "maxZ", box.maxZ);
    }
    private Map<String, Object> precheck(List<Map<String, Object>> commands) {
        List<Map<String, Object>> results = new ArrayList<>(); int invalid = 0;
        for (int i = 0; i < commands.size(); i++) {
            Map<String, Object> item = commands.get(i), out = new LinkedHashMap<>(item); out.put("index", i);
            out.put("valid", true); out.put("status", "ok"); out.put("error", null); out.put("cursor", null); out.put("context", null);
            try {
                Target target = target(item);
                // This entity is never attached to the world. Reuse vanilla block position and permissions.
                var source = new CommandBlockEntity(target.pos, Blocks.COMMAND_BLOCK.defaultBlockState()).getCommandBlock().createCommandSourceStack(target.level, CommandSource.NULL);
                StringReader reader = new StringReader((String)item.get("command"));
                if (reader.canRead() && reader.peek() == '/') reader.skip();
                if (reader.canRead()) {
                    var parsed = server.getCommands().getDispatcher().parse(reader, source);
                    Commands.validateParseResults(parsed);
                    if (parsed.getContext().getLastChild().getCommand() == null) throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownCommand().createWithContext(parsed.getReader());
                }
            } catch (CommandSyntaxException e) {
                out.put("valid", false); out.put("status", "failed"); out.put("error", e.getRawMessage().getString()); out.put("cursor", e.getCursor()); out.put("context", e.getContext());
            } catch (RuntimeException e) {
                out.put("valid", false); out.put("status", "failed"); out.put("error", e.getMessage());
            }
            if (Boolean.FALSE.equals(out.get("valid"))) { invalid++; out.put("reason", out.get("error")); }
            results.add(out);
        }
        return Map.of("status", invalid == 0 ? "ok" : "failed", "count", results.size(), "invalidCount", invalid, "commands", results);
    }
    private Map<String, Object> readChain(Target start, int limit) {
        commandEntity(start);
        List<Map<String, Object>> blocks = new ArrayList<>(); Set<BlockPos> visited = new HashSet<>();
        BlockPos pos = start.pos; String stop;
        while (true) {
            visited.add(pos); blocks.add(read(new Target(start.level, pos)));
            BlockPos next = pos.relative(start.level.getBlockState(pos).getValue(CommandBlock.FACING));
            if (!withinBoundary(start.level, next)) { stop = "boundary"; break; }
            if (!start.level.hasChunkAt(next)) { stop = "unloaded_chunk"; break; }
            if (!start.level.getBlockState(next).is(Blocks.CHAIN_COMMAND_BLOCK) || !(start.level.getBlockEntity(next) instanceof CommandBlockEntity)) { stop = "end_of_chain"; break; }
            if (visited.contains(next)) { stop = "loop"; break; }
            if (blocks.size() >= limit) { stop = "max_blocks"; break; }
            pos = next;
        }
        Map<String, Object> out = result(start, "ok");
        out.put("blocks", blocks); out.put("count", blocks.size()); out.put("complete", stop.equals("end_of_chain")); out.put("stopReason", stop);
        return out;
    }
    private CommandBlockEntity commandEntity(Target target) {
        if (!(target.level.getBlockState(target.pos).getBlock() instanceof CommandBlock) || !(target.level.getBlockEntity(target.pos) instanceof CommandBlockEntity entity)) throw new IllegalArgumentException("Target is not a command block");
        return entity;
    }
    private Map<String, Object> read(Target target) {
        CommandBlockEntity entity = commandEntity(target);
        BaseCommandBlock command = entity.getCommandBlock();
        Map<String, Object> out = result(target, "ok");
        out.put("command", command.getCommand());
        out.put("type", type(target.level.getBlockState(target.pos)));
        out.put("facing", target.level.getBlockState(target.pos).getValue(CommandBlock.FACING).getName());
        out.put("conditional", entity.isConditional()); out.put("automatic", entity.isAutomatic());
        out.put("powered", entity.isPowered()); out.put("trackOutput", command.isTrackOutput());
        out.put("name", command.getName().getString()); out.put("successCount", command.getSuccessCount());
        out.put("lastOutput", command.getLastOutput() == null ? null : command.getLastOutput().getString());
        return out;
    }
    private static String type(BlockState state) {
        return state.is(Blocks.CHAIN_COMMAND_BLOCK) ? "chain" : state.is(Blocks.REPEATING_COMMAND_BLOCK) ? "repeating" : "impulse";
    }
    private static Block block(String type) {
        return switch (type) { case "chain" -> Blocks.CHAIN_COMMAND_BLOCK; case "repeating" -> Blocks.REPEATING_COMMAND_BLOCK; default -> Blocks.COMMAND_BLOCK; };
    }
    private BlockState state(Map<String, Object> args, BlockState old) {
        return block((String)args.getOrDefault("type", old == null ? "impulse" : type(old))).defaultBlockState()
            .setValue(CommandBlock.FACING, Direction.valueOf(((String)args.getOrDefault("facing", old == null ? "north" : old.getValue(CommandBlock.FACING).getName())).toUpperCase(Locale.ROOT)))
            .setValue(CommandBlock.CONDITIONAL, (boolean)args.getOrDefault("conditional", old != null && old.getValue(CommandBlock.CONDITIONAL)));
    }
    private void configure(Target target, Map<String, Object> args, boolean fresh) {
        CommandBlockEntity entity = commandEntity(target);
        BaseCommandBlock command = entity.getCommandBlock();
        if (args.containsKey("command")) command.setCommand((String)args.get("command"));
        if (fresh || args.containsKey("trackOutput")) command.setTrackOutput((boolean)args.getOrDefault("trackOutput", true));
        if (args.containsKey("name")) command.setCustomName(Component.literal((String)args.get("name")));
        entity.setChanged();
    }
    private void notifyTarget(Target target) {
        CommandBlockEntity entity = commandEntity(target);
        entity.setChanged();
        BlockState state = target.level.getBlockState(target.pos);
        entity.getCommandBlock().onUpdated(target.level);
        target.level.sendBlockUpdated(target.pos, state, state, 3);
        notifyNeighbors(target, state.getBlock());
        notifyComparators(target, state.getBlock());
    }
    private void notifyNeighbors(Target target, Block source) {
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = target.pos.relative(direction);
            if (target.level.hasChunkAt(neighbor)) target.level.neighborChanged(neighbor, source, null);
        }
    }
    private void notifyComparators(Target target, Block source) {
        // Vanilla's comparator scan does not check the second block's chunk; add that guard.
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos pos = target.pos.relative(direction);
            if (!target.level.hasChunkAt(pos)) continue;
            BlockState state = target.level.getBlockState(pos);
            if (!state.is(Blocks.COMPARATOR) && state.isRedstoneConductor(target.level, pos)) {
                pos = pos.relative(direction);
                if (!target.level.hasChunkAt(pos)) continue;
                state = target.level.getBlockState(pos);
            }
            if (state.is(Blocks.COMPARATOR)) target.level.neighborChanged(state, pos, source, null, false);
        }
    }
    private Snapshot snapshot(Target target) {
        BlockEntity entity = target.level.getBlockEntity(target.pos);
        return new Snapshot(target, target.level.getBlockState(target.pos), entity == null ? null : entity.saveWithFullMetadata(target.level.registryAccess()));
    }
    private void restore(Snapshot snapshot) {
        Target target = snapshot.target;
        target.level.removeBlockEntity(target.pos);
        target.level.setBlock(target.pos, snapshot.state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        if (snapshot.nbt != null) {
            BlockEntity entity = BlockEntity.loadStatic(target.pos, snapshot.state, snapshot.nbt, target.level.registryAccess());
            if (entity == null) throw new IllegalStateException("Could not restore block entity");
            target.level.setBlockEntity(entity); entity.setChanged();
        }
        target.level.sendBlockUpdated(target.pos, snapshot.state, snapshot.state, 3);
    }
    private void rollback(List<Snapshot> snapshots, RuntimeException cause) {
        for (int i = snapshots.size() - 1; i >= 0; i--) {
            try { restore(snapshots.get(i)); } catch (RuntimeException e) { cause.addSuppressed(e); }
        }
        if (cause.getSuppressed().length > 0) throw new IllegalStateException("Write failed; rollback incomplete, inspect world", cause);
    }
    private Map<String, Object> place(List<Map<String, Object>> items) {
        List<Target> targets = new ArrayList<>(); List<Snapshot> snapshots = new ArrayList<>(); Set<Target> unique = new HashSet<>();
        for (Map<String, Object> item : items) {
            Target target;
            try {
                target = target(item);
                if (!unique.add(target)) throw new IllegalArgumentException("Duplicate batch coordinate");
                if (!(boolean)item.getOrDefault("replace", false) && !target.level.getBlockState(target.pos).isAir()) throw new IllegalArgumentException("Target is occupied; use replace=true");
            } catch (RuntimeException e) { throw new IllegalArgumentException(location(item) + ": " + e.getMessage(), e); }
            targets.add(target); snapshots.add(snapshot(target));
        }
        List<Snapshot> modified = new ArrayList<>();
        try {
            for (int i = 0; i < items.size(); i++) {
                Target target = targets.get(i); modified.add(snapshots.get(i));
                // Clear even a pre-existing command entity, so fresh placement uses fresh defaults.
                target.level.removeBlockEntity(target.pos);
                BlockState state = state(items.get(i), null);
                target.level.setBlock(target.pos, state, Block.UPDATE_KNOWN_SHAPE);
                target.level.setBlockEntity(new CommandBlockEntity(target.pos, state));
                configure(target, items.get(i), true);
            }
            for (int i = 0; i < items.size(); i++) commandEntity(targets.get(i)).setAutomatic((boolean)items.get(i).getOrDefault("automatic", false));
            for (Target target : targets) notifyTarget(target);
            return Map.of("status", "placed", "count", targets.size(), "blocks", targets.stream().map(this::read).toList());
        } catch (RuntimeException e) { rollback(modified, e); throw new IllegalStateException("Batch write failed and restored original targets: " + e.getMessage(), e); }
    }
    private Map<String, Object> update(Target target, Map<String, Object> args) {
        CommandBlockEntity old = commandEntity(target); Snapshot saved = snapshot(target); boolean automatic = old.isAutomatic();
        try {
            BlockState desired = state(args, saved.state);
            if (!desired.equals(saved.state)) {
                old.setAutomatic(false);
                target.level.setBlock(target.pos, desired, Block.UPDATE_KNOWN_SHAPE);
                // Restore all command entity fields (including execution history) before applying the patch.
                BlockEntity restored = BlockEntity.loadStatic(target.pos, desired, saved.nbt, target.level.registryAccess());
                if (!(restored instanceof CommandBlockEntity)) throw new IllegalStateException("Could not retain command configuration");
                target.level.setBlockEntity(restored);
            }
            configure(target, args, false);
            commandEntity(target).setAutomatic((boolean)args.getOrDefault("automatic", automatic));
            if (!desired.equals(saved.state)) commandEntity(target).onModeSwitch();
            notifyTarget(target);
            return read(target);
        } catch (RuntimeException e) { rollback(List.of(saved), e); throw e; }
    }
    private Map<String, Object> run(Target target, boolean activated) {
        CommandBlockEntity entity = commandEntity(target); BaseCommandBlock command = entity.getCommandBlock();
        Map<String, Object> result = read(target);
        String status, reason = "";
        if (!activated) { status = "skipped"; reason = "not_active"; }
        else {
            // markConditionMet reads the previous block: never let it load a neighboring chunk.
            BlockPos previous = target.pos.relative(target.level.getBlockState(target.pos).getValue(CommandBlock.FACING).getOpposite());
            if (entity.isConditional() && !target.level.hasChunkAt(previous)) { status = "skipped"; reason = "condition_chunk_unloaded"; }
            else if (!entity.markConditionMet()) { command.setSuccessCount(0); status = "skipped"; reason = "condition_not_met"; }
            else if (!command.performCommand(target.level)) { status = "skipped"; reason = "already_executed_this_tick"; }
            else if (command.getCommand().isEmpty() || command.getSuccessCount() > 0) status = "executed";
            else { status = "failed"; reason = "command_failed"; }
            entity.setChanged();
            command.onUpdated(target.level);
            notifyComparators(target, target.level.getBlockState(target.pos).getBlock());
        }
        result.put("successCount", command.getSuccessCount());
        result.put("lastOutput", command.getLastOutput() == null ? null : command.getLastOutput().getString());
        result.put("status", status); result.put("reason", reason); return result;
    }
    private Map<String, Object> execute(Target start, String mode) {
        commandEntity(start);
        if (!start.level.isCommandBlockEnabled()) throw new IllegalStateException("Vanilla command blocks are disabled");
        Direction direction = start.level.getBlockState(start.pos).getValue(CommandBlock.FACING);
        List<Map<String, Object>> results = new ArrayList<>(); Map<String, Object> first = run(start, true); results.add(first);
        String stop = mode.equals("chain") && first.get("status").equals("skipped") ? (String)first.get("reason") : "single";
        if (mode.equals("chain") && !first.get("status").equals("skipped")) {
            int limit = Math.max(0, start.level.getGameRules().get(GameRules.MAX_COMMAND_SEQUENCE_LENGTH));
            BlockPos pos = start.pos; stop = "chain_limit";
            for (int i = 0; i < limit; i++) {
                pos = pos.relative(direction);
                if (!start.level.hasChunkAt(pos)) { stop = "unloaded_chunk"; break; }
                if (start.level.isOutsideBuildHeight(pos) || !start.level.getWorldBorder().isWithinBounds(pos)) { stop = "boundary"; break; }
                BlockState state = start.level.getBlockState(pos);
                if (!state.is(Blocks.CHAIN_COMMAND_BLOCK) || !(start.level.getBlockEntity(pos) instanceof CommandBlockEntity entity)) { stop = "end_of_chain"; break; }
                Map<String, Object> result = run(new Target(start.level, pos), entity.isPowered() || entity.isAutomatic()); results.add(result);
                if ("already_executed_this_tick".equals(result.get("reason"))) { stop = "already_executed_this_tick"; break; }
                direction = state.getValue(CommandBlock.FACING);
            }
        }
        return Map.of("status", results.stream().anyMatch(r -> r.get("status").equals("failed")) ? "failed" : first.get("status"), "mode", mode, "stopReason", stop, "blocks", results);
    }
    static String location(Map<String, Object> args) { return args.get("dimension") + " [" + args.get("x") + ", " + args.get("y") + ", " + args.get("z") + "]"; }
    private Map<String, Object> result(Target target, String status) {
        Map<String, Object> result = new LinkedHashMap<>(); result.put("status", status);
        result.put("dimension", target.level.dimension().identifier().toString()); result.put("x", target.pos.getX()); result.put("y", target.pos.getY()); result.put("z", target.pos.getZ()); return result;
    }
    private record Target(ServerLevel level, BlockPos pos) {}
    private record Snapshot(Target target, BlockState state, CompoundTag nbt) {}
}
