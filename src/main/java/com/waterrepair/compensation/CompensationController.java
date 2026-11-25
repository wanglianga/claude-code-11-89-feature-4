package com.waterrepair.compensation;

import com.waterrepair.auth.AuthService;
import com.waterrepair.auth.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 停水服务补偿核算接口：服务账户/合同等级、停水区间与回执来源、临时抵扣、
 * 结算/账期账单、差额账本、通知与客服争议反查。
 */
@Tag(name = "07-停水补偿核算与争议反查")
@RestController
public class CompensationController {

    private final CompensationService service;
    private final CompensationQueryService queryService;

    public CompensationController(CompensationService service,
                                  CompensationQueryService queryService) {
        this.service = service;
        this.queryService = queryService;
    }

    // ---------------- 基础数据 ----------------

    @Operation(summary = "合同等级列表（含每个服务抵扣的金额）")
    @GetMapping("/api/compensation/contract-levels")
    public List<ContractLevel> levels() {
        return service.levels();
    }

    @Operation(summary = "服务账户列表（含关联受水地址与合同等级）")
    @GetMapping({"/api/compensation/service-accounts", "/api/compensation/accounts"})
    public List<ServiceAccount> accounts() {
        return service.accounts();
    }

    @Operation(summary = "服务账户详情")
    @GetMapping("/api/compensation/service-accounts/{accountNo}")
    public ServiceAccount account(@PathVariable String accountNo) {
        return service.account(accountNo);
    }

    @Operation(summary = "按受水地址反查服务账户")
    @GetMapping("/api/compensation/addresses/{addressCode}/service-accounts")
    public List<ServiceAccount> accountsAtAddress(@PathVariable String addressCode) {
        return service.accountsAtAddress(addressCode);
    }

    @Operation(summary = "新建服务账户（回放样例已内置 U1-U13，一般无需调用）")
    @Roles({"DISPATCHER"})
    @PostMapping("/api/compensation/service-accounts")
    public ServiceAccount createAccount(@RequestBody CompensationDtos.CreateAccountRequest req) {
        return service.createAccount(req);
    }

    // ---------------- 无状态试算 ----------------

    @Operation(summary = "无状态试算：直接提交区间数组，验证账户日并集/4小时抵扣/每日2个上限")
    @PostMapping("/api/compensation/calculate")
    public Map<String, Object> calculate(@RequestBody CompensationDtos.CalculateRequest req) {
        return queryService.calculateStateless(req);
    }

    // ---------------- 停水区间与回执来源 ----------------

    @Operation(summary = "登记/幂等更新一条停水区间（关联账户、地址、抢修单、开始/恢复回执），"
            + "可立即推临时抵扣；通知/关单时间不允许作为恢复时间")
    @Roles({"DISPATCHER"})
    @PostMapping("/api/compensation/intervals")
    public Map<String, Object> upsertInterval(
            @RequestBody CompensationDtos.UpsertIntervalRequest req, HttpServletRequest http) {
        return service.upsertInterval(req, operator(http));
    }

    @Operation(summary = "停水区间列表（可按 accountNo/day/addressCode 过滤）")
    @GetMapping("/api/compensation/intervals")
    public List<OutageInterval> intervals(@RequestParam(required = false) String accountNo,
                                          @RequestParam(required = false) String day,
                                          @RequestParam(required = false) String addressCode) {
        return service.intervals(accountNo, parseDayOrNull(day), addressCode);
    }

    @Operation(summary = "单条停水区间详情")
    @GetMapping("/api/compensation/intervals/{code}")
    public OutageInterval interval(@PathVariable String code) {
        return service.requireInterval(code);
    }

    @Operation(summary = "区间变更留痕（恢复/校时/复压失败/移出作废的完整版本链）")
    @GetMapping("/api/compensation/intervals/{code}/revisions")
    public List<OutageIntervalRevision> revisions(@PathVariable String code) {
        return service.revisions(code);
    }

    @Operation(summary = "登记恢复回执：按账户日并集重算并给居民推临时抵扣")
    @Roles({"DISPATCHER"})
    @PostMapping("/api/compensation/intervals/{code}/recovery")
    public Map<String, Object> recovery(@PathVariable String code,
                                        @RequestBody CompensationDtos.RecoveryRequest req,
                                        HttpServletRequest http) {
        return outcome(service.recordRecovery(code, req, operator(http)));
    }

    @Operation(summary = "恢复回执校时更正：未结算直接重算；已结算追加差额分录并发生效补差说明")
    @Roles({"DISPATCHER"})
    @PostMapping("/api/compensation/intervals/{code}/correct-recovery")
    public Map<String, Object> correctRecovery(
            @PathVariable String code,
            @RequestBody CompensationDtos.CorrectRecoveryRequest req, HttpServletRequest http) {
        return outcome(service.correctRecovery(code, req, operator(http)));
    }

    @Operation(summary = "复压失败：原恢复不成立、停水重新开始/待核定，重算并保留居民原所见值")
    @Roles({"DISPATCHER"})
    @PostMapping("/api/compensation/intervals/{code}/repressure-failure")
    public Map<String, Object> repressureFailure(
            @PathVariable String code,
            @RequestBody CompensationDtos.RepressureFailureRequest req, HttpServletRequest http) {
        return outcome(service.repressureFailure(code, req, operator(http)));
    }

    @Operation(summary = "作废单条区间（该地址被移出影响范围）：未结算重算，已结算走差额分录")
    @Roles({"DISPATCHER"})
    @PostMapping("/api/compensation/intervals/{code}/remove")
    public Map<String, Object> remove(
            @PathVariable String code,
            @RequestBody(required = false) CompensationDtos.RemoveIntervalRequest req,
            HttpServletRequest http) {
        CompensationDtos.RemoveIntervalRequest body = req == null
                ? new CompensationDtos.RemoveIntervalRequest("地址被移出已确认影响范围", null) : req;
        return outcome(service.removeInterval(code, body, operator(http)));
    }

    @Operation(summary = "按 事故/抢修单 + 地址 批量作废区间（压力复核移出地址时调用）")
    @Roles({"DISPATCHER"})
    @PostMapping("/api/compensation/scope-removals")
    public Map<String, Object> scopeRemoval(
            @RequestBody CompensationDtos.ScopeRemovalRequest req, HttpServletRequest http) {
        return service.scopeRemoval(req, operator(http));
    }

    @Operation(summary = "从真实事故的 ISOLATION 回执与 PASSED REPRESSURE 回执幂等生成补偿区间")
    @Roles({"DISPATCHER"})
    @PostMapping("/api/compensation/incidents/{incidentId}/sync")
    public Map<String, Object> syncIncident(@PathVariable Long incidentId,
                                           HttpServletRequest http) {
        return service.syncIncident(incidentId, operator(http));
    }

    // ---------------- 账户日核算 / 结算 ----------------

    @Operation(summary = "账户×自然日核算视图：并集块、抵扣证据、通知、差额分录、账单与客服解释")
    @GetMapping("/api/compensation/days/{accountNo}/{day}")
    public Map<String, Object> dayView(@PathVariable String accountNo,
                                       @PathVariable String day) {
        return queryService.dayView(accountNo, LocalDate.parse(day));
    }

    @Operation(summary = "立即结算该账户日（路线 A 的落袋动作）；幂等，重复结算不产生重复分录")
    @Roles({"DISPATCHER"})
    @PostMapping("/api/compensation/days/{accountNo}/{day}/settle")
    public Map<String, Object> settle(@PathVariable String accountNo,
                                      @PathVariable String day,
                                      @RequestBody(required = false) CompensationDtos.SettleDayRequest req,
                                      HttpServletRequest http) {
        String route = req == null ? "IMMEDIATE" : req.route();
        String note = req == null ? null : req.note();
        LedgerEntry entry = service.settleDay(accountNo, LocalDate.parse(day), route, note,
                operator(http));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("settlementEntry", entry);
        result.put("dayView", queryService.dayView(accountNo, LocalDate.parse(day)));
        return result;
    }

    // ---------------- 账期 / 账单（路线 B） ----------------

    @Operation(summary = "账期结束统一合并区间并出账（路线 B）；同一账期重复调用幂等")
    @Roles({"DISPATCHER"})
    @PostMapping("/api/compensation/periods/{period}/issue")
    public Map<String, Object> issuePeriod(@PathVariable String period,
                                           @RequestBody(required = false) CompensationDtos.IssuePeriodRequest req,
                                           HttpServletRequest http) {
        Boolean push = req == null ? null : req.pushBillNotice();
        return service.issuePeriod(period, push, operator(http));
    }

    @Operation(summary = "账单列表，可按 accountNo / period(yyyy-MM) 过滤")
    @GetMapping("/api/compensation/bills")
    public List<AccountBill> bills(@RequestParam(required = false) String accountNo,
                                    @RequestParam(required = false) String period) {
        return service.bills(accountNo, period);
    }

    @Operation(summary = "账单详情（原始快照 + 出账后差额累计 + 当前应付）")
    @GetMapping("/api/compensation/bills/{billNo}")
    public AccountBill bill(@PathVariable String billNo) {
        return service.bill(billNo);
    }

    // ---------------- 账本 / 通知 ----------------

    @Operation(summary = "差额账本分录（可按 accountNo/day 过滤）")
    @GetMapping("/api/compensation/ledger")
    public List<LedgerEntry> ledger(@RequestParam(required = false) String accountNo,
                                    @RequestParam(required = false) String day) {
        return service.ledger(accountNo, parseDayOrNull(day));
    }

    @Operation(summary = "居民通知/临时抵扣/补差说明推送记录（只追加，保留居民原所见值）")
    @GetMapping("/api/compensation/notices")
    public List<CompensationNotice> notices(@RequestParam(required = false) String accountNo,
                                             @RequestParam(required = false) String day) {
        return service.notices(accountNo, parseDayOrNull(day));
    }

    // ---------------- 争议页 / 反查 ----------------

    @Operation(summary = "客服争议页数据：从抵扣反查地址、时间片、回执来源，并给出一致解释；"
            + "不传 day 时返回该账户全部账日")
    @GetMapping("/api/compensation/disputes/{accountNo}")
    public Map<String, Object> dispute(@PathVariable String accountNo,
                                        @RequestParam(required = false) String day) {
        return queryService.dispute(accountNo, parseDayOrNull(day));
    }

    @Operation(summary = "争议页数据（query 参数风格别名）")
    @GetMapping("/api/compensation/disputes")
    public Map<String, Object> disputeByQuery(@RequestParam String accountNo,
                                              @RequestParam(required = false) String day) {
        return queryService.dispute(accountNo, parseDayOrNull(day));
    }

    @Operation(summary = "争议页路径别名 /api/disputes/{accountNo}")
    @GetMapping("/api/disputes/{accountNo}")
    public Map<String, Object> disputeAlias(@PathVariable String accountNo,
                                            @RequestParam(required = false) String day) {
        return queryService.dispute(accountNo, parseDayOrNull(day));
    }

    @Operation(summary = "从某个抵扣序号反查其覆盖时间片、贡献地址与回执来源")
    @GetMapping("/api/compensation/credits/evidence")
    public Map<String, Object> creditEvidence(@RequestParam String accountNo,
                                              @RequestParam String day,
                                              @RequestParam(defaultValue = "1") int ordinal) {
        return queryService.creditEvidence(accountNo, LocalDate.parse(day), ordinal);
    }

    // ---------------- helpers ----------------

    private Map<String, Object> outcome(CompensationService.MutationOutcome o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("day", o.day());
        m.put("calc", o.calc());
        m.put("newLedgerEntries", o.newLedgerEntries());
        m.put("newNotices", o.newNotices());
        m.put("dayView", queryService.dayView(o.day().getAccountNo(), o.day().getDay()));
        return m;
    }

    private String operator(HttpServletRequest http) {
        Object u = http.getAttribute(AuthService.ATTR_USERNAME);
        return u == null ? "system" : u.toString();
    }

    private LocalDate parseDayOrNull(String day) {
        if (day == null || day.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(day);
        } catch (Exception e) {
            throw com.waterrepair.common.ApiException.badRequest(
                    "BAD_DAY", "day 格式应为 yyyy-MM-dd: " + day);
        }
    }
}
