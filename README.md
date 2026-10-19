# 4V4DBind

面向 Minecraft 26.3 的纯命令玩家资料绑定插件, 支持 Paper 和 Folia, 使用同一个 JAR

内置 QQ号、邮箱、户籍和手机号四种类型, 户籍由姓名与身份证号码组成一条记录. 每名玩家每种类型默认最多保存 10 条, 可分别调整上限或设为 `-1` 不限数量. 资料归属于玩家 UUID, 改名不会转移记录

这是玩家提交资料的登记功能. 插件只做格式校验, 不验证 QQ号、邮箱或手机号归属, 不验证姓名与身份证的真实对应关系, 不接入 QQ群、验证码或外部实名认证服务. 类型定义固定在代码中, 后续新增类型由我们修改代码实现; 类型相关的 YAML 设置仅保留每类数量上限

## 运行与安装

| 项目 | 目标 |
| --- | --- |
| 插件版本 | 1.0 |
| Minecraft | 26.3, 插件会拒绝其他游戏版本 |
| Java | 25 |
| Paper 编译基线 | `26.3.build.205-stable` |
| Folia 编译基线 | `26.3.build.3-alpha` |
| 部署文件 | `4V4DBind-1.0.jar` |

将 JAR 放入服务端的 `plugins` 目录并启动服务端. 首次启动会生成:

```text
plugins/4V4DBind/
  config.yml
  messages.yml
  bindings.db
```

看到日志中的“资料服务已就绪”后即可使用 `/bind`. SQLite、Gson 和 Adventure 使用目标服务端提供的版本, 无需另装数据库服务或其他前置插件. SQLite 运行期间还可能生成 `bindings.db-wal` 和 `bindings.db-shm`

旧版本遗留的 `types.yml` 不再读取, 插件也不会自动删除它; 可自行删除这个遗留文件

## 命令

类型参数使用固定的内置显示名 `QQ号`、`邮箱`、`户籍`、`手机号`. 内置 ID 是保存到数据库的稳定标识, 不会自动成为命令别名. 下表中的 `<...>` 是必填占位符, `[...]` 是可选参数

| 命令 | 用途 |
| --- | --- |
| `/bind` | 显示当前类型的帮助 |
| `/bind add <类型> <字段...>` | 为自己添加一条记录 |
| `/bind list [类型] [页码]` | 查看自己的列表, 按内置规则脱敏 |
| `/bind view <编号>` | 查看自己某条记录的完整内容 |
| `/bind remove <编号>` | 删除自己的记录 |
| `/bind admin add <玩家或UUID> <类型> <字段...>` | 为目标玩家添加记录 |
| `/bind admin list <玩家或UUID> [类型] [页码]` | 查看目标玩家的列表 |
| `/bind admin view <玩家或UUID> <编号>` | 查看目标玩家某条记录的完整内容 |
| `/bind admin remove <玩家或UUID> <编号>` | 删除目标玩家的记录 |
| `/bind reload` | 验证并重载 `config.yml` 和 `messages.yml` |

示例:

```text
/bind add QQ号 12345678
/bind add 邮箱 User@example.invalid
/bind add 户籍 "张 三" <18位身份证号码>
/bind add 手机号 13800138000
/bind list
/bind list 2
/bind list QQ号 2
/bind list 手机号
/bind view 12
/bind remove 12
/bind admin list PlayerName 邮箱 1
/bind admin view PlayerName 12
/bind admin add PlayerName QQ号 23456789
/bind admin add PlayerName 手机号 13900139000
/bind admin remove PlayerName 12
```

包含空格的字段使用英文双引号包围. 双引号内部支持 `\"` 和 `\\` 转义; 引号必须闭合, 不支持用单引号代替. 户籍按“姓名、身份证号码”的顺序填写, 其他三类只填一个值. 控制台使用管理员命令, 通常不需要开头的 `/`; 普通玩家命令仅供玩家执行

页码从 1 开始, 默认每页 10 条. `/bind list 2` 查看全部类型的第二页, `/bind list QQ号 2` 查看 QQ号的第二页. 记录编号在数据库内唯一, 但编号本身不会授予访问其他玩家记录的权限

管理员可以操作已被插件记录过的离线玩家. 玩家名来自本服登录时记录的目录, 不会联网查找任意玩家; 名字存在歧义时使用 UUID. Tab 只提示命令、类型名和有权限操作时的在线玩家名, 不提示已经保存的个人资料

## 权限

| 权限节点 | 默认 | 范围 |
| --- | --- | --- |
| `4v4dbind.use` | 所有玩家 | 添加、列表、完整查看和删除自己的资料 |
| `4v4dbind.admin.list` | OP | 查看其他玩家的列表 |
| `4v4dbind.admin.view` | OP | 查看其他玩家的完整资料 |
| `4v4dbind.admin.add` | OP | 为其他玩家添加资料 |
| `4v4dbind.admin.remove` | OP | 删除其他玩家的资料 |
| `4v4dbind.reload` | OP | 重载配置 |

管理员权限按操作分别判断. 列表权限不会授予完整查看权限. 控制台也通过对应的权限检查

## 数据与去重规则

四种内置类型分别有独立上限. 同一玩家不能重复绑定相同 QQ号、邮箱或手机号; 户籍按身份证号码去重, 修改姓名后再次提交同一号码仍视为重复. 不同玩家可以登记相同资料, 包括相同身份证号码和手机号

邮箱保留本地部分的大小写, 域名统一为小写, 国际化域名转换为 ASCII. 因此域名大小写不同不会绕过去重, 本地部分大小写不同仍可作为不同值. 身份证末位 `x` 统一为 `X`

手机号只接受大陆 11 位 ASCII 数字, 格式为 `1[3-9][0-9]{9}`. 不接受 `+86` 前缀、分隔符或全角数字, 不发送短信, 不验证号码是否存在或由提交者持有

SQLite 明文保存所有字段及去重信息. `list` 的脱敏仅影响显示, `/bind view` 和有权限的管理员完整查看会显示原值. 插件自己的操作日志只记录操作、UUID、记录编号和结果等元数据, 不写入字段原值; 服务端、代理、客户端历史或其他插件仍可能记录输入的整条命令

控制台完整查看功能保留. 控制台完整查询结果会由服务端写入控制台日志

备份时先停服, 再复制整个 `plugins/4V4DBind` 目录. 运行中仅复制 `bindings.db` 可能遗漏尚在 WAL 中的写入

## 两个配置文件

### config.yml

默认内容:

```yaml
schema-version: 1
page-size: 10
queue-capacity: 256
busy-timeout-millis: 3000
rate-burst: 5
rate-per-second: 1.0
max-in-flight: 2
zone: Asia/Hong_Kong
binding-limits:
  qq: 10
  email: 10
  identity: 10
  phone: 10
```

| 配置 | 含义 | 范围 |
| --- | --- | --- |
| `page-size` | 每页记录数 | 1–50 |
| `queue-capacity` | 单个后台数据库执行器的等待队列容量 | 1–4096 |
| `busy-timeout-millis` | 等待 SQLite 锁的最长时间, 单位毫秒 | 0–10000 |
| `rate-burst` | 每个会话可积累的命令令牌数 | 1–100 |
| `rate-per-second` | 每秒补充的命令令牌数 | 0.1–20 |
| `max-in-flight` | 每个会话同时处理的请求上限 | 1–32 |
| `zone` | 记录时间的显示时区 | 有效的 Java 时区名称 |
| `binding-limits` | 每名玩家各类资料的数量上限 | 各项为 `-1` 或 1–1000000 |

`queue-capacity` 和 `busy-timeout-millis` 修改后必须重启服务端. 这两项有变化时, `/bind reload` 会拒绝整次重载并保留原配置. 其他配置也只有在所有校验和数据库类型同步都成功后才一起生效; 重载过程中会暂时拒绝新请求

`binding-limits` 使用固定的内置 ID `qq`、`email`、`identity`、`phone` 作为键, 分别控制 QQ号、邮箱、户籍和手机号的数量上限. 整个映射或其中任意一项都可以省略, 省略项使用代码默认上限 10. `-1` 表示不限数量, 不是停用

数量必须为 YAML 整数 `-1` 或 1–1000000. 未知 ID、`0`、小于 `-1`、超过 1000000、非整数或带引号的数字都会被拒绝. 例如只修改手机号数量时, 可在现有 `config.yml` 中保留 `binding-limits: {phone: -1}`

数量上限支持 `/bind reload` 生效. 降低上限不会删除已有记录; 已达到或超过新上限时, 需要先删除记录才能继续添加. 除数量外, 类型名称、启用状态、字段、脱敏规则和校验结构均固定在代码中, 没有 YAML 设置入口

### messages.yml

默认前缀为 `[4V4DBind]`, 提供中文帮助、列表、完整查看、添加、删除、重载和错误消息. 保留默认键, 按需修改对应文本即可. 支持 MiniMessage, 需要完整闭合格式标签, 例如 `<green>已保存</green>`; 动态资料作为纯文本插入, 不会作为 MiniMessage 标签解析

两个 YAML 文件均要求 UTF-8, 单文件最多 256 KiB. 不接受重复键、集合别名、对象标签等结构. `config.yml` 顶层使用固定键集合, 除可省略的 `binding-limits` 外, 缺键或额外键会被拒绝. 无效配置在重载时不会替换正在使用的配置

## 内置类型与代码扩展

| 内置 ID | 命令显示名 | 字段顺序 | 本人去重依据 | 默认上限 |
| --- | --- | --- | --- | --- |
| `qq` | QQ号 | QQ号 | `value` | 10 |
| `email` | 邮箱 | 邮箱 | `value` | 10 |
| `identity` | 户籍 | 姓名、身份证号码 | `id_number` | 10 |
| `phone` | 手机号 | 手机号 | `value` | 10 |

字段最大长度分别为 QQ号 12、邮箱 254、姓名 64、身份证号码 18、手机号 11 个码点. 所有内置字段都必填, 四种类型均启用

| 类型或字段 | 校验规则 |
| --- | --- |
| QQ号 | 5–12 位 ASCII 数字, 首位不能为 0 |
| 邮箱 | 邮箱格式检查及域名规范化 |
| 姓名 | 姓名文本, 支持字母及适当的组合字符、内部空格和姓名分隔符 |
| 身份证号码 | 中国大陆 18 位身份证的结构、校验位和出生日期检查 |
| 手机号 | 11 位 ASCII 数字, 满足 `1[3-9][0-9]{9}` |

身份证检查不查询行政区划数据库, 不核实证件是否存在或属于提交者. 所有输入还受不可接受字符、字段长度与总长度限制, 保存后的 JSON 不得超过 8192 字节

列表中的身份证号码固定显示 `****`. 其他字段超过 4 个码点时保留首尾各 2 个, 否则显示 `****`. 脱敏规则不能通过 YAML 修改

固定的类型 ID、显示名、启用状态、版本、字段结构、脱敏规则、验证器和去重依据统一由 `bind-core` 的 `BuiltinTypes.defaults()` 定义. 后续有新的绑定需求时, 由我们在代码中增加定义, 按需增加校验器, 补充测试后重新构建 JAR

SQLite 会核对已保存类型的结构快照. 开发升级若改变已有记录所属类型的版本、字段 ID、顺序、所用验证器标识、必填状态、最大长度或去重依据, 或移除其代码定义, 会被拒绝; 单纯提升版本号不会自动迁移数据. 应保留旧定义并增加新类型, 或另行实现明确的数据迁移

## 编译与验证

使用 Java 25 和项目自带的 Gradle Wrapper:

```powershell
.\gradlew.bat clean build verifyFoliaApi
```

Linux/macOS:

```sh
sh ./gradlew clean build verifyFoliaApi
```

根项目输出 `build/libs/4V4DBind-1.0.jar`, 包含三个内部模块. 部署时只使用这个 JAR, 不需要复制各模块的 JAR

单独运行自动化测试可用 `sh ./gradlew test` 或 Windows 下的 `.\gradlew.bat test`. `verifyFoliaApi` 会额外对固定的 Folia API 编译插件源码. 实际验证范围和运行记录见 [docs/VERIFICATION.md](docs/VERIFICATION.md)

如果 Windows/Java 25 报 `Unable to establish loopback connection`, 可以仅在当前 PowerShell 进程设置 `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\Windows\BayMcNonexistentSocketDir` 后重试, 不需要修改系统环境变量

本地真实服务端验收脚本为 `tools/live_acceptance.py`. 构建后使用 Python 3 运行 `python tools/live_acceptance.py --accept-eula`; 只有接受 Minecraft EULA 后才使用该参数. 脚本校验指定服务端下载的 SHA-256, 在忽略的 `build/acceptance` 下创建独立目录, 仅监听 `127.0.0.1`, 不连接生产服. 它使用虚构玩家目录验证控制台命令和数据库, 不代替真实玩家客户端验收

## GitHub Actions 与版本发布

推送任意分支、提交 PR 或在 Actions 页面手动运行 `Build and Release` 时, 自动使用 Java 25 和 Gradle Wrapper 构建、执行自动测试并检查 Folia API. 成功后可在该次运行的 Artifacts 下载插件 JAR 与 SHA-256 校验文件, 保留 14 天; 测试报告也会保存

正式发布通过推送版本标签触发. 标签必须与 `build.gradle.kts` 中的版本、最终 JAR 文件名和包内 `plugin.yml` 完全一致. 当前版本为 `1.0`, 对应标签是 `v1.0`, 不能写成 `v1.0.0`

确认代码已经提交并推送后, 发布当前版本:

```sh
git tag -a v1.0 -m "4V4DBind 1.0"
git push origin v1.0
```

标签构建和测试通过后, 自动创建 GitHub Release, 生成发布说明, 并上传 `4V4DBind-1.0.jar` 与 `4V4DBind-1.0.jar.sha256`. 文件可在 [Releases 页面](https://github.com/konsheng/4V4DBind/releases)下载. 带 `-rc.1` 等后缀的版本会标为预发布

后续发版先修改项目版本、提交并推送代码, 再推送对应的新标签. 已有 Release 不会被覆盖; 重跑已发布标签的任务会停止发布. 普通分支推送、PR 和手动构建不创建 Release

工作流使用 GitHub 自动提供的 `GITHUB_TOKEN`, 无需配置个人访问令牌. 构建任务仅有读取仓库权限, 发布任务单独授予 `contents: write`; Gradle 使用开源基础缓存, 只有默认分支推送可以写缓存, Actions 固定到完整提交 SHA
