# 4V4DBind 1.0 验证记录

验证日期: 2026-10-19, Asia/Hong_Kong

## 环境与产物

- Windows Server 2025, Temurin OpenJDK 25.0.4.1
- Gradle 9.8.1, JUnit 6.1.3
- Paper 26.3 build 205, STABLE
- Folia 26.3 build 3, ALPHA
- 两个平台实际使用 JDBC 3.49.1.0, SQLite 3.49.1
- 交付文件: `build/libs/4V4DBind-1.0.jar`, 99,832 字节
- SHA-256: `da4cf72ad3ff2caa2b9ce9b6405d0d9ae98fb6e1bd83b8f021a8cd89d14689da`

Gradle 发行包与 Wrapper JAR 已分别核对官方 SHA-256. 成品包含三个内部模块及 `plugin.yml`、`config.yml`、`messages.yml` 三份 YAML 资源, 未包含 `types.yml`、Bukkit/Paper/Folia API、Adventure、Gson、SQLite 驱动、测试代码或本地 IDE 文件

## 自动化测试

执行 `gradlew.bat build verifyFoliaApi --no-daemon --warning-mode fail`, 构建及 Folia API 独立编译通过, 未出现 Gradle 弃用警告

| 模块 | 测试数 | 失败 | 错误 | 跳过 |
| --- | --- | --- | --- | --- |
| bind-core | 95 | 0 | 0 | 0 |
| bind-storage | 23 | 0 | 0 | 0 |
| bind-plugin | 37 | 0 | 0 | 0 |
| 合计 | 155 | 0 | 0 | 0 |

覆盖四种内置类型、Java 校验器扩展、多条绑定、跨玩家相同资料、本人去重、限额、并发和跨连接写入、事务、重启持久化、所有权查询、损坏 JSON、队列满、数据库忙、有界关闭、YAML 安全预检、错误配置、类型结构保护、MiniMessage 动态文本隔离、Unicode/控制字符/溢出、脱敏、限流、旧会话和调度时权限复查

类型全部由代码定义, 仅数量上限从主配置 `binding-limits` 读取. 测试覆盖手机号格式与默认上限、从旧三类数据库增加手机号、数量缺省及边界, 并确认资源中不存在 `types.yml`, 遗留文件即使包含恶意语法、非法 UTF-8 或超长内容也完全不读取. 后续类型扩展通过修改代码实现

本机 Java 25 存在回环连接初始化问题, 验证时仅对测试进程设置 `-Djdk.net.unixdomain.tmpdir=C:\Windows\BayMcNonexistentSocketDir`, 未修改系统环境变量

## 真实服务端验收

使用同一个且与交付 SHA-256 一致的插件 JAR, 由 `tools/live_acceptance.py` 在 `127.0.0.1` 临时测试服完成以下操作. 目标玩家目录由虚构测试夹具预置, 通过真实服务端控制台执行管理员命令

| 场景 | Paper | Folia |
| --- | --- | --- |
| 插件加载、数据库就绪、正常停服 | 通过 | 通过 |
| QQ号多条绑定、本人重复拒绝、跨玩家相同资料 | 通过 | 通过 |
| 邮箱规范化、户籍成组保存与身份证判重 | 通过 | 通过 |
| 手机号多条绑定、本人去重、跨玩家相同、非法格式拒绝 | 通过 | 通过 |
| 完整查询、所有权限定、脱敏列表 | 通过 | 通过 |
| 删除、重复删除、编号递增不重排 | 通过 | 通过 |
| 正常重载、错误配置保留旧设置 | 通过 | 通过 |
| 首次启动不生成类型配置、遗留类型文件不影响重载或重启 | 通过 | 通过 |
| 主配置降低上限保留旧记录并拒绝新增 | 通过 | 通过 |
| 主配置 `-1` 不限数量、未知限额类型拒绝 | 通过 | 通过 |
| 完整停服、重新启动、逐条核对数据与编号 | 通过 | 通过 |

机器可读结果见 [acceptance-report.json](acceptance-report.json). 原始临时目录为 `build/acceptance/run-20261019-154837`, 每个平台保留两次启动日志与 SQLite 数据. 这些文件都被 Git 忽略, 未混入交付包

服务端 OSHI 在此 Windows 环境报告 Perflib 计数器错误, 不影响上述启动和命令验收. 故障注入的错误配置会产生预期的重载拒绝日志

## 尚未完成的实测

- 未完成 Minecraft 26.3 真实玩家客户端登录、本人命令、Tab 补全和聊天显示验收
- 未完成真实玩家退出重连、动态撤销权限、Folia 跨区域移动时的回执实测; 对应逻辑已有自动化模型测试
- 尝试的测试客户端库 `minecraft-protocol` 1.68.0 仅提供至 26.1 的协议支持, 未用旧协议连接结果代替 26.3 客户端验证
- 未进行生产服长期运行、其他权限/登录插件组合或非 Windows 系统的实服验收

控制台完整查看按用户选择保留, 因此控制台查询结果会写入服务端日志; 插件审计事件本身只记录操作元数据. 数据明文存储也属于已确认需求

## 复现

先构建成品, 接受 Minecraft EULA 后运行:

```text
python tools/live_acceptance.py --accept-eula
```

脚本校验指定服务端下载的 SHA-256, 创建独立测试目录, 只监听本机, 结束后正常关闭测试服. 不需要安装 Node 或第三方 Python 包, 不操作生产目录. 使用 `--platform paper` 或 `--platform folia` 可以只测试一个平台
