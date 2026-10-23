# 验证记录

## 2026-10-02：新增三个只读工具

环境：Windows 11、Java 25.0.3、Minecraft 26.3、Fabric Loader 0.19.5、Fabric API 0.161.0+26.3。工具总数为十个，新增 `query_area`、`precheck_commands`、`get_command_chain`。

当前发行文件：`build/libs/commandblock_mcp-1.0.0.jar`，8,885,067 字节。

SHA-256：`22CEE10A9188433D198F9E4F7EFF1999E781DF8C67CA4182BD80B78482434CCC`。

| 验证 | 实际结果 | 证据 |
| --- | --- | --- |
| Gradle `test build --offline --no-configuration-cache` | 通过；8 个 JUnit 测试，0 失败、0 错误；最终构建复用已通过的测试结果 | `build/read-only-test-build-log.txt`、`build/test-results/test/TEST-xzh.studio.commblock.mcp.McpRuntimeTest.xml` |
| 隔离专用服务器 `runVerificationServer --offline --no-configuration-cache` | `PASS 130 checks; restorePhase=true`，含持久化恢复及官方 SDK HTTP 调用 | `build/read-only-world-verification-log.txt`、`build/verification-server/verification-result.txt` |
| 发行 JAR 内容检查 | 包含新工具实现；未包含世界验证、客户端验证、故障注入和 JUnit 类 | `jar tf build/libs/commandblock_mcp-1.0.0.jar` |

单元测试覆盖十个工具的名称和完整发现 Schema、两种批量参数各自的元素 Schema、数组数量、必需/未知字段、错误类型、命令长度、区域体积上限及极端坐标溢出、明细数量和链读取数量限制。原有鉴权、会话生命周期、超时取消和配置持久化测试继续通过。

真实服务器用例覆盖纯空气、石头、水、植物、普通实体、旁观者玩家、实体中心在区域外但包围盒相交、仅贴边及超大包围盒、详情截断保留完整计数、反向端点、高度/世界边界、局部和中间未加载区块。命令预检覆盖合法命令、一个前导斜杠、空操作、未知命令、缺少参数、尾部垃圾、相对坐标、选择器没有目标、权限不足、其他维度、位置错误及混合成功失败批次，并检查世界方块和已有命令方块执行历史不变。整链读取覆盖直链、拐弯、断电、条件未满足、游戏规则关闭/链长为零、循环不重复、数量限制及恰好到达自然链尾、建筑高度和世界边界、未加载区块；逐块结果与单块读取一致，读取前后完整方块实体 NBT 一致。

HTTP 用例确认新增工具实际分发、混合预检失败设置 `isError=true`、空命令成功、循环链读取成功，以及区域失败结果不包含 `clear`。verbose 用模拟玩家验证预检批次仅一条摘要，悬停包含逐项位置和错误原因。

验证中修正了测试代码的 SDK Schema 表示差异、Minecraft 26.3 的实体常量和时间查询语法；首次失败中断了旧持久化夹具的重建，因此旧测试标记移至 `build/restore-marker-before-read-only-tests.txt` 后重建。HTTP 测试时，无玩家服务器会卸载夹具区块；验证模组增加了不持久化、不模拟的临时区块票据，仅保留 HTTP 夹具所在区块。新工具本身未增加区块加载或票据操作，未加载区块的拒绝与保持未加载已独立验证。

本次未重跑图形客户端或独立发行 JAR 的服务器启动验证；下方相关结果属于此前七个工具的版本。所有本次世界操作均在 `build/verification-server`，未操作用户游戏存档；测试服务器和 MCP 监听均已停止。

## 2026-10-01：七个工具版本的历史验证

日期：2026-10-01。环境：Windows 11、Java 25.0.3、Minecraft 26.3、Fabric Loader 0.19.5、Fabric API 0.161.0+26.3。

发行文件：build/libs/commandblock_mcp-1.0.0.jar

大小：8879262 字节。

SHA-256：DA27DAD4ACE1EF3EF13208BC03D951C6292566553AEEC2794A9F8766C0C698D0

| 验证 | 结果 | 证据 |
| --- | --- | --- |
| Gradle test + build | 通过，5 个 JUnit 测试无失败 | build/delivery-build-log.txt、build/test-results/test |
| 默认配置缓存构建 | 通过 | build/default-build-log.txt |
| 专用服务器世界验证 | PASS 59 checks，含重启恢复 | build/verification-server/verification-result.txt、build/edge-test-log.txt |
| 实际 Minecraft 客户端内置服务器 | PASS 57 checks，含作弊关闭拒绝 | build/verification-client/verification-result.txt、build/final-client-log.txt |
| 独立 Fabric 发行 JAR 运行 | PASS 56 checks，含重启恢复 | build/production-server/verification-result.txt、build/final-production-log.txt |
| 发行 JAR 检查 | 嵌入 20 个库，无重复日志实现，无测试/模板类 | JAR 内 fabric.mod.json 与 META-INF/jars |

测试覆盖官方 SDK 初始化、工具发现、调用、结构化结果、401/403、会话关闭、端口冲突与释放；持久化配置与令牌、请求排队超时及停止取消；三种命令方块/六个朝向、更新保留字段、覆盖保护、批量重复与全部预检；故障注入后恢复方块和完整方块实体 NBT；单块与连锁执行、条件失败、同 tick 保护、链长限制、命令删除自身与输出；无效维度、世界边界、高度、未加载区块及边界邻居不加载。

verbose 用服务器内模拟玩家验证普通玩家权限、独立订阅、取消订阅和失败消息内容。尚未用两个真实联网客户端人工检查聊天悬停显示，也未人工执行同一客户端切换多个存档的完整交互流程；会话隔离与取消机制已在独立测试和真实世界停止中验证。

发行 JAR 运行验证发生在最终两项小调整前：非法 JSON 的错误说明和 verbose 摘要长度限制。最终发行文件再次通过全部 JUnit 测试与构建。邻居区块边界检查是后补的专用服务器用例，因此各运行环境检查数不同。

测试目录均位于 build 下，未操作用户的游戏存档。测试专用模组不会进入发行 JAR。单人测试及测试服务器已停止。清理 build 会移除以上运行报告。
