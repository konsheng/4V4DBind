# 4V4DBind

轻量级 Minecraft 资料绑定插件, 支持 Paper 和 Folia, 基于 Minecraft 26.3 开发, 需要 Java 25

## 功能

- 通过 `/bind` 绑定 QQ号、邮箱、户籍和手机号, 户籍包含姓名与身份证号码
- 每名玩家每类默认可绑定 10 条, 支持调整上限或设置为不限数量
- 同一玩家的相同资料去重, 户籍按身份证号码去重; 不同玩家可绑定相同资料
- 支持查看、删除和管理员代绑, 列表脱敏显示, 单条查看显示完整内容
- 使用 SQLite 明文存储, 无需额外安装数据库

绑定仅作资料登记和格式检查, 无需验证码或外部验证

## 安装

1. 从 [Releases](https://github.com/konsheng/4V4DBind/releases) 下载插件 JAR
2. 放入服务端的 `plugins` 目录并启动服务端
3. 使用 `/bind` 查看帮助

## 命令

`<...>` 为必填参数, `[...]` 为可选参数. 类型使用 `QQ号`、`邮箱`、`户籍`、`手机号`

| 命令 | 说明 |
| --- | --- |
| `/bind` | 查看帮助 |
| `/bind add <类型> <字段...>` | 添加自己的绑定 |
| `/bind list [类型] [页码]` | 查看自己的绑定列表 |
| `/bind view <编号>` | 查看一条完整记录 |
| `/bind remove <编号>` | 删除自己的记录 |
| `/bind admin add <玩家> <类型> <字段...>` | 为玩家添加绑定 |
| `/bind admin list <玩家> [类型] [页码]` | 查看玩家的绑定列表 |
| `/bind admin view <玩家> <编号>` | 查看玩家的完整记录 |
| `/bind admin remove <玩家> <编号>` | 删除玩家的记录 |
| `/bind reload` | 重载配置和消息 |

绑定示例:

```text
/bind add QQ号 12345678
/bind add 邮箱 user@example.com
/bind add 户籍 张三 <18位身份证号码>
/bind add 手机号 13800138000
```

户籍按姓名、身份证号码的顺序填写, 含空格的字段用英文双引号包围

页码从 1 开始. `/bind list 2` 查看全部类型的第 2 页, `/bind list QQ号 2` 查看 QQ号的第 2 页

管理员的 `<玩家>` 可填写本服已记录的玩家名或 UUID, 支持离线玩家. 控制台使用管理员命令

## 权限

| 权限节点 | 默认 | 说明 |
| --- | --- | --- |
| `4v4dbind.use` | 所有玩家 | 管理自己的绑定 |
| `4v4dbind.admin.list` | OP | 查看其他玩家的脱敏列表 |
| `4v4dbind.admin.view` | OP | 查看其他玩家的完整记录 |
| `4v4dbind.admin.add` | OP | 为其他玩家添加绑定 |
| `4v4dbind.admin.remove` | OP | 删除其他玩家的绑定 |
| `4v4dbind.reload` | OP | 重载配置 |

## 配置

文件位于 `plugins/4V4DBind/`:

- `config.yml`: 插件设置
- `messages.yml`: 提示消息, 支持 MiniMessage, 例如 `<green>已保存</green>`
- `database.db`: 绑定数据

`config.yml` 默认配置:

```yaml
page-size: 10                # 每页记录数
queue-capacity: 256          # 等待处理的请求上限
busy-timeout-millis: 3000    # 数据库繁忙时的最长等待毫秒数
rate-burst: 5                # 可积累的请求次数
rate-per-second: 1.0        # 每秒恢复的请求次数
max-in-flight: 2            # 每名玩家同时处理的请求数
zone: Asia/Hong_Kong        # 时间显示时区
binding-limits:             # 每名玩家各类绑定的数量上限, -1表示不限
  qq: 10
  email: 10
  identity: 10
  phone: 10
```

`qq`、`email`、`identity`、`phone` 分别对应 QQ号、邮箱、户籍、手机号, 仅用于配置

修改后执行 `/bind reload`. `queue-capacity` 和 `busy-timeout-millis` 修改后需要重启服务端. 降低绑定上限不会删除已有记录

备份时先停服, 再复制整个 `plugins/4V4DBind` 目录
