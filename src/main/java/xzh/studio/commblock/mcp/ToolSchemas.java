package xzh.studio.commblock.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.*;

public final class ToolSchemas {
    public static final List<String> NAMES = List.of("get_world_context", "get_command_block", "place_command_block", "update_command_block", "remove_command_block", "batch_place_command_blocks", "execute_command_block", "query_area", "precheck_commands", "get_command_chain");
    private static Map<String, Object> field(String type) { return Map.of("type", type); }
    private static Map<String, Object> choice(String... values) { return Map.of("type", "string", "enum", List.of(values)); }
    public static McpSchema.JsonSchema schema(String name) {
        if (!NAMES.contains(name)) throw new IllegalArgumentException("Unknown tool: " + name);
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        if (!name.equals("get_world_context")) {
            properties.put("dimension", Map.of("type", "string", "maxLength", 256));
            for (String axis : List.of("x", "y", "z")) properties.put(axis, Map.of("type", "integer", "minimum", Integer.MIN_VALUE, "maximum", Integer.MAX_VALUE));
            required.addAll(List.of("dimension", "x", "y", "z"));
        }
        if (name.contains("place") || name.equals("update_command_block")) {
            properties.put("command", Map.of("type", "string", "maxLength", 32767));
            properties.put("type", choice("impulse", "repeating", "chain"));
            properties.put("facing", choice("north", "south", "east", "west", "up", "down"));
            for (String flag : List.of("conditional", "automatic", "trackOutput")) properties.put(flag, field("boolean"));
            properties.put("name", Map.of("type", "string", "maxLength", 256));
            if (name.contains("place")) { properties.put("replace", field("boolean")); required.add("command"); }
        }
        if (name.equals("execute_command_block")) properties.put("mode", choice("single", "chain"));
        if (name.equals("query_area")) {
            for (String axis : List.of("endX", "endY", "endZ")) {
                properties.put(axis, Map.of("type", "integer", "minimum", Integer.MIN_VALUE, "maximum", Integer.MAX_VALUE));
                required.add(axis);
            }
            for (String limit : List.of("maxBlocks", "maxEntities")) properties.put(limit, Map.of("type", "integer", "minimum", 1, "maximum", 1024, "default", 256));
        }
        if (name.equals("get_command_chain")) properties.put("maxBlocks", Map.of("type", "integer", "minimum", 1, "maximum", 4096, "default", 4096));
        if (name.equals("batch_place_command_blocks")) {
            McpSchema.JsonSchema item = schema("place_command_block");
            properties.clear(); required.clear(); required.add("blocks");
            properties.put("blocks", Map.of("type", "array", "minItems", 1, "maxItems", 256, "items",
                Map.of("type", "object", "properties", item.properties(), "required", item.required(), "additionalProperties", false)));
        }
        if (name.equals("precheck_commands")) {
            properties.put("command", Map.of("type", "string", "maxLength", 32767)); required.add("command");
            Map<String, Object> item = Map.of("type", "object", "properties", new LinkedHashMap<>(properties), "required", new ArrayList<>(required), "additionalProperties", false);
            properties.clear(); required.clear(); required.add("commands");
            properties.put("commands", Map.of("type", "array", "minItems", 1, "maxItems", 256, "items", item));
        }
        return new McpSchema.JsonSchema("object", properties, required, false, null, null);
    }
    public static void validate(String name, Map<String, Object> args) {
        McpSchema.JsonSchema schema = schema(name);
        validateObject(schema.properties(), schema.required(), args);
        if (name.equals("query_area")) areaVolume(args);
    }
    static long areaVolume(Map<String, Object> args) {
        long volume = 1;
        for (String axis : List.of("x", "y", "z")) {
            long length = Math.abs(((Number)args.get(axis)).longValue() - ((Number)args.get("end" + axis.toUpperCase(Locale.ROOT))).longValue()) + 1;
            if (length > 32768 / volume) throw new IllegalArgumentException("Area volume must not exceed 32768 blocks");
            volume *= length;
        }
        return volume;
    }
    @SuppressWarnings("unchecked")
    private static void validateObject(Map<String, Object> properties, List<String> required, Map<String, Object> args) {
        for (String key : required) if (!args.containsKey(key)) throw new IllegalArgumentException("Missing field: " + key);
        args.forEach((key, value) -> {
            if (!properties.containsKey(key)) throw new IllegalArgumentException("Unknown field: " + key);
            Map<String, Object> spec = (Map<String, Object>) properties.get(key);
            String type = (String) spec.get("type");
            boolean valid = switch (type) {
                case "string" -> value instanceof String;
                case "boolean" -> value instanceof Boolean;
                case "integer" -> value instanceof Number n && Double.isFinite(n.doubleValue()) && n.doubleValue() == n.intValue();
                case "array" -> value instanceof List<?>;
                default -> false;
            };
            if (!valid) throw new IllegalArgumentException("Invalid " + type + " field: " + key);
            if (value instanceof Number n && ((spec.containsKey("minimum") && n.doubleValue() < ((Number)spec.get("minimum")).doubleValue())
                || (spec.containsKey("maximum") && n.doubleValue() > ((Number)spec.get("maximum")).doubleValue()))) throw new IllegalArgumentException("Field out of range: " + key);
            if (spec.containsKey("enum") && !((List<?>)spec.get("enum")).contains(value)) throw new IllegalArgumentException("Invalid value for " + key);
            if (value instanceof String s && spec.containsKey("maxLength") && s.length() > (int)spec.get("maxLength")) throw new IllegalArgumentException("Field too long: " + key);
            if (value instanceof List<?> list) {
                if (list.size() < ((Number)spec.get("minItems")).intValue() || list.size() > ((Number)spec.get("maxItems")).intValue()) throw new IllegalArgumentException("Field " + key + " must contain " + spec.get("minItems") + "–" + spec.get("maxItems") + " items");
                Map<String, Object> itemSpec = (Map<String, Object>)spec.get("items");
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?>)) throw new IllegalArgumentException("Batch item must be an object");
                    validateObject((Map<String, Object>)itemSpec.get("properties"), (List<String>)itemSpec.get("required"), (Map<String, Object>)item);
                }
            }
        });
    }
}
