# MinecraftDB

将超平坦世界变成 MySQL 风格的数据库 —— 用彩色羊毛块存储数据，支持完整 SQL 查询、ACID 事务和 HTTP API。

## 核心概念

MinecraftDB 利用 Minecraft 世界中的**彩色羊毛块**作为物理存储介质。每种羊毛颜色代表一个十六进制半字节（0x0–0xF），数据按行/列排列在超平坦世界上，你可以亲自走入"数据库"查看数据。

一张 **表** = X 轴上连续的一片区域，一行数据沿 Z 轴放置，一列数据沿 Y 轴堆叠。基岩标记表边界，金块标记表顶端。

## 特性

- **完整 SQL 引擎**：从零构建的词法分析器、递归下降解析器、AST 执行器
- **DDL 支持**：`CREATE TABLE`、`DROP TABLE`、`ALTER TABLE`、`CREATE INDEX`（索引当前仅元数据，不参与查询计划）
- **DML 支持**：`INSERT`（支持多组 VALUES 批量插入）、`SELECT`、`UPDATE`、`DELETE`，支持 `WHERE` 条件、比较运算符、`ORDER BY`、`LIMIT`/`OFFSET`
- **数据类型**：`INT`、`BIGINT`、`FLOAT`、`DOUBLE`、`BOOLEAN`、`VARCHAR(n)`、`TEXT`、`DATE`、`DATETIME`；`NOT NULL` 约束生效
- **事务**：WAL 落盘 + 崩溃恢复 + 语句级回滚（DDL 非事务性，与常规数据库语义一致）
- **三种交互方式**：游戏内 `/db` 命令/CLI 模式、HTTP REST API（端口 25566）、TCP telnet 终端（端口 25567，默认仅监听 127.0.0.1）
- **零新增物品/方块**：只使用原版的羊毛、基岩和金块，注册表完全干净

## 快速开始

### 构建

```bash
./gradlew build
```

构建产物位于 `build/libs/MinecraftDB-1.0.0.jar`。

### 部署

将 JAR 文件放入 Minecraft 客户端的 `mods/` 目录。启动游戏后，在超平坦世界中使用：

```
/db cli                 # 进入 SQL 交互模式
/db CREATE TABLE ...    # 直接执行 SQL
/db tables              # 查看所有表
```

### HTTP API

```
GET  /api/v1/health              # 健康检查
GET  /api/v1/query?sql=SELECT... # 执行只读查询（仅 SELECT/SHOW/DESC，写操作返回 405）
POST /api/v1/execute             # 执行任意 SQL {"sql": "..."}（请求体上限 1MB）
GET  /api/v1/tables              # 列出所有表
GET  /api/v1/table/{name}/schema # 获取表结构
GET  /api/v1/table/{name}/rows   # 分页获取表数据（limit/offset，响应含 hasMore）
```

### TCP CLI

```bash
telnet localhost 25567
```

连接后即可在类 MySQL 终端中执行 SQL（连接数上限 10，空闲 10 分钟自动断开）。

## SQL 语法速查

```sql
CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(50) NOT NULL, age INT);
INSERT INTO users (id, name, age) VALUES (1, 'Alice', 25), (2, 'Bob', 30);  -- 批量插入
SELECT * FROM users WHERE age > 18 ORDER BY age DESC LIMIT 10 OFFSET 0;
UPDATE users SET age = 26 WHERE name = 'Alice';
DELETE FROM users WHERE id = 1;
BEGIN; COMMIT; ROLLBACK;
SHOW TABLES;  DESC users;  SHOW INDEXES FROM users;
```

## 配置

在 `config/minecraftdb.json` 中可配置所有选项：

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `HTTP_API_ENABLED` | `true` | 启用 HTTP API |
| `HTTP_API_PORT` | `25566` | HTTP API 端口 |
| `HTTP_API_BIND_ADDRESS` | `127.0.0.1` | HTTP API 绑定地址 |
| `HTTP_API_TOKEN` | 空 | Bearer Token 认证 |
| `TCP_CLI_ENABLED` | `true` | 启用 TCP CLI |
| `TCP_CLI_PORT` | `25567` | TCP CLI 端口 |
| `TCP_CLI_BIND_ADDRESS` | `127.0.0.1` | TCP CLI 绑定地址，对外网开放前务必设置 Token |
| `TCP_CLI_TOKEN` | 空 | TCP CLI 认证 Token，设置后连接需先输入 `auth <token>` |
| `DATABASE_DEFAULT_WORLD` | `minecraftdb` | 作为数据库的世界名称（找不到时回退主世界） |
| `DATABASE_WAL_ENABLED` | `true` | 启用 WAL（事务日志与崩溃恢复） |
| `PERFORMANCE_MAX_SELECT_ROWS` | `10000` | 无 LIMIT 时 SELECT 的物化行数上限 |
| `PERFORMANCE_BATCH_BLOCK_UPDATES` | `true` | 方块批量写入（关闭后逐块立即落盘，仅调试用） |

## 技术架构

```
src/main/java/com/minecraftdb/
├── MinecraftDB.java          # Mod 主类
├── api/                      # HTTP + TCP API 服务
├── command/                  # 游戏内 /db 命令
├── config/                   # JSON 配置管理
├── database/                 # 数据库引擎核心（事务、锁、WAL）
├── sql/                      # SQL 词法分析、解析、执行、AST
├── storage/                  # 羊毛块编解码
└── util/                     # 工具类
```

SQL 引擎测试位于 `src/test/java/com/minecraftdb/sql/`。

## 环境要求

- Minecraft 1.20.1
- Forge 47.4+
- Java 17+


