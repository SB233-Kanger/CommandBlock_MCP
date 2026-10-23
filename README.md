# CommandBlock MCP

Minecraft **26.3** / Fabric Loader **0.19.5+** / Fabric API **0.161.0+26.3** / **Java 25**。

AI 通过本机 Streamable HTTP 管理命令方块，支持单人内置服务器与 Fabric 专用服务器。HTTP/MCP 使用 [官方 MCP Java SDK](https://java.sdk.modelcontextprotocol.io/latest/server/) 2.0.1 和 Jetty 12.1.13，运行依赖已嵌入发行 JAR。

## 安装与连接

1. 将 `build/libs/commandblock_mcp-1.0.0.jar` 和 Fabric API 放进游戏或服务端的 `mods` 目录。
2. 启动世界后生成 `config/commandblock_mcp.json`。首次默认关闭，管理员执行 `/cbmcp enable` 开启；单人世界必须开启作弊。
3. AI 客户端选择 **Streamable HTTP**，地址 `http://127.0.0.1:8765/mcp`，请求头 `Authorization: Bearer <配置文件中的 token>`。
4. 专用服务器的 AI 客户端运行在服务器本机。联机玩家无需安装客户端模组即可接收 verbose 消息。

配置示例（token 缺失/为空时自动生成，不要将示例文字作为令牌）：

```json
{"port":8765,"enabled":false,"requestTimeoutSeconds":15,"token":""}
```

监听地址固定为 `127.0.0.1`，端口 1–65535，等待超时 1–120 秒。令牌使用至少 32 个 URL-safe 字符。端口与令牌改动需重新进入世界或重启；enable/disable 自动保存。聊天和普通日志不输出令牌。HTTP 校验 Bearer、Host 和 Origin，普通 AI HTTP 客户端通常不发送 Origin。

世界停止会关闭会话并取消尚未开始的请求；新世界使用新的实例。配置错误或端口占用仅禁用 MCP，不中断游戏。排队超时后请求不会再修改世界；已开始的操作返回实际结果，避免中途取消造成半写入。

管理员命令（原版权限 `COMMANDS_ADMIN`）：

```text
/cbmcp enable
/cbmcp disable
/cbmcp status
```

每个玩家独立订阅，无需管理员权限，默认关闭，断线后清除：

```text
/cbmcp verbose on
/cbmcp verbose off
```

verbose 包含 AI 工具名、维度、坐标、结果和失败原因。批量使用一条摘要，鼠标悬停展开逐项位置、状态和原因。自动重复方块不会持续产生 MCP verbose；原版命令反馈仍由原版游戏规则控制。

## 工具与参数

所有位置均为显式 `dimension`（例如 `minecraft:overworld`）和整数绝对坐标 `x,y,z`。检查维度、世界边界、建筑高度和已加载区块，不主动加载区块。未知字段、错误类型和超长命令会被拒绝。

| 工具 | 必需参数 | 可选参数与行为 |
| --- | --- | --- |
| `get_world_context` | 无 | 维度、玩家 UUID/名称/位置、权限状态 |
| `get_command_block` | `dimension,x,y,z` | 读取配置、成功计数、最后输出、供电状态 |
| `place_command_block` | 位置、`command` | 下表配置，`replace=false` |
| `update_command_block` | 位置 | 下表配置；未指定字段保持原值 |
| `remove_command_block` | 位置 | 将已有命令方块替换为空气 |
| `batch_place_command_blocks` | `blocks` 数组 | 每项完整放置参数，1–256 项，拒绝重复坐标 |
| `execute_command_block` | 位置 | `mode=single\|chain`，默认 single |
| `query_area` | `dimension,x,y,z,endX,endY,endZ` | 包含两端、自动归一化，最多 32768 格；`maxBlocks,maxEntities` 分别默认 256，可设为 1–1024 |
| `precheck_commands` | `commands` 数组 | 1–256 项，每项 `dimension,x,y,z,command`；仅解析，不执行 |
| `get_command_chain` | 位置 | 包含起点，按各块朝向读取后续链式方块；`maxBlocks` 默认及上限为 4096，最小为 1 |

| 配置字段 | 允许值 | 放置默认值 |
| --- | --- | --- |
| `command` | 字符串，最多 32767 字符 | 必填，允许空字符串 |
| `type` | `impulse`, `repeating`, `chain` | `impulse` |
| `facing` | `north`, `south`, `east`, `west`, `up`, `down` | `north` |
| `conditional` | 布尔 | `false` |
| `automatic` | 布尔 | `false`（需要红石） |
| `trackOutput` | 布尔 | `true` |
| `name` | 字符串，最多 256 字符 | 原版默认名称 |
| `replace` | 布尔，仅放置支持 | `false`（只允许空气） |

批量在服务器线程预检全部目标，再完成全部配置，最后启用 automatic 并发送更新。预检失败整批不写入；写入异常恢复已修改目标的方块和方块实体 NBT。外部模组造成的额外副作用及已执行命令的副作用不属于回滚范围。修改和删除只接受已有命令方块。

手动执行使用方块自身位置、维度和原版权限，遵守条件判定与同 tick 重复执行保护。single 只执行指定方块；chain 按朝向遍历后续连锁，遵守供电/automatic、条件与 `max_command_sequence_length`，遇到未加载区块停止。手动执行不改变供电/automatic，也不创建循环任务；原本自动运行的方块仍保持原配置。Minecraft 26.3 的命令方块开关由维度规则 `minecraft:command_blocks_work` 控制，关闭时拒绝手动执行。

结果同时包含 `structuredContent` 与 JSON 文本，提供状态、位置或逐项 blocks。业务失败设置 `isError=true` 并给出原因与目标；条件不满足与同 tick 重复执行返回 `skipped`。

区域查询先检查整个范围的建筑高度、世界边界和区块加载状态，无法完整检查时返回错误，不提供空地结论。返回归一化 `bounds`、`volume`、`blockCount`、`entityCount`，以及非空气方块的 `blocks`（坐标、注册 `id`、状态 `properties`）和实体的 `entities`（`uuid,type,name,x,y,z,boundingBox`）。区域对应方格占据的空间，最大端的空间边界为坐标加一；实体包围盒与该空间有体积交叉即计入，包括玩家和旁观者，仅贴边不计入。水和植物同样属于非空气。`allAir`、`noEntities` 和 `clear=allAir && noEntities` 根据完整统计计算；`blocksTruncated`、`entitiesTruncated` 标记明细截断。结果仅表示调用时的状态，不锁定空间或保证后续放置成功。

命令预检无需目标位置已有命令方块，使用该维度、方块中心位置及原版命令方块权限。允许一个前导 `/`；空字符串及 `/` 均为有效空操作。返回 `commands` 中每项的零起始 `index`、原始位置与命令、`valid`、`status`、`error`、`cursor`、`context`，并提供 `count` 和 `invalidCount`。语法错误的 `cursor` 为原始命令中的零起始字符位置，包含前导 `/`，`context` 为错误附近文本；成功项及位置错误的字符位置/上下文为 `null`。参数 Schema 错误拒绝整次调用；通过参数校验后，逐项解析或位置失败继续检查其他项，存在失败时整体 `isError=true`。预检不执行命令、不解析选择器实际目标、不保证运行成功，也不改变放置和修改工具的语法接受行为。

整链读取返回有序 `blocks`、`count`、`complete`、`stopReason`，每块复用单块读取的配置与执行结果字段。起点可为任意命令方块，随后只读取相邻链式命令方块，支持拐弯。它不受供电、条件、命令方块开关或执行链长规则影响，不修改配置或执行历史。`stopReason` 为 `end_of_chain`、`loop`、`max_blocks`、`boundary` 或 `unloaded_chunk`；仅自然链尾 `end_of_chain` 对应 `complete=true`，循环位置不重复输出。恰好达到数量上限且已是自然链尾时仍标记完整。

## 查询与预检示例

放置前调用 `query_area` 检查区域：

```json
{"dimension":"minecraft:overworld","x":0,"y":100,"z":0,"endX":3,"endY":101,"endZ":0,"maxBlocks":256,"maxEntities":256}
```

调用 `precheck_commands` 批量检查语法；即使目标为空气也可检查：

```json
{"commands":[
  {"dimension":"minecraft:overworld","x":0,"y":100,"z":0,"command":"/say Hello from MCP"},
  {"dimension":"minecraft:overworld","x":1,"y":100,"z":0,"command":"execute as @e[type=minecraft:pig] run say parsed"}
]}
```

放置后调用 `get_command_chain` 读取整链：

```json
{"dimension":"minecraft:overworld","x":0,"y":100,"z":0,"maxBlocks":4096}
```

## 完整放置示例

玩家先进入目标区块，并启用 MCP。以下为 `batch_place_command_blocks` 参数，要求两处坐标均为空气：

```json
{
  "blocks": [
    {
      "dimension": "minecraft:overworld",
      "x": 0, "y": 100, "z": 0,
      "command": "say Hello from MCP",
      "type": "impulse", "facing": "east",
      "conditional": false, "automatic": false, "trackOutput": true,
      "name": "MCP start"
    },
    {
      "dimension": "minecraft:overworld",
      "x": 1, "y": 100, "z": 0,
      "command": "say Chain complete",
      "type": "chain", "facing": "east",
      "conditional": true, "automatic": true, "trackOutput": true,
      "name": "MCP follow-up"
    }
  ]
}
```

随后调用 `execute_command_block`：

```json
{"dimension":"minecraft:overworld","x":0,"y":100,"z":0,"mode":"chain"}
```

调用 `get_command_block` 读取第二块：

```json
{"dimension":"minecraft:overworld","x":1,"y":100,"z":0}
```

这些 JSON 是 MCP 工具参数，客户端须先初始化会话并通过 `tools/call` 调用；并非直接 POST 到端点的完整协议请求。

## 构建与验证

```powershell
.\gradlew.bat test build --no-configuration-cache
.\gradlew.bat runVerificationServer --no-configuration-cache
```

服务器测试只操作 `build/verification-server` 的独立世界。先阅读并接受 [Minecraft EULA](https://aka.ms/MinecraftEULA)，将该目录 `eula.txt` 设置为 `eula=true`，并将该目录 MCP 配置设为 `enabled=true, port=18765`。服务器自动停止，将 PASS/FAIL 写入 `verification-result.txt`。再次运行检查存档恢复。验证模组位于 `src/verification`，不包含于发行 JAR。

独立测试覆盖官方 SDK 初始化、工具发现、结构化结果、鉴权、会话关闭、端口释放、请求取消及参数校验。世界测试覆盖方块类型/朝向、字段保持、执行/条件/链长、覆盖保护、无效位置、批量预检、故障注入恢复和重启保存。
新增工具验证覆盖十个工具的发现与 Schema、区域体积和明细数量限制、空气/水/植物/实体/玩家及包围盒交叉、完整统计与截断、语法和位置混合失败批次、未执行命令、直链/拐弯/循环/边界/数量限制、未加载区块保持未加载，以及读取前后完整方块实体 NBT 一致。

单人验证可运行 `runVerificationClient --no-configuration-cache`：先将已停止的测试服务器世界复制到 `build/verification-client/saves/cbmcp-test`，为该客户端测试目录设置 MCP `enabled=true` 和独立端口。测试会自动打开这个世界并退出。验证任务检查 PASS/FAIL，失败时 Gradle 任务也会失败。详细结果与尚未人工验证的流程见 [VERIFICATION.md](VERIFICATION.md)。

首版不包含任意指令执行工具、撤销历史、外置 stdio 桥接或控制未安装模组的远程服务端。

许可证：[MIT](LICENSE)。
