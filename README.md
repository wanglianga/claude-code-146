# 城市共享自习室：预约 / 门禁延时 / 退费服务

Kotlin + Spring Boot 3 + Redis 实现的**后端服务**，覆盖预约下单、支付押金、实名门禁进出时间窗、
迟到顺延、无人值守连续事件（顺延侵占下一位 → 投诉 → 换座）、临时延时计费、设备故障与门店停电、
邻座噪音投诉、客服争议工单（门禁日志/座位传感器/申诉/店员处理/监控事件编号证据包）、
提前离场退费、包月固定座/浮动座权益、门店运营报表与一致性对账修复。

- 语言/框架：Kotlin 1.9 · Spring Boot 3.2 · Spring Data JPA · Spring Data Redis
- 存储：H2（零外部依赖可直接运行；compose 中为文件持久化）+ Redis 7（分布式锁/门禁凭证/座位占用镜像/幂等）
- API 文档：Swagger UI `/swagger-ui.html`，OpenAPI JSON `/v3/api-docs`
- Redis 不可达时自动降级为进程内协调实现（仅保证单实例一致），启动日志会打印当前实现

---

## 一、一键启动（Docker Compose）

前置：安装 Docker 与 Docker Compose。

```bash
docker compose up --build
```

启动后：

- 服务地址：http://localhost:8080
- Swagger UI：http://localhost:8080/swagger-ui.html
- 健康验证：`curl http://localhost:8080/api/stores`

## 二、本地开发启动（无 Docker）

前置：JDK 17、Maven 3.6+；Redis 可选（无 Redis 自动降级为单机模式）。

```bash
# 1)（可选）启动本地 Redis
redis-server --daemonize yes --port 6379

# 2) 构建并运行（默认读取 application.yml 中 REDIS_HOST/REDIS_PORT 环境变量）
mvn spring-boot:run
# 或打包后运行
mvn -DskipTests package
java -jar target/studyroom-service-1.0.0.jar

# 指定外部 Redis
REDIS_HOST=127.0.0.1 REDIS_PORT=6379 java -jar target/studyroom-service-1.0.0.jar
```

运行测试（不需要外部 Redis，会自动走单机协调实现）：

```bash
mvn test
```

种子数据默认开启（`studyroom.seed.enabled=true`），启动后自动创建：

| 门店 | 说明 |
|---|---|
| 门店 1「24h无人值守自习室(科技园店)」 | 无人值守，座位 A-01/A-02（单座）、A-03（单座·包月固定座）、R-01（包间）、N-01（夜间座） |
| 门店 2「晨光旗舰店(市民中心店)」 | 有人值守，座位 B-01 |

### 测试账号（种子用户）

| 用户 | 手机号 | 实名 | 说明 |
|---|---|---|---|
| 小明 | 13800000001 | 是 | 普通用户 |
| 小红 | 13800000002 | 是 | 普通用户 |
| 月月 | 13800000003 | 是 | 持有 A-03 当月固定座包月（MP-SEED-A03） |
| 未实名小刚 | 13800000004 | 否 | 用于验证未实名门禁拒绝 |

也可通过 `POST /api/users` 注册新用户、`POST /api/users/{id}/realname` 完成实名。

---

## 三、核心业务与一致性设计

### 状态与不变量

- 预约状态机：`PENDING_PAYMENT → BOOKED → IN_PROGRESS → COMPLETED/REFUNDED`，旁路 `CANCELLED / NO_SHOW / DISPUTED`。
- **门禁放行 IN** 与「预约 BOOKED→IN_PROGRESS、记录实际进场、座位 OCCUPIED + Redis 占用镜像」在**同一数据库事务 + Redis 座位锁**内完成，不会出现“门已开但订单仍未开始”。
- **离场/退费完成**与「延时计费、押金结算、座位 FREE + 删除 Redis 镜像、凭证作废」同事务，不会出现“退费完成但座位仍锁定”。
- 所有触碰座位时间轴的操作（下单、顺延、延时、换座、进出场）均按座位加分布式锁；换座涉及两个座位时按 seatId 升序加锁防死锁。
- 预约、门禁、座位、投诉、设备、计费、退款、门店责任的每一次变化写入统一不可变 `timeline_events`，客服可按单/门店/工单回放。

### 无人值守连续事件链

1. 上一位用户晚到（15 分钟宽限内可进场），未进场可申请**顺延**（整体时段后移）；
2. 顺延后与**同座位下一位**用户时段重叠时，系统立即给下一位订单打 `encroachedByReservationId` 标记；
3. 下一位到场，门禁因侵占标记/占用传感器**拒绝放行**（返回 `SEAT_OCCUPIED`，门禁日志与时间轴留痕、附监控事件编号）；
4. 用户提交**投诉**，客服开**工单**（根因可标 `USER_LATE`/`BENEFIT_ABUSE`/`DEVICE_FAULT` 等）；
5. 客服**换座**（目标座做重叠/包月冲突校验），用户在新座位放行；工单可核定补偿并完结。
6. 在场用户**临时延时**若将侵占下一位则直接拒绝，必须先由客服处理冲突；实际超时在离场按分钟计费。

### 其他场景

- 迟到超时：调度器宽限期标记迟到、超 `no-show-minutes`（默认 30）判爽约释放占位。
- 设备故障：门锁故障门禁拒绝（`GATE_DEVICE_FAULT`）；空调故障投诉自动关联未恢复设备事件；
  **门店停电（POWER）** 自动列入所有在场/待进场订单为受影响方、门店置 OUTAGE 拒绝新进场但允许离场，恢复后可走门店责任工单退款补偿。
- 押金：包间 50、夜间座 30、储物柜 20；离场检查设备损坏/储物柜遗留时从押金扣除、余额返还。
- 提前离场退费：已用时长 ≤50%（可配）时按未使用比例退费；门店责任补偿不受此限。
- 包月：固定座与普通预约双向冲突校验；浮动座按月核销 0 元下单；权益滥用可吊销/拉黑。
- 对账修复 `POST /api/ops/reconcile`：修复“已放行但订单未开始”“已完结/退费但座位仍锁”“Redis 镜像与 DB 漂移”。

---

## 四、主要接口（详见 Swagger UI）

| 模块 | 典型接口 |
|---|---|
| 基础资料 | `POST/GET /api/stores`、`POST/GET /api/stores/{id}/seats`、座位调价/维护 |
| 用户/包月 | `POST /api/users`、`POST /api/users/{id}/realname`、`POST /api/passes` |
| 预约 | `POST /api/reservations`（幂等键）、`/{id}/pay|cancel|postpone|extend|reassign`、`/{id}/early-leave-refund`、`/{id}/billing`、`/{id}/timeline` |
| 门禁 | `POST /api/gate/attempt`（IN/OUT，返回放行结果+监控事件编号） |
| 设备/传感器 | `POST /api/stores/{id}/device-events`、`.../{eventId}/resolve`、`POST /api/seats/{id}/sensor-readings` |
| 投诉/工单 | `POST /api/stores/{id}/complaints`、`POST /api/appeals`、`POST /api/tickets`、`/{id}/handle|resolve|evidence` |
| 运营 | `GET /api/ops/stores/{id}/report`、`POST /api/ops/reconcile` |

时间字段统一使用 epoch 毫秒；金额单位元。

## 五、本题验证方式（手动复现实例）

```bash
# 晚到→顺延→占下一位→投诉→换座 等 8 个场景的一键 HTTP 冒烟脚本（需要应用运行在 8080）
# 脚本见仓库 scripts/smoke.py（无第三方依赖，仅用 python3 标准库）
python3 scripts/smoke.py
```

脚本覆盖：连续事件链、提前离场退费释放座位、门店停电批量处置、空调故障投诉关联设备事件、
邻座噪音投诉、包月固定座冲突、运营报表争议来源分类、门锁故障门禁拒绝。

## 六、实际执行过的验证结论

以下结论为在本工作区**实际执行**的结果：

- `mvn test`：**13/13 通过**（连续事件链、门禁前置条件、延时计费、提前离场退费、押金扣除、
  停电、门锁故障、包月冲突、迟到/爽约调度、两类对账修复）。
- `python3 scripts/smoke.py` 对运行中的应用执行 8 大场景 **31 项断言全部通过**。
- 应用在本地 Redis 7 可达时启动日志显示「Coordinator 使用 Redis 分布式实现」；
  Swagger UI `/swagger-ui/index.html` 返回 200，`/v3/api-docs` 返回 OpenAPI 3 文档。

> 注：本工作区未安装 Docker 守护进程，`Dockerfile`/`docker-compose.yml` 已按最佳实践提供但未在本机实际构建；
> 应用本体已通过 `mvn package` 产出可执行 jar 并实际启动验证。
