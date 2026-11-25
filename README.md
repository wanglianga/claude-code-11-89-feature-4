# 供水抢修调度服务（water-repair-service）

暴雨后的凌晨，值守人员从原始报修形成事故、核实隔离范围、派出合格班组并确认恢复。
本项目用 **Java 21 / Spring Boot 3.5 / PostgreSQL** 实现了一个真实可调用的后端服务，
覆盖：报修原文与归属判断、事故确认、图算法候选关阀组合与精确地址影响集合、
班组许可/当班窗口/车辆/工具校验、队伍与阀门操作许可的并发互斥、两种稀缺队伍调度路线、
五类现场回执的顺序约束、复压区间+校时容差核验、复压失败重新派工，压力复核推翻后的
范围/资源占用/通知全链路更正，以及**停水服务补偿核算**：服务账户/合同等级/受水地址/
停水开始与恢复回执关联、同一账户同一自然日的区间并集、每满 4 小时 1 个抵扣每日最多 2 个、
临时抵扣推送与补差说明、结算/出账后的差额分录账本，和客服争议页的抵扣证据反查。

> 项目类型：**后端 API 服务**。自带 OpenAPI 3 文档与 Swagger UI；另提供一个纯静态的
> 客服争议核查页 `/dispute.html`（调用同一套 JSON 接口，不是独立前端工程）。

---

## 1. 一分钟启动

### 方式 A：Docker Compose（一键，推荐验收）

```bash
docker compose up --build
```

- 自动启动 PostgreSQL 18 与应用，应用监听 <http://localhost:8080>
- 宿主机 PostgreSQL 端口冲突时：`DB_HOST_PORT=55432 APP_PORT=18080 docker compose up --build`
- 容器内已配置阿里云 Maven 镜像；样例数据在首次启动时自动播种

打开：

- Swagger UI：<http://localhost:8080/swagger-ui/index.html>
- OpenAPI JSON：<http://localhost:8080/v3/api-docs>
- 健康检查：<http://localhost:8080/api/health>

### 方式 B：本地开发启动（本工作区已验证）

环境：JDK 25（`/opt/jdk-25`，编译目标 21）、Maven 3.9（`/opt/maven`）、
PostgreSQL 18（本机 `127.0.0.1:5432`，库/用户/密码均为 `ccdev`）。

```bash
# 1) 确保 PostgreSQL 可连（容器自带实例）
PGPASSWORD=ccdev /opt/postgresql-18/bin/psql -h 127.0.0.1 -U ccdev -d ccdev -c 'select 1;'

# 2) 开发模式运行（首次会自动建表+播种）
JAVA_HOME=/opt/jdk-25 /opt/maven/bin/mvn -s settings.xml spring-boot:run

# 或打包后运行
JAVA_HOME=/opt/jdk-25 /opt/maven/bin/mvn -s settings.xml -DskipTests package
/opt/jdk-25/bin/java -jar target/water-repair-service.jar
```

如需使用自己的数据库，用环境变量覆盖：

```bash
DB_URL='jdbc:postgresql://HOST:5432/DBNAME' DB_USER=u DB_PASSWORD=p \
  /opt/jdk-25/bin/java -jar target/water-repair-service.jar
```

---

## 2. 测试账号

| 用户名 | 密码 | 角色 | 用途 |
| --- | --- | --- | --- |
| `dispatcher` | `Disp@12345` | DISPATCHER | 值班调度员，全部写操作（受理/确认/派工/复核/关单/reset） |
| `crewsole` | `Crew@12345` | CREW（绑定 `C_SOLE`） | 唯一资质装备齐备的持证维修班班长，可提交回执 |
| `scout` | `Scout@12345` | CREW（绑定 `C_SCOUT`） | 勘查一班 |
| `viewer` | `View@12345` | VIEWER | 只读角色，写接口返回 403 |

登录换取 token：

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"dispatcher","password":"Disp@12345"}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
echo "$TOKEN"
```

之后所有 `/api/**`（除 `/api/auth/login`、`/api/health`）都要带：

```
Authorization: Bearer <token>
```

在 Swagger UI 中点右上角 **Authorize**，粘贴 token 原文（不要带 `Bearer ` 前缀）即可调试。

> 注意：补偿模块里的 `U1`-`U13`（含题面样例账户 `U7`）是**供水服务账户**（与受水地址、
> 合同等级关联），不是登录账号；所有 `/api/compensation/**` 写操作仍用上表的
> `dispatcher` token，查询可用任意登录角色。

---

## 3. 自动回放验证（推荐，纯 Python 标准库）

脚本会先登录、调用 `POST /api/admin/reset` 清库重播，然后真实发起并发 HTTP 请求，
逐步断言题目要求的全部场景。可重复执行。

```bash
# 终端 1：启动服务（见上）

# 终端 2：
python3 scripts/replay.py
# 或
bash scripts/verify.sh
# 远端/自定义地址：
BASE_URL=http://localhost:8080 python3 scripts/replay.py
```

预期结尾：

```
##############################################################################
# 回放断言结果：PASS=171  FAIL=0
# 🎉 全部场景断言通过：同段重拨 / 相邻双支管 / 并发互斥 / 复压重派 / 更正链
#    补偿核算：U7 并集铁律 / 先恢复后更正 / 重叠晚到 / 出账后补报 / 差额账本 / 争议反查 / 两路线同结果
ALL_DONE: true
```

> `bash scripts/verify.sh` 在检测不到本机 8080 服务时，会自动 Maven 打包并后台启动应用，
> 回放结束后关闭它（可用 `AUTO_START=0` 关闭该行为）；已用 `BASE_URL` 指向外部/Compose
> 服务时则只等待并回放，不会动外部实例。

`scripts/replay.py` 覆盖并核对：

1. **同段重拨**：R1/R2 形成 I2 后，R3 热线重拨自动作为 `REDIAL` 来源挂入同一事故；
   事故数、派工数均不增加；重复追加来源幂等。
2. **相邻双支管**：相隔 <100m 的幸福路 8 号（A05→S2）与滨河巷 1 号（A06→S3）形成不同事故；
   路口地标与“幸福里 6 栋”同楼双地址都只给 `AMBIGUOUS [S2,S3]`，不自动合并；
   人工确认保留 v1 AUTO / v2 MANUAL 两级判断历史。
3. **精确影响集合**：单关 `V2 = A01..A05`；单关 `V1 = A01..A05 + A10..A13`（S1+S2，9 户）；
   `V3 = A06..A09`。I2 候选组合为 `[V2(5户), V1(9户)]`，I3 只有 `V3`，I1 只有 `V1`。
4. **资格门槛**：许可缺失（勘查班不能接维修单，422）、当班窗口外、
   车辆不可用、工具不可用都有逐项 `checks`。
5. **唯一持证班组两次并发派单**：两线程用 Barrier 同时请求 I2/I3 的维修派单，
   数据库行锁 + 时间窗重叠校验保证**恰好一个 200、一个 409 `CREW_ALREADY_BOOKED`**，
   409 细节给出占用方派工单/事故/窗口；取消后占用全部 `RELEASED` 且原因可查。
6. **两条调度路线**：
   - 路线 A（勘查先行，I3）：先派 `C_SCOUT`，04:25 到场确认 V3；
     跳序 `REPAIR` 被 `RECEIPT_ORDER_CONFLICT` 拒，勘查单提交 `ISOLATION` 被 422 拒；
     随后锁 `C_SOLE` 的 06:00-08:00 窗口被路线 B 的 I2 先占（409），
     最终改锁 09:30-11:30（与 I2 窗口 09:20 结束半开相接，不冲突）成功。
   - 路线 B（捆绑暂留，I2）：`holdOnly=true` 一次锁定
     `CREW C_SOLE + VALVE_PERMIT POP-V2 + VEHICLE VEH-1 + TOOL-*` 共 5 项 HELD 占用，
     派工单 `PROVISIONAL`，`issue` 后才 `DISPATCHED`。
7. **复压失败→重新派工**：I2 第一周期五回执齐全，`PR-S2-FAIL`（0.14MPa < S2 下限 0.22）
   核验 `FAILED`，关单 409 `REPRESSURE_NOT_PASSED`；`redispatch` 后
   旧派工单 5 项占用以 `REDISPATCH_AFTER_REPRESSURE_FAILURE` 释放，
   `cycleNo=2 / attemptNo=2 / parentAssignmentId` 成链；旧单再交回执报 `RECEIPT_WRONG_CYCLE`；
   新周期用 `PR-S2-OK` 通过并关单，新占用以 `INCIDENT_CLOSED` 释放。
8. **回执归属**：I3 的 `ARRIVAL` 永久归属勘查单/`C_SCOUT`，
   其余四条归属维修单/`C_SOLE`；每条回执都带 `assignmentId/assignmentCode/cycleNo`。
9. **压力复核更正链（I1）**：复核 `OVERTURNS` 把边界 S1/V1 改为 S2/V2：
   更正记录保存新旧边界/阀门/地址全集，`removed=[A10..A13]`、`added=[]`；
   占用侧释放 `POP-V1`（原因含 `PRESSURE_REVIEW_CORRECTION`）并新占 `POP-V2`；
   9 条原始通知全部 `SUPERSEDED` 且挂 `supersededByCorrectionId`，
   新发 5 条 `CORRECTION` + 4 条 `CORRECTION_CANCEL`（A10-A13），
   取消通知用 `replacesNotificationId` 链回旧通知——旧通知不删除、不伪装未发生。
10. **校时容差**：`POST /api/pressure/verify-repressure` 可分别判定
    “压力值超出支管区间”和“校时后采样时刻与复压时刻偏差超过容差”。
11. 终态：所有 `resource_occupancy` 均为 `RELEASED`，3 个事故均 `CLOSED`。
12. **补偿基础与真实回执联动（第 15 节）**：U1-U13 服务账户、三档合同等级播种；
    无状态试算直接锁定 U7 铁律（10-12 + 11-15 → 并集 300 分钟 → 1 个/50 元；
    第二次恢复更正 18:00 → 480 分钟 → 2 个/100 元）；对 I1/I2/I3 执行
    `POST /api/compensation/incidents/{id}/sync`，用 `ISOLATION` 回执作停水开始、
    `PASSED REPRESSURE` 回执作恢复（失败复压回执进来源留痕），U2 同日 I2+I1 两段
    225+60 分钟合计 285 分钟 → 1 个抵扣，重复 sync 幂等。
13. **顺序①先恢复后更正（第 16 节，U7/2026-11-20）**：10-12 先计 0；11-15 到达后
    并集 10-15=300 分钟 → 临时 1 个并推送；`NOTIFICATION`/`WORK_ORDER_CLOSE`
    冒充恢复时间被 `422 RECOVERY_SOURCE_NOT_RECEIPT` 拒绝；复压失败把 U7-T2 置为
    `RECOVERY_REVOKED` 并重算为 0、发 `REASSESSING` 通知，但原 PROVISIONAL 的
    “居民所见 1 个/50 元”不改写；恢复回执校时更正为 18:00 后 → 480 分钟 → 2 个，
    未结算只重算 + 补差说明 1→2，不写账本；结算后冻结 SETTLEMENT 2/100，重复结算幂等。
    争议视图验证每个抵扣反查到的时间片/地址/回执来源（第 2 个抵扣只来自 U7-T2）、
    变更版本链，以及 U7 数据绝不出现邻居 A08/U8。
14. **顺序②重叠事件先后到达（第 17 节，U8/2026-11-21）**：11-15 先到=1；重叠的
    10-12 后到仍为 300 分钟/1（重叠 60 分钟不双计）；同时窗邻居 U7/A07 独立成账；
    16-19 第三段到 → 480 分钟/2；该单地址被移出影响范围后作废、未结算直接重算回 1
    并发 2→1 调减说明；结算 1/20 后 14-19 延伸段晚到 → 追加 `DIFF +1/+20`
    （`OVERLAP_LATE_EVENT`，保留 `preservedSeen=1`），结算快照 1/20 不动、最终 2/40；
    另验证 `scope-removals` 按抢修单+地址批量作废。
15. **顺序③已出账后补报（第 18 节，U9/2026-10 账期）**：BATCH 模式登记 09-13 不推送；
    账期出账 `BILL-202610-U9` 原始 1/20 且幂等；出账后补报 12-17 → 并集 09-17=480 分钟
    → 2，追加 `LATE_REPORT_AFTER_BILL` 差额分录 +1/+20 并挂原账单、自动补差说明；
    账单原始值保持 1/20，调整 +1/+20，当前 2/40；账本恰为 SETTLEMENT+DIFF，重复出账
    不新增分录；争议解释与账本/账单数字逐项一致。
16. **两路线同结果与争议页（第 19 节）**：U10 即时路线（临时推送→更正→日结算）与
    U11 批量路线（BATCH→账期合并）最终都是并集 480 分钟/2 个/40 元、快照 2/40，
    但 U10 有 PROVISIONAL+SUPPLEMENT 过程通知、U11 只有 BILL_ISSUED；
    `/api/compensation/credits/evidence` 反查精确；`/dispute.html` 可访问；
    OpenAPI 含全部补偿/争议路径；VIEWER 可只读、不可写，匿名 401。

---

## 4. 手工 API 走查（核心端点）

所有写接口都需要调度员 token。完整 schema 见 Swagger UI。

```bash
B=http://localhost:8080
H="Authorization: Bearer $TOKEN"; J='Content-Type: application/json'

# 复位样例（回放脚本开头也会调用）
curl -s -X POST $B/api/admin/reset -H "$H" -H "$J" -d '{}'

# 管网与精确影响
curl -s $B/api/network/addresses -H "$H" | python3 -m json.tool
curl -s -X POST $B/api/network/impact -H "$H" -H "$J" -d '{"valveCodes":["V2"]}'
curl -s -X POST $B/api/network/candidate-isolations -H "$H" -H "$J" \
  -d '{"boundarySegmentCodes":["S2"]}'

# 受理报修（自动归属判断）
curl -s -X POST $B/api/reports -H "$H" -H "$J" -d '{
  "callerPhone":"13900000009","rawAddress":"幸福路3号",
  "description":"低压","receivedAt":"2026-11-26T03:05:00+08:00"}'

# 调度确认事故
curl -s -X POST $B/api/incidents -H "$H" -H "$J" -d '{
  "title":"S2 低压","reportIds":[1],"boundarySegmentCodes":["S2"]}'

# 候选方案 → 选 V2 → 通知 A01-A05
curl -s $B/api/incidents/1/candidate-isolations -H "$H"
curl -s -X POST $B/api/incidents/1/isolation-plans -H "$H" -H "$J" -d '{"valveCodes":["V2"]}'
curl -s -X POST $B/api/incidents/1/notify -H "$H"

# 资格预检 → 捆绑暂留派工 → 下达
curl -s -X POST $B/api/incidents/1/dispatch/eligibility -H "$H" -H "$J" -d '{
  "kind":"REPAIR","crewCode":"C_SOLE",
  "windowStart":"2026-11-26T05:00:00+08:00","windowEnd":"2026-11-26T07:00:00+08:00"}'
curl -s -X POST $B/api/incidents/1/dispatch/repair -H "$H" -H "$J" -d '{
  "crewCode":"C_SOLE","strategy":"RESERVE_BUNDLE","holdOnly":true,
  "windowStart":"2026-11-26T05:00:00+08:00","windowEnd":"2026-11-26T07:00:00+08:00"}'

# 按顺序提交回执（assignmentId 从上一步响应取）
curl -s -X POST $B/api/incidents/1/receipts -H "$H" -H "$J" -d '{
  "assignmentId":1,"type":"ARRIVAL","eventAt":"2026-11-26T05:05:00+08:00","note":"到场"}'

# 事故全状态聚合（核对来源/判断/方案/通知/更正/派工/占用/回执）
curl -s $B/api/incidents/1 -H "$H" | python3 -m json.tool
```

停水补偿 U7 一分钟走查（完整三顺序见 `scripts/replay.py` 第 15-19 节）：

```bash
# 无状态验证 U7 并集铁律（不落库）
curl -s -X POST $B/api/compensation/calculate -H "$H" -H "$J" -d '{
  "day":"2026-11-20","contractLevelCode":"CL-PLUS",
  "intervals":[
    {"startAt":"2026-11-20T10:00:00+08:00","endAt":"2026-11-20T12:00:00+08:00"},
    {"startAt":"2026-11-20T11:00:00+08:00","endAt":"2026-11-20T18:00:00+08:00"}]}'
# => unionMinutes=480, creditCount=2, amountCny=100

# 登记两张抢修单区间（带开始/恢复回执来源，恢复即推临时抵扣）
curl -s -X POST $B/api/compensation/intervals -H "$H" -H "$J" -d '{
  "intervalCode":"U7-T1","accountNo":"U7","addressCode":"A07","ticketCode":"WO-U7-01",
  "startAt":"2026-11-20T10:00:00+08:00","endAt":"2026-11-20T12:00:00+08:00",
  "startReceiptCode":"RCPT-S1","recoveryReceiptCode":"RCPT-R1",
  "recoverySourceType":"REPRESSURE_RECEIPT","pushProvisional":true}'

# 客服争议页数据：解释文案 + 抵扣反查地址/时间片/回执 + 通知/账本/账单
curl -s "$B/api/compensation/disputes/U7?day=2026-11-20" -H "$H" | python3 -m json.tool
```

补偿核算关键业务约束（实现于 `compensation/OutageUnionCalculator.java` 与
`CompensationService.java`）：

- **只认真实恢复回执**：恢复时刻必须来自 `REPRESSURE`/恢复类回执（复压须 `PASSED`）；
  传入 `NOTIFICATION`、`WORK_ORDER_CLOSE` 等来源直接 `422`。通知发送时刻与关单时刻
  在任何路径都不会被当作恢复时间。
- **账户日并集**：按 `Asia/Shanghai` 切自然日，只合并同一 `accountNo` 的有效
  `RECOVERED` 区间；重叠分钟只计一次，邻居账户（不同 accountNo/addressCode）不并入。
- **抵扣规则**：`floor(并集分钟 / 240)`，每日封顶 2；每个抵扣在并集时间轴上对应一个
  240 分钟证据块，可反查贡献区间、地址与回执链。
- **三种变更处置**：复压失败 → 区间 `RECOVERY_REVOKED`（有效时长重算，发重新核定通知，
  居民原所见值保留）；校时更正 → 改 `endAt` 并写 revision；地址移出影响范围 → 区间
  `VOID`（支持单条或按事故/抢修单+地址批量）。
- **两种结算状态**：未结算（`ACTIVE`）变更直接重算，只追加临时/补差通知；一旦
  `SETTLED`/`BILLED`，原始结算快照与原始账单行冻结，新差异只能追加 `DIFF` 差额分录
  （带符号增量 + after 快照 + `preservedSeen*`），并同步账单调整额。
- **两条路线同口径**：路线 A 每次恢复推 `PROVISIONAL`、变更发 `SUPPLEMENT`，可日结算；
  路线 B 用 `mode=BATCH` 静默登记，`POST /periods/{yyyy-MM}/issue` 账期统一合并出账。
  两者最终金额都由同一个并集计算器得出。

主要资源分组（Swagger Tag 与之一致）：

| 分组 | 代表端点 |
| --- | --- |
| 01 鉴权 | `POST /api/auth/login` |
| 02 管网图与影响 | `/api/network/*`、`POST /api/network/impact`、`/api/network/candidate-isolations` |
| 03 报修与归属 | `POST /api/reports`、`POST /api/reports/{id}/judgments` |
| 04 事故/方案/通知/复核 | `POST /api/incidents`、`/isolation-plans`、`/notify`、`/pressure-reviews`、`GET /{id}` |
| 05 派工与互斥 | `/dispatch/scout`、`/dispatch/repair`、`/redispatch`、`/api/assignments/{id}/cancel|complete|issue` |
| 06 回执与复压 | `POST /api/incidents/{id}/receipts`、`/close`、`POST /api/pressure/verify-repressure` |
| 07 停水补偿核算 | `/api/compensation/service-accounts`、`/contract-levels`、`POST /api/compensation/intervals`、`.../recovery|correct-recovery|repressure-failure|remove`、`POST /api/compensation/scope-removals`、`POST /api/compensation/days/{account}/{day}/settle`、`POST /api/compensation/periods/{yyyy-MM}/issue`、`/ledger`、`/notices`、`/bills`、`GET /api/compensation/disputes/{account}`、`GET /api/compensation/credits/evidence`、`POST /api/compensation/calculate`、`POST /api/compensation/incidents/{id}/sync` |
| 00 回放支持 | `POST /api/admin/reset` |

客服争议核查页（静态页面，登录后输入账户/日期即可）：<http://localhost:8080/dispute.html>；
争议 JSON 别名：`GET /api/disputes/{accountNo}?day=yyyy-MM-dd`。

---

## 5. 领域模型与关键实现

### 5.1 随题样例管网（`src/main/resources/samples/network.json`）

```
NSRC ─ M0 ─ NT0 ─ F1 ─ NV1(V1) ─ F1D ─ NT1 ─ S1 ─ NE1   地址 A10-A13
                │                    └ F2 ─ NV2(V2) ─ S2 ─ NE2  地址 A01-A05
                └ F3 ─ NV3(V3) ─ S3 ─ NE3                      地址 A06-A09
```

- 节点：`SOURCE / TEE 三通 / VALVE 阀门 / END 末端`；管段连接两节点。
- 地址点唯一映射管段；`幸福里6栋`同时是 A04(S2)/A07(S3) 的别名，
  路口 `LM-XF-BH` 候选 `[S2,S3]`——用于证明“同楼/最近坐标 ≠ 同一事故”。
- 每段配置复压正常区间（如 S2 `[0.22, 0.45] MPa`）与校时容差（600s）。
- 压力读数带设备号、采样时刻 `sampledAt`、校时偏差 `clockOffsetSeconds`；
  约定 **校时后采样时刻 = sampledAt + clockOffsetSeconds**。

影响算法（`network/ImpactService.java`）：把关闭的阀门视为不可穿越节点，
从所有 SOURCE 做可达性遍历；一条管段两端都不可达即判为失压，其上地址全部受影响。
候选方案枚举全部阀门子集（≤4 阀组合），返回能隔离边界管段的“包含意义最小”组合。

### 5.2 归属与事故边界

- 每条报修永久保存原文（地址、描述、照片引用、接听时刻）。
- 自动归属按“最长别名命中 → 路口地标 → 坐标半径”的优先级产出
  `MATCHED / AMBIGUOUS / UNMATCHED` 判断；人工判断只追加新版本，不覆盖历史。
- 报修受理**不会**自动建事故；只有调度调用 `POST /api/incidents` 显式确认边界后才成案。
- 热线重拨带 `redialOfReportId/hotlineCallId`：若原线索已在事故中，
  新线索自动以 `REDIAL` 加入同一事故，不产生新事故或派工。

### 5.3 互斥与资格

- 资格逐项检查：班组 active、申请窗口落在当班时间（按 Asia/Shanghai）、
  所需资质（`Q_INSPECT/Q_REPAIR/Q_VALVE_STD/Q_VALVE_LARGE`，V1 要大阀许可）、
  绑定车辆 `available`、标配工具全部 `available`。
- 占用资源类型：`CREW / VALVE_PERMIT / VEHICLE / TOOL`。
- 预留时先对资源主行 `SELECT ... FOR UPDATE`，再用半开区间条件
  `existing.start < :end AND existing.end > :start AND status='HELD'` 查重叠。
  因此同一队伍（或同一阀门操作票）的两个并发派单在数据库层串行化，恰好一成一败；
  端点相接的窗口（如 09:20 结束 / 09:30 开始）不冲突。
- 释放不删行：占用行置 `RELEASED` 并写 `releaseReason/releasedAt`。

### 5.4 回执周期与更正链

- 顺序固定：`ARRIVAL → ISOLATION → REPAIR → FLUSH → REPRESSURE`，按 `(incident, cycleNo)` 成链。
- `ISOLATION` 及之后必须由 `REPAIR` 派工单提交；勘查单只能交 `ARRIVAL`。
- 复压必须引用压力读数，核验：压力值在该支管区间内、校时后采样时刻与回执时刻偏差 ≤ 容差、
  读数设备管段与当前边界一致；任一不过即 `FAILED`，`/close` 返回 409。
- `redispatch` 释放上一维修单占用、`cycleNo+1`、建立新 `Assignment(attemptNo,parent)`；
  旧周期回执与失败结论原样保留，旧单不能再写新周期。
- 压力复核 `OVERTURNS` 在一个事务内完成：旧方案 `CORRECTED` + 新方案版本、
  事故边界更新、地址集合 diff、阀门操作票“释放旧票/新占新票”（新票冲突则整体回滚）、
  旧通知 `SUPERSEDED` + 更正/取消通知链、`CorrectionRecord` 留全量快照。

### 5.5 停水服务补偿核算（`compensation/`）

| 实体 | 表 | 作用 |
| --- | --- | --- |
| `ContractLevel` | `comp_contract_level` | 合同等级与每个抵扣面额（CL-RES 20 / CL-PLUS 50 / CL-BIZ 100 元） |
| `ServiceAccount` | `comp_service_account` + `comp_account_address` | 服务账户（U1-U13）↔ 一个或多个受水地址，补偿按账户归集 |
| `OutageInterval` | `comp_outage_interval` | 抢修单×地址的实际停水区间，带开始回执、恢复回执（含校时字段）、来源类型与状态（OPEN/RECOVERED/RECOVERY_REVOKED/VOID） |
| `OutageIntervalRevision` | `comp_interval_revision` | 区间 UPSERT/RECOVERY/CORRECT_RECOVERY/REPRESSURE_FAILURE/REMOVE 版本链 |
| `CompensationDay` | `comp_account_day` | 账户×自然日状态（ACTIVE/SETTLED/BILLED）、最新并集/最终值与结算冻结快照 |
| `LedgerEntry` | `comp_ledger_entry` | SETTLEMENT/DIFF 差额分录：带符号增量、after 余额、`preservedSeen*`、账单挂载与幂等键 |
| `CompensationNotice` | `comp_notice` | PROVISIONAL/SUPPLEMENT/REASSESSING/BILL_ISSUED 推送记录，只追加，永久保留居民当时所见值 |
| `AccountBill` | `comp_account_bill` + `comp_bill_item` | 账期账单：原始快照行不可变，`adjustment*` 汇总出账后 DIFF，`current*` 为当前应付 |

核心算法在 `OutageUnionCalculator`（纯 Java，含 6 个单元测试）：按 `Asia/Shanghai`
把区间裁剪到自然日 → 排序合并重叠/相接区间 → 汇总并集分钟 →
`min(2, floor(minutes/240))` → 在并集时间轴上切出每个抵扣的 240 分钟证据块并反查
贡献区间与地址。`CompensationService.reevaluate(...)` 是所有变更的唯一重算收口，
保证临时值、最终值、差额账本、通知与客服解释来自同一次计算；
`CompensationQueryService` 组装争议页视图和逐行中文解释。
`POST /api/compensation/incidents/{id}/sync` 可直接从既有事故的
`ISOLATION`/`REPRESSURE` 回执幂等生成区间，证明“开始/恢复时间只认真实回执”。

---

## 6. 本地开发与验证方式（实际执行记录）

以下验证均在本工作区容器内**实际执行通过**（日期 2026-11-25）：

| 验证 | 命令 | 实际结果 |
| --- | --- | --- |
| 编译 | `JAVA_HOME=/opt/jdk-25 /opt/maven/bin/mvn -s settings.xml compile` | BUILD SUCCESS |
| 单元测试 | `... mvn -s settings.xml test` | `Tests run: 11, Failures: 0, Errors: 0`（原 `ImpactServiceTest` 4 个 + 新增 `OutageUnionCalculatorTest` 7 个，覆盖 U7 300/480 分钟、封顶 2、相接/离散块、跨午夜按自然日裁剪、作废区间、恰好 4 小时） |
| 打包 | `... mvn -s settings.xml -DskipTests package` | 生成 `target/water-repair-service.jar`（约 57 MB fat jar） |
| 真实运行 | `/opt/jdk-25/bin/java -jar target/water-repair-service.jar` | 8080 启动成功，Hibernate 连 PostgreSQL 18.1 建表/播种（含 `comp_*` 新表），日志无 ERROR |
| 端到端回放 | `python3 scripts/replay.py` | `PASS=171 FAIL=0`，连续重复运行结果一致（reset 幂等，含真实双线程并发派单） |
| 一键验证 | `bash scripts/verify.sh` | 对已运行实例：`PASS=171 FAIL=0`、退出码 0；停服后再跑：自动 package+起服+回放+收尾，同样 `PASS=171`、退出码 0 |
| 接口文档 | `GET /v3/api-docs`、`/swagger-ui/index.html` | 均 200，OpenAPI 含全部 `/api/**` 路径（含 `/api/compensation/**`、`/api/disputes/{accountNo}`） |
| 争议页面 | `GET /dispute.html` | 200，静态页与 `/api/compensation/disputes/*` 联调字段一致 |

说明与限制（如实记录）：

- 当前容器**未安装 Docker**，因此 `docker compose up --build` 未能在本机实际执行；
  `Dockerfile`（多阶段 Maven 21 构建 + JRE 21 运行）与 `docker-compose.yml`
  （`postgres:18-alpine` + 健康检查 + 依赖编排）已按标准方式提供，需在有 Docker 的环境验收。
- 回放使用显式 ISO 时间（调度样例事故日 `2026-11-26`；补偿三顺序分别用
  `2026-11-20/21`、账期 `2026-10`、两路线对照 `2026-09`，时区均为 `+08:00`），
  不依赖系统时钟，可重复。
- 短信通道为 `SIMULATED_SMS`：通知落库并在接口/聚合详情中可查，不接真实短信网关。

---

## 7. 目录速览

```
pom.xml / settings.xml / Dockerfile / docker-compose.yml
scripts/replay.py                 # 171 断言的端到端回放（原调度链 114 + 补偿核算 57）
scripts/verify.sh                 # 等服务/可选自启 + 跑 replay，退出码即结论
src/main/resources/static/dispute.html   # 客服争议核查静态页（抵扣反查地址/时间片/回执）
src/main/resources/application.yml
src/main/resources/samples/       # 管网/班组/账号/报修/回执/补偿账户合同样本
src/main/java/com/waterrepair/
  auth/        账号、登录 token、角色拦截
  common/      统一异常、健康检查、首页跳转
  config/      Web/OpenAPI/播种/reset
  network/     节点/管段/阀门/地址/压力读数 + 图影响算法
  report/      报修原文 + 归属判断版本
  incident/    事故/来源/方案/通知/更正/压力复核
  crew/        班组/车辆/工具/阀门操作许可
  dispatch/    派工单 + 资源占用 + 行锁互斥 + 资格校验
  receipt/     五类顺序回执 + 复压核验
  compensation/ 服务账户/合同等级、账户日并集算法、临时抵扣、结算/账单、差额账本、通知、争议反查
src/test/java/com/waterrepair/network/ImpactServiceTest.java
src/test/java/com/waterrepair/compensation/OutageUnionCalculatorTest.java
```
