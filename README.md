# time-shard — 时间分表组件（开发者接入指南）

> 适用场景：需要按时间维度做**物理分表**的 Spring Boot + `JdbcTemplate` 项目  
> Maven 坐标：`org.example:time-shard:2.0.1`  
> 核心包名：`org.example.shard`  
> 发布仓库：**GitHub Packages**（`https://maven.pkg.github.com/ninuxgithub/time-shard`）  
> 本库已在宿主项目 **graalvm-test** 中以 `api_log`（API 调用日志）作为首个落地业务，下文以它为完整示例。

---

## 1. 它能解决什么

一类数据天然具备「按时间写入、按时间范围查询、单表持续膨胀」的特征：API 调用日志、流水、审计记录等。
`time-shard` 帮你把它们按 **年 / 月 / 日** 粒度拆成物理分表（`api_log_2026` / `api_log_202609` / `api_log_20260922`），
写入自动路由落单表，范围查询自动 `UNION ALL` 多表合并并全局排序分页，且分片表**自动预建**——
跨年/月/日边界写入永不落空。

### 为什么不直接用现成方案

| 方案 | 结论 |
|---|---|
| ShardingSphere-JDBC | 大量 SPI / 反射 / 动态代理，**GraalVM native-image 编译风险高**，与本项目硬约束冲突 |
| MySQL 原生分区表（`PARTITION BY RANGE`） | 应用零改动，但主键须含分区键、分区数上限 8192，本质仍是逻辑单表 |
| **自研轻量组件（本库）** | `JdbcTemplate` 动态表名路由 + 自动建表，零反射魔法，GraalVM 友好，代码量可控 |

### 设计原则（务必遵守）

1. 分片表**不走 JPA/Hibernate**（`JPA` 对动态表名支持差，且 `ddl-auto=update` 只管实体表），统一走 `JdbcTemplate`；
2. **零反射、零 classpath 扫描**：分表描述符通过 Spring **构造器集合注入**收集，`RowMapper` 用**静态单例**，保证 native image 可用；
3. 写入按时间落单表；范围查询自动 `UNION ALL` 合并，按 `(created_at, id)` 全局排序 + 分页；
4. 分片表**自动预建**：启动时建模板表 + 当期/下期分片表，每天 `00:05` 定时巡检补建。

---

## 2. 工程结构

```
time-shard/                                # 独立分表库（无任何业务代码）
├── pom.xml                                # 独立版本 1.0.0，仅依赖 spring-context/jdbc/boot + slf4j
└── src/main/java/org/example/shard/
    ├── ShardStrategy.java                # 年/月/日策略枚举：后缀、周期截断、下一周期、区间枚举
    ├── ShardTableDescriptor.java         # 分表元数据接口（SPI）
    ├── ShardRouter.java                  # 表名路由 + 分片数保护
    ├── ShardTableManager.java            # 建表/补建/列举
    ├── ShardTableInitializer.java        # 启动初始化 + 每日 00:05 预建
    ├── AbstractShardedRepository.java     # 分片仓储基类
    └── PageResult.java                   # 分页结果 record（total/page/size/items）
```

---

## 3. 快速接入（五步）

> 下面用一个最小示例 `order_event` 讲清机制；第 6 节给出本项目 **api_log** 的真实完整实现。

### 步骤 0：配置 Maven 仓库与引入依赖

`time-shard` 发布在 **GitHub Packages**，且本项目构建通过 `settingsAliyun.xml` 走 Aliyun 镜像拉取 `central` 依赖。接入前必须配好两处：

**① 在宿主 `pom.xml` 声明 GitHub Packages 仓库（拉取 time-shard 必须）**

```xml
<repositories>
    <repository>
        <id>github</id>
        <name>GitHub NinuxGithub Apache Maven Packages</name>
        <url>https://maven.pkg.github.com/ninuxgithub/time-shard</url>
    </repository>
</repositories>
```

**② 在 `~/.m2/settings.xml` 配置仓库凭证（否则 401 无法下载）**

GitHub Packages 需要带 `read:packages` 权限的 Personal Access Token：

```xml
<servers>
    <server>
        <id>github</id>
        <username>你的GitHub用户名</username>
        <password>ghp_xxx你的GitHub Personal Access Token</password>
    </server>
</servers>
```

> 说明：本项目根目录的 `settingsAliyun.xml` 仅用于把 `central` 指向 Aliyun 镜像（`mvn -s settingsAliyun.xml ...`），它**不含 `github` 私服凭证**，不能替代上面的 ②。生产环境建议把 Aliyun 镜像与 GitHub 凭证都写进你自己的 `~/.m2/settings.xml`。

**③ 声明依赖（注意版本号是 2.0.1）**

```xml
<dependency>
    <groupId>org.example</groupId>
    <artifactId>time-shard</artifactId>
    <version>2.0.1</version>
</dependency>
```

**本地联调（可选）**：若你有 `time-shard/` 源码并想本地调试，把它 `install` 进本地仓库即可，无需走私服：

```bash
mvn -s settingsAliyun.xml -f time-shard/pom.xml install
# 宿主 app 构建（pom 已声明依赖，会自动命中本地仓库的 2.0.1）
mvn -s settingsAliyun.xml compile
```

### 步骤 1：定义实体（普通 POJO，不要写 JPA 注解）

```java
public class OrderEvent {
    private Long id;
    private String orderNo;
    private java.math.BigDecimal amount;
    private java.time.LocalDateTime createdAt;
    // getter / setter 略
}
```

> ⚠️ 分片表实体**不是 JPA 实体**，`spring.jpa.hibernate.ddl-auto=update` 不会管理它，建表完全由本库负责。

### 步骤 2：实现分表描述符（@Component）

声明模板表、分片策略、建模板表 DDL：

```java
@Component
public class OrderEventShardDescriptor implements ShardTableDescriptor {

    @Override public String templateTable() { return "order_event"; }

    @Override public ShardStrategy strategy() { return ShardStrategy.MONTH; }

    @Override public String createTemplateTableDDL() {
        return """
                CREATE TABLE IF NOT EXISTS order_event (
                    id         BIGINT        NOT NULL AUTO_INCREMENT,
                    order_no   VARCHAR(64)   NOT NULL,
                    amount     DECIMAL(12,2) NOT NULL,
                    created_at DATETIME(3)   NOT NULL,
                    PRIMARY KEY (id),
                    KEY idx_order_event_created (created_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """;
    }
}
```

### 步骤 3：继承分片仓储基类

```java
@Repository
public class OrderEventRepository extends AbstractShardedRepository<OrderEvent> {

    private final OrderEventShardDescriptor descriptor;

    public OrderEventRepository(JdbcTemplate jdbcTemplate, ShardRouter shardRouter,
                                OrderEventShardDescriptor descriptor) {
        super(jdbcTemplate, shardRouter);
        this.descriptor = descriptor;
    }

    @Override protected ShardTableDescriptor descriptor() { return descriptor; }
    @Override protected LocalDateTime shardTimeOf(OrderEvent e) { return e.getCreatedAt(); }
    @Override protected void applyGeneratedId(OrderEvent e, long id) { e.setId(id); }

    @Override protected String[] insertColumns() {
        return new String[]{"order_no", "amount", "created_at"};
    }

    @Override protected void bindInsert(PreparedStatement ps, OrderEvent e) throws SQLException {
        ps.setString(1, e.getOrderNo());
        ps.setBigDecimal(2, e.getAmount());
        ps.setObject(3, e.getCreatedAt());
    }

    @Override protected RowMapper<OrderEvent> rowMapper() {
        return (rs, i) -> {
            OrderEvent e = new OrderEvent();
            e.setId(rs.getLong("id"));
            e.setOrderNo(rs.getString("order_no"));
            e.setAmount(rs.getBigDecimal("amount"));
            e.setCreatedAt(rs.getObject("created_at", LocalDateTime.class));
            return e;
        };
    }
}
```

### 步骤 4：配置 application.properties

```properties
# 单次范围查询允许扫描的最大分片表数（防大区间拖垮 DB）
shard.max-tables-per-query=400

# 本业务分表粒度：YEAR / MONTH / DAY
order.event.shard-strategy=MONTH
```

> 子模块的 `shard-strategy` key 自己起（如 `order.event.shard-strategy`），通过 `@Value("${order.event.shard-strategy:MONTH}")` 注入到描述符即可，与库本身解耦。

### 步骤 5：启动装配

库内组件带 `@Component/@Service` 注解，包名为 `org.example.shard`：

- **若宿主 `@SpringBootApplication` 的主类在 `org.example`（如本项目）**：自动装配，零配置；
- **否则**需显式引入二选一：
  ```java
  @ComponentScan("org.example.shard")
  // 或
  @Import({ShardRouter.class, ShardTableManager.class, ShardTableInitializer.class})
  ```
- 库使用 `@Scheduled`（每日预建分片表），宿主需开启 `@EnableScheduling`。

完成。启动时组件自动建 `order_event` 模板表与当期/下期分片表，写入自动路由，查询自动合并。

---

## 4. 核心 API 速查

### ShardStrategy（枚举）

| 策略 | 物理表后缀示例 |
|---|---|
| `YEAR` | `order_event_2026` |
| `MONTH`（默认） | `order_event_202609` |
| `DAY` | `order_event_20260922` |

负责：生成后缀、周期截断（`truncate`）、下一周期（`next`）、区间枚举。时间范围遵循**左闭右开** `[start, end)`。

### ShardTableDescriptor（接口）

| 方法 | 说明 |
|---|---|
| `templateTable()` | 模板表名（如 `order_event`），只承载 DDL，不存业务数据 |
| `strategy()` | 分片策略 `YEAR/MONTH/DAY` |
| `createTemplateTableDDL()` | 建模板表 DDL（用 `CREATE TABLE IF NOT EXISTS`） |

分片表由 `ShardTableInitializer` 通过 `CREATE TABLE ... LIKE template` 自动复制列与索引创建。

### AbstractShardedRepository<T>（基类，可直接用）

```java
repo.save(entity);                                       // 按时间路由插入单表，回填自增 id
repo.queryRange(start, end, "AND order_no = ?",          // 附加过滤（AND 开头）
                new Object[]{"SO123"}, true, 50, 0);     // desc=true 倒序, limit=50, offset=0
repo.countRange(start, end, null, null);                // 区间计数（含附加过滤）
```

需子类实现的抽象方法：`descriptor()`、`shardTimeOf(entity)`、`applyGeneratedId(entity, id)`、`insertColumns()`、`bindInsert(ps, entity)`、`rowMapper()`。

### PageResult<T>（record）

`total` / `page` / `size` / `items` —— 分页结果，可直接作为 REST 响应体。

---

## 5. 配置项

| Key | 默认值 | 说明 |
|---|---|---|
| `shard.max-tables-per-query` | `400` | 单次范围查询最多扫描的分片表数，超限抛 `IllegalStateException`（HTTP 500），提示缩小区间 |
| `<业务>.shard-strategy` | `MONTH` | 各业务自行定义的粒度开关（YEAR/MONTH/DAY） |

---

## 6. 本项目（graalvm-test）落地案例：api_log

`api_log` 是首个使用本库的真实业务，采集每次 `/api/*` 请求的 method/path/状态码/耗时/IP/UA。

**涉及的真实文件**（均在 `src/main/java/org/example/`）：

| 文件 | 职责 |
|---|---|
| `entity/ApiCallLog.java` | 普通 POJO（非 JPA 实体），字段 `appKey/method/path/queryString/statusCode/costMs/clientIp/userAgent/authResult/createdAt` |
| `repository/ApiCallLogShardDescriptor.java` | 声明 `api_log` 模板表、策略（`@Value("${api.log.shard-strategy:MONTH}")`）、DDL |
| `repository/ApiCallLogRepository.java` | 继承 `AbstractShardedRepository`，提供 `search(start,end,appKey,page,size)` 分页查询 |
| `config/ApiCallLogFilter(.Config).java` | 注册在 `/api/*`、最高优先级的过滤器，记录每次请求（失败也记录） |
| `service/ApiCallLogRecorder(.Impl).java` | `@Async` 异步落库，异常吞掉只告警——**日志永不影响业务请求** |
| `config/AsyncConfig.java` | 线程池 core 2 / max 4 / 队列 2000 / `DiscardPolicy`（过载丢日志保业务） |
| `controller/ApiCallLogController.java` | REST 查询端点 |

**查询接口**（需认证：前端 Session 或外部 JWT+签名）：

```
GET /api/api-logs
     ?start=2026-09-01            # 可选，日期或日期时间，默认近 7 天
     &end=2026-09-22              # 可选；仅日期时按"次日 00:00"半开区间处理
     &appKey=client-xxxx          # 可选，按调用方过滤
     &page=0                      # 可选，默认 0
     &size=20                     # 可选，默认 20，上限 100
```

返回（`PageResult`）：

```json
{
  "total": 128, "page": 0, "size": 20,
  "items": [
    { "id": 1024, "appKey": "client-abcd", "method": "GET", "path": "/api/apps",
      "queryString": null, "statusCode": 200, "costMs": 12, "clientIp": "127.0.0.1",
      "userAgent": "curl/8.0", "authResult": "SUCCESS",
      "createdAt": "2026-09-22T18:50:03.451" }
  ]
}
```

**运维：查看当前分片表**

```
GET /api/api-logs/shards
→ { "template": "api_log", "strategy": "MONTH", "tables": ["api_log_202609", "api_log_202610"] }
```

**前端登录态示例**

```bash
# 1) 登录拿 Cookie
curl -c cookies.txt -X POST http://localhost:8080/api/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'

# 2) 查日志
curl -b cookies.txt "http://localhost:8080/api/api-logs?start=2026-09-01&appKey=client-abcd"
```

---

## 7. 切换分表粒度

修改配置后重启即可：

```properties
api.log.shard-strategy=DAY      # MONTH -> api_log_20260922
```

> ⚠️ 切换只影响**新数据**的写入路由，已存在的旧分片表（如 `api_log_202609`）**不会被迁移或合并**。如需切换粒度，请配合运维迁移脚本处理历史数据。

---

## 8. 边界与注意事项

1. **null 边界回退**：`queryRange(start=null, end=X)` 只扫 X 所在单个分片；两者都为 null 则查「当前时间」所在分片。直接调 repository 时需自行传入完整区间（Controller 层已填默认近 7 天）。
2. **每表独立 AUTO_INCREMENT**：id 在跨分片间可能重复。全局排序以 `(created_at, id)` 联合决定，分页稳定；**不要用 id 做跨表唯一引用**。
3. **反区间 / 零长区间**：`end <= start` 返回空结果；Controller 层会先校验并返回 400。
4. **分片数保护**：单次范围查询最多扫 `shard.max-tables-per-query` 张表（默认 400），超限抛 `IllegalStateException`（HTTP 500）。
5. **异步落库可能丢日志**：线程池队列满时按 `DiscardPolicy` 丢弃（宁可丢日志不可拖垮业务）。`/api/api-logs/shards` 可核对分片表。
6. **失败启动兜底**：启动时建表失败（如 DB 暂不可用）只记 ERROR 不阻断应用；之后每次 `/api/*` 请求仍触发异步写，写失败有 warn 日志，可人工执行 `ensureShardTables` 补建。

---

## 9. GraalVM Native 兼容

本库刻意规避 native image 所有已知雷区，构建无需追加任何 `reflect-config` / `resource-config` 条目：

| 风险点 | 规避方式 |
|---|---|
| 反射创建实例 | `RowMapper` 为静态单例；实体为普通 POJO，直接 new |
| Classpath 扫描 | 分表描述符通过 Spring 构造器集合注入 `List<ShardTableDescriptor>` 收集 |
| 动态代理 | 无 AOP 代理需求；`@Async` 走 JDK 接口代理 |
| 动态 SQL 拼接 | 全部基于 `PreparedStatement` 占位符；表名来自白名单式确定性拼接（模板表 + 时间后缀），无外部输入直拼表名 |

```bash
mvn -Pnative native:compile    # 本组件零额外配置
```

---

## 10. 在其他项目中复用

`time-shard` 以 `org.example:time-shard:2.0.1` 发布在 **GitHub Packages**，接入只需 3 步：

1. **配仓库 + 凭证**：在目标项目 `pom.xml` 加入第 3 节「步骤 0」的 `<repositories>`（`id=github`，GitHub Packages 地址），并在 `~/.m2/settings.xml` 加入 `<server id="github">` + 带 `read:packages` 的 Token；
2. **声明依赖**：`org.example:time-shard:2.0.1`；
3. **写业务代码**：提供 `ShardTableDescriptor` 实现（`@Component`）+ 继承 `AbstractShardedRepository` 的仓储。

> 若目标项目与 `time-shard` 同处一个内网私服（如 Nexus / Artifactory），把上面的 GitHub Packages 仓库换成你们自己的私服地址与对应 `<server>` 凭证即可，其余完全一致。
>
> **为什么不用 Starter 自动装配**：Starter（`AutoConfiguration.imports`）要求库代码避开宿主扫描根包（需换包名），否则会被 `@SpringBootApplication` 重复扫描导致装配顺序错乱。保持与宿主同根的包名 + 组件注解是当下改动最小、行为最直观的方案；若未来要发布给大量外部项目，可升级为 Starter 形态。
