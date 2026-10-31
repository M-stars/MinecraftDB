# MinecraftDB

将超平坦世界变成 MySQL 风格的数据库 —— 用彩色羊毛块存储数据，支持完整 SQL 查询、JOIN、事务、MVCC 隔离、内存索引和 HTTP/Web 工作台。

## 核心概念

MinecraftDB 利用 Minecraft 世界中的**彩色羊毛块**作为物理存储介质。每种羊毛颜色代表一个十六进制半字节（0x0–0xF），数据按行/列排列在超平坦世界上，你可以亲自走入"数据库"查看数据。

一张 **表** = X 轴上连续的一片区域，一行数据沿 Z 轴放置，一列数据沿 Y 轴堆叠。基岩标记表边界，金块标记表顶端，橡木告示牌写明表名与行数。

## 特性

- **完整 SQL 引擎**：从零构建的词法分析器、递归下降解析器、表达式 AST 执行器
- **DDL 支持**：`CREATE TABLE [IF NOT EXISTS]`、`DROP TABLE [IF EXISTS]`、`TRUNCATE [TABLE]`、`ALTER TABLE`、`CREATE INDEX`（真实内存索引）、`SHOW CREATE TABLE`
- **DML 支持**：`INSERT`（多组 VALUES 批量插入）、`SELECT`、`UPDATE`（SET 支持任意表达式）、`DELETE`
- **查询能力**：
  - `WHERE`：AND/OR 优先级、括号、`IS [NOT] NULL`、`[NOT] IN`、`[NOT] BETWEEN`、`LIKE`、**条件两侧支持表达式与标量函数**
  - **`JOIN`**：`[INNER] JOIN` / `LEFT [OUTER] JOIN ... ON`，表别名与 `alias.col` 限定列名，等值连接自动探测右表内存索引
  - **聚合**：`COUNT/SUM/AVG/MIN/MAX` + `GROUP BY` + **`HAVING`**（别名/聚合均可引用，未 SELECT 的聚合自动补算）+ `DISTINCT`
  - **子查询**：`IN (SELECT ...)` 与标量子查询（非相关），SELECT/UPDATE/DELETE 的 WHERE 通用
  - **标量函数**：`UPPER/LOWER/LENGTH/TRIM/ABS/ROUND/COALESCE`，可在 SELECT 投影、WHERE、SET 中嵌套使用
  - `ORDER BY`、`LIMIT`/`OFFSET`、`SELECT` 表达式投影与别名
- **数据完整性**：
  - `NOT NULL`、**复合 `PRIMARY KEY (a, b)`**（元组唯一）、`AUTO_INCREMENT`、`DEFAULT`
  - **`UNIQUE`**（列级/表级单列，走内存索引快速判重）
  - **外键 `REFERENCES tbl[(col)]`**：写入校验父表存在值，父端 DELETE/UPDATE 执行 RESTRICT
- **事务与隔离（MVCC 写缓冲）**：每个玩家/TCP 连接/HTTP 会话独立事务；事务内写入进会话私有缓冲，
  **其他会话只读到最后提交状态（无脏读）**；提交时做**首提交者胜冲突检测**（写冲突自动拒绝）；
  WAL 落盘 + 崩溃恢复（已提交事务幂等重放）+ 回滚（DDL 非事务性）
- **内存索引参与查询计划**：主键/自增/UNIQUE/显式索引列自动建哈希索引，
  等值查询 O(1)；**`EXPLAIN SELECT/UPDATE/DELETE`** 显示访问路径（INDEX SEEK / FULL TABLE SCAN）
- **数据进出口**：`GET /api/v1/table/{n}/export?format=csv|sql`、`GET /api/v1/dump`（整库 mysqldump 风格）、
  `POST /api/v1/table/{n}/import`（CSV，支持 autoCreate 按数据推断类型建表）、
  游戏内 `/db export|import|dump`
- **Web 查询工作台**：浏览器打开 HTTP 端口根路径即得——SQL 编辑器（Ctrl+Enter）、结果表格、
  表结构侧栏、执行历史、导入导出按钮，零外部依赖
- **三种交互方式**：游戏内 `/db` 命令/CLI 模式、HTTP REST API（端口 25566）、TCP telnet 终端（端口 25567，默认仅监听 127.0.0.1）
- **性能**：方块批量写入 + 分 tick 预算排水（`DROP` 大表不再卡顿）、UPDATE 只写被 SET 的列、流式行扫描
- **零新增物品/方块**：只使用原版的羊毛、基岩、金块和告示牌

## 快速开始

### 构建

```bash
./gradlew build
```

构建产物位于 `build/libs/MinecraftDB-1.0.0.jar`。

### 部署

将 JAR 文件放入 Minecraft 客户端 `mods/` 目录。启动游戏后，在超平坦世界中使用：

```
/db cli                 # 进入 SQL 交互模式
/db CREATE TABLE ...    # 直接执行 SQL
/db tables              # 查看所有表
/db locate <表名>       # 传送到表旁
/db signs               # 刷新表告示牌
/db dump                # 整库导出 SQL
```

### Web 工作台

服务器启动后浏览器打开 `http://127.0.0.1:25566/` 即可使用查询工作台。

### HTTP API

```
GET  /                                # Web 查询工作台
GET  /api/v1/health                   # 健康检查
GET  /api/v1/query?sql=SELECT...      # 执行只读查询（SELECT/SHOW/DESC/EXPLAIN，写操作返回 405）
POST /api/v1/execute                  # 执行任意 SQL {"sql": "..."}（请求体上限 1MB）
GET  /api/v1/tables                   # 列出所有表
GET  /api/v1/table/{name}/schema      # 获取表结构
GET  /api/v1/table/{name}/rows        # 分页获取表数据（limit/offset，响应含 hasMore）
GET  /api/v1/table/{name}/export?format=csv|sql  # 导出单表
GET  /api/v1/dump                     # 整库 SQL dump
POST /api/v1/table/{name}/import?autoCreate=true # 导入 CSV（body 为原始 CSV，上限 16MB）
```

**HTTP 事务**：默认每个请求独立（等效自动提交）。需要跨请求事务时，携带相同的
`X-MinecraftDB-Session` 请求头即可：`POST /execute` 发 `BEGIN`，后续请求用同一
会话头执行语句，最后 `COMMIT`/`ROLLBACK`。两个请求写同一行时，后提交者会收到
提交冲突错误（首提交者胜）。

### TCP CLI

```bash
telnet localhost 25567
```

连接后即可在类 MySQL 终端中执行 SQL（连接数上限 10，空闲 10 分钟自动断开）。
每条连接拥有独立事务。

## SQL 语法速查

```sql
-- 建表：复合主键、UNIQUE、外键、AUTO_INCREMENT、DEFAULT
CREATE TABLE IF NOT EXISTS users (
  id INT PRIMARY KEY AUTO_INCREMENT,
  email VARCHAR(100) UNIQUE,
  name VARCHAR(50) NOT NULL,
  role VARCHAR(20) DEFAULT 'player',
  score INT DEFAULT 0
);
CREATE TABLE orders (
  order_id INT, item_id INT, qty INT, user_id INT REFERENCES users(id),
  PRIMARY KEY (order_id, item_id)
);

-- 批量插入（自增列可省略）
INSERT INTO users (email, name, score) VALUES ('a@x.com', 'Alice', 90), ('b@x.com', 'Bob', 75);

-- 条件查询：表达式两侧、函数、BETWEEN、子查询
SELECT id, UPPER(name) AS name_up, score * 2 AS double_score
  FROM users
 WHERE (role = 'player' OR score BETWEEN 80 AND 100)
   AND id IN (SELECT user_id FROM orders)
 ORDER BY score DESC
 LIMIT 10 OFFSET 0;

-- JOIN（等值连接走内存索引探测）
SELECT u.name, o.order_id FROM users u
  LEFT JOIN orders o ON u.id = o.user_id
 WHERE o.qty > 1;

-- 聚合与分组 + HAVING
SELECT role, COUNT(*) AS cnt, AVG(score) AS avg_score
  FROM users GROUP BY role HAVING cnt >= 2 ORDER BY cnt DESC;

-- 修改与删除（SET 支持表达式与函数）
UPDATE users SET score = score + 10, name = UPPER(name) WHERE name LIKE 'A%';
DELETE FROM users WHERE email IS NULL;
TRUNCATE TABLE users;

-- 事务与会话（每个玩家/TCP 连接/HTTP 会话头独立，写冲突首提交者胜）
BEGIN; COMMIT; ROLLBACK;

-- 元数据与查询计划
SHOW TABLES;  DESC users;  SHOW INDEXES FROM users;  SHOW CREATE TABLE users;
EXPLAIN SELECT * FROM users WHERE id = 1;   -- INDEX SEEK or FULL TABLE SCAN
```

## 已知限制

- 不支持 UNION、相关子查询（子查询不可引用外层列）、复合表级 `UNIQUE`、多列 `ORDER BY` 表达式
- 外键仅支持 RESTRICT（无 ON DELETE CASCADE / SET NULL）；外键父端检查为子表扫描
- 隔离级别为"读已提交 + 首提交者胜"：读会话看到的是最后提交状态（而非事务开始时的快照）；
  不做间隙锁，插入意向冲突由提交时冲突检测兜底
- 索引为内存结构，重启后首次使用时全表扫描重建；事务写过某表时该表查询绕过索引走全扫描
  （保证读己之写）
- 聚合/排序/JOIN 需要物化命中行，受 `PERFORMANCE_MAX_SELECT_ROWS` 保护
- 所有语句在服务端主线程串行执行（Minecraft setBlock 的线程约束）
- `DROP TABLE` 的方块清除分 tick 排水，期间世界里残留的羊毛暂不可见但尚未清除

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
| `PERFORMANCE_BLOCKS_PER_TICK` | `2048` | 每 tick 排水的方块写入上限（0 = flush 时同步全部应用） |

## 技术架构

```
src/main/java/com/minecraftdb/
├── MinecraftDB.java          # Mod 主类（tick 排水泵）
├── api/                      # HTTP API + Web 工作台 + TCP CLI 服务
├── command/                  # 游戏内 /db 命令（locate/signs/export/import/dump）
├── config/                   # JSON 配置管理
├── database/                 # 数据库引擎核心（事务/MVCC 写缓冲、锁、WAL、索引、数据进出口）
├── sql/                      # SQL 词法分析、解析、执行、查询计划、JOIN 命名空间
├── storage/                  # 羊毛块编解码、待应用写入日志（FIFO + 分 tick 排水）
└── util/                     # 工具类
```

可脱离 Minecraft 单测的纯逻辑（表达式求值、条件树、查询计划、JOIN 命名空间、
待应用写入日志、CSV 解析、WAL 判定）位于 `sql/ast`、`sql/`、`storage/`、`database/`
包内，测试共 150+ 项（含 5000 条固定种子模糊输入）。

## 环境要求

- Minecraft 1.20.1
- Forge 47.4+
- Java 17+
