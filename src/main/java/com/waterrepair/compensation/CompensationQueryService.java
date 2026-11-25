package com.waterrepair.compensation;

import com.waterrepair.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 组装账户日核算视图与客服“争议页”解释。所有解释文案中的数字都来自
 * {@link OutageUnionCalculator} 的同一次计算，确保临时/最终数值、账本与口径完全一致。
 */
@Service
public class CompensationQueryService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final CompensationService service;
    private final ServiceAccountRepository accountRepo;
    private final ContractLevelRepository levelRepo;
    private final OutageIntervalRepository intervalRepo;
    private final CompensationDayRepository dayRepo;
    private final LedgerEntryRepository ledgerRepo;
    private final CompensationNoticeRepository noticeRepo;
    private final AccountBillRepository billRepo;

    public CompensationQueryService(CompensationService service,
                                    ServiceAccountRepository accountRepo,
                                    ContractLevelRepository levelRepo,
                                    OutageIntervalRepository intervalRepo,
                                    CompensationDayRepository dayRepo,
                                    LedgerEntryRepository ledgerRepo,
                                    CompensationNoticeRepository noticeRepo,
                                    AccountBillRepository billRepo) {
        this.service = service;
        this.accountRepo = accountRepo;
        this.levelRepo = levelRepo;
        this.intervalRepo = intervalRepo;
        this.dayRepo = dayRepo;
        this.ledgerRepo = ledgerRepo;
        this.noticeRepo = noticeRepo;
        this.billRepo = billRepo;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> dayView(String accountNo, LocalDate day) {
        ServiceAccount account = service.requireAccount(accountNo);
        ContractLevel level = levelRepo.findById(account.getContractLevelCode()).orElse(null);
        int unit = level == null ? 0 : level.getCreditAmountCny();

        List<OutageInterval> intervals = intervalRepo.findIntersectingDay(accountNo,
                day.atStartOfDay(OutageUnionCalculator.SETTLEMENT_ZONE).toOffsetDateTime(),
                day.plusDays(1).atStartOfDay(OutageUnionCalculator.SETTLEMENT_ZONE).toOffsetDateTime());
        OutageUnionCalculator.CalcResult calc = OutageUnionCalculator.calculate(day,
                intervals.stream().map(i -> new OutageUnionCalculator.CalcSlice(
                        i.getIntervalCode(), i.getAccountNo(), i.getAddressCode(),
                        i.getStartAt(), i.getEndAt(), i.getState())).toList());

        CompensationDay row = dayRepo.findByAccountNoAndDay(accountNo, day).orElse(null);
        List<LedgerEntry> ledger = ledgerRepo.findByAccountNoAndDayOrderByIdAsc(accountNo, day);
        // 当日通知之外，若该日已进账期账单，把账期首日出具的 BILL_ISSUED 通知也带到争议视图，
        // 客服在具体账日页就能看到居民收到的出账通知。
        List<CompensationNotice> notices = new ArrayList<>(
                noticeRepo.findByAccountNoAndDayOrderByIdAsc(accountNo, day));
        if (row != null && row.getBillNo() != null) {
            LocalDate periodStart = java.time.YearMonth.from(day).atDay(1);
            for (CompensationNotice n :
                    noticeRepo.findByAccountNoAndDayOrderByIdAsc(accountNo, periodStart)) {
                if ("BILL_ISSUED".equals(n.getKind())
                        && notices.stream().noneMatch(x -> x.getId().equals(n.getId()))) {
                    notices.add(n);
                }
            }
        }
        AccountBill bill = row == null || row.getBillId() == null ? null
                : billRepo.findById(row.getBillId()).orElse(null);

        long rawMinutes = intervals.stream()
                .filter(i -> "RECOVERED".equals(i.getState()) && i.getEndAt() != null)
                .mapToLong(i -> java.time.Duration.between(i.getStartAt(), i.getEndAt()).toMinutes())
                .sum();

        long openCount = intervals.stream().filter(i -> "OPEN".equals(i.getState())).count();
        long revokedCount = intervals.stream().filter(i -> "RECOVERY_REVOKED".equals(i.getState())).count();
        long voidCount = intervals.stream().filter(i -> "VOID".equals(i.getState())).count();

        List<String> lines = buildExplanation(account, level, intervals, calc, rawMinutes,
                row, ledger, notices, bill, openCount, revokedCount, voidCount);

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("accountNo", accountNo);
        view.put("day", day.toString());
        view.put("timezone", OutageUnionCalculator.SETTLEMENT_ZONE.getId());
        view.put("status", row == null ? "ACTIVE" : row.getStatus());
        view.put("rule", Map.of(
                "unionFirst", true,
                "fullHoursPerCredit", OutageUnionCalculator.MINUTES_PER_CREDIT / 60,
                "minutesPerCredit", OutageUnionCalculator.MINUTES_PER_CREDIT,
                "maxCreditsPerDay", OutageUnionCalculator.MAX_CREDITS_PER_DAY,
                "naturalDayTimezone", OutageUnionCalculator.SETTLEMENT_ZONE.getId(),
                "recoveryTimeSource", "ONLY_RECOVERY_RECEIPT_PASSED",
                "notificationOrCloseAllowedAsRecoveryTime", false));
        view.put("contractLevel", level);
        view.put("unitAmountCny", unit);

        view.put("unionBlocks", calc.unionBlocks());
        view.put("unionMinutes", calc.unionMinutes());
        view.put("unionHours", calc.unionHours());
        view.put("rawRecoveredMinutes", rawMinutes);
        view.put("overlapSavedMinutes", Math.max(0, rawMinutes - calc.unionMinutes()));
        view.put("creditsBeforeDailyCap", calc.creditsBeforeDailyCap());
        view.put("dailyCapApplied", calc.dailyCapApplied());
        view.put("uncountedMinutes", calc.uncountedMinutes());

        int credits = calc.creditCount();
        view.put("creditCount", credits);
        view.put("finalCreditCount", credits);
        view.put("amountCny", credits * unit);
        view.put("finalAmountCny", credits * unit);
        view.put("credits", buildCreditViews(calc, unit, intervals));

        if (row != null) {
            view.put("provisionalCreditCount", row.getProvisionalCreditCount());
            view.put("provisionalAmountCny", row.getProvisionalAmountCny());
            view.put("settledCreditCount", row.getSettledCreditCount());
            view.put("settledAmountCny", row.getSettledAmountCny());
            view.put("settledAt", row.getSettledAt());
            view.put("billNo", row.getBillNo());
        }
        view.put("settled", row != null && row.getSettlementEntryId() != null);
        view.put("billed", row != null && "BILLED".equals(row.getStatus()));

        view.put("intervalCount", intervals.size());
        view.put("rawIntervalCount", intervals.size());
        view.put("activeIntervalCount", intervals.stream().filter(i -> !"VOID".equals(i.getState())).count());
        view.put("recoveredIntervalCount",
                intervals.stream().filter(i -> "RECOVERED".equals(i.getState())).count());
        view.put("openIntervalCount", openCount);
        view.put("recoveryRevokedCount", revokedCount);
        view.put("voidedIntervalCount", voidCount);
        view.put("intervals", intervals);
        view.put("voidedIntervals", intervals.stream().filter(i -> "VOID".equals(i.getState())).toList());
        view.put("ledgerEntries", ledger);
        view.put("notices", notices);
        view.put("bill", bill);

        view.put("explanationLines", lines);
        view.put("csExplanation", String.join("\n", lines));
        return view;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> dispute(String accountNo, LocalDate day) {
        ServiceAccount account = service.requireAccount(accountNo);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("page", "water-repair-compensation-dispute");
        result.put("account", account);
        result.put("contractLevel",
                levelRepo.findById(account.getContractLevelCode()).orElse(null));
        if (day != null) {
            result.put("day", day.toString());
            result.put("caseView", dayView(accountNo, day));
        } else {
            List<Map<String, Object>> days = new ArrayList<>();
            for (CompensationDay d : dayRepo.findByAccountNoOrderByDayAsc(accountNo)) {
                days.add(dayView(accountNo, d.getDay()));
            }
            result.put("days", days);
            result.put("ledgerEntries", ledgerRepo.findByAccountNoOrderByIdAsc(accountNo));
            result.put("notices", noticeRepo.findByAccountNoOrderByIdAsc(accountNo));
            result.put("bills", billRepo.findByAccountNoOrderByPeriodDesc(accountNo));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> creditEvidence(String accountNo, LocalDate day, int ordinal) {
        Map<String, Object> view = dayView(accountNo, day);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> credits = (List<Map<String, Object>>) view.get("credits");
        return credits.stream()
                .filter(c -> ((Number) c.get("ordinal")).intValue() == ordinal)
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("CREDIT_EVIDENCE_NOT_FOUND",
                        accountNo + " " + day + " 不存在第 " + ordinal + " 个抵扣证据"));
    }

    /** 无状态试算：不落库，直接验证任意区间数组的并集与抵扣。 */
    public Map<String, Object> calculateStateless(CompensationDtos.CalculateRequest req) {
        LocalDate day;
        if (req.day() != null && !req.day().isBlank()) {
            day = LocalDate.parse(req.day());
        } else {
            OffsetDateTime any = req.intervals() == null ? null : req.intervals().stream()
                    .map(CompensationDtos.UpsertIntervalRequest::startAt)
                    .filter(java.util.Objects::nonNull).findFirst().orElse(null);
            if (any == null) {
                throw ApiException.badRequest("DAY_REQUIRED", "请提供 day 或至少一个带 startAt 的区间");
            }
            day = any.atZoneSameInstant(OutageUnionCalculator.SETTLEMENT_ZONE).toLocalDate();
        }
        List<OutageUnionCalculator.CalcSlice> slices = new ArrayList<>();
        if (req.intervals() != null) {
            for (CompensationDtos.UpsertIntervalRequest i : req.intervals()) {
                slices.add(new OutageUnionCalculator.CalcSlice(
                        i.intervalCode(), i.accountNo(), i.addressCode(),
                        i.startAt(), i.endAt(),
                        i.endAt() == null ? "OPEN" : "RECOVERED"));
            }
        }
        int max = req.maxCreditsPerDay() == null
                ? OutageUnionCalculator.MAX_CREDITS_PER_DAY : req.maxCreditsPerDay();
        OutageUnionCalculator.CalcResult calc = OutageUnionCalculator.calculate(day, slices, max);
        int unit = 0;
        if (req.contractLevelCode() != null) {
            unit = levelRepo.findById(req.contractLevelCode())
                    .map(ContractLevel::getCreditAmountCny).orElse(0);
        } else if (req.accountNo() != null && accountRepo.findByAccountNo(req.accountNo()).isPresent()) {
            unit = service.unitAmount(req.accountNo());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("day", day.toString());
        result.put("unionMinutes", calc.unionMinutes());
        result.put("unionHours", calc.unionHours());
        result.put("unionBlocks", calc.unionBlocks());
        result.put("creditsBeforeDailyCap", calc.creditsBeforeDailyCap());
        result.put("creditCount", calc.creditCount());
        result.put("maxCreditsPerDay", max);
        result.put("unitAmountCny", unit);
        result.put("amountCny", calc.creditCount() * unit);
        result.put("uncountedMinutes", calc.uncountedMinutes());
        result.put("credits", buildCreditViews(calc, unit, List.of()));
        return result;
    }

    // ------------------------------------------------------------------
    // 内部：抵扣证据视图 + 客服解释
    // ------------------------------------------------------------------

    private List<Map<String, Object>> buildCreditViews(OutageUnionCalculator.CalcResult calc,
                                                       int unit,
                                                       List<OutageInterval> allIntervals) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (OutageUnionCalculator.CreditEvidence ce : calc.credits()) {
            List<OutageInterval> contributors = allIntervals.stream()
                    .filter(i -> ce.intervalCodes().contains(i.getIntervalCode())).toList();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ordinal", ce.ordinal());
            m.put("coveredStartAt", ce.coveredStartAt());
            m.put("coveredEndAt", ce.coveredEndAt());
            m.put("countedMinutes", ce.countedMinutes());
            m.put("amountCny", unit);
            m.put("spans", ce.spans());
            m.put("intervalCodes", ce.intervalCodes());
            m.put("addressCodes", ce.addressCodes());
            // 从抵扣反查到的具体地址、时间片与回执来源。
            m.put("provenance", contributors.stream().map(i -> {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("intervalCode", i.getIntervalCode());
                p.put("ticketCode", i.getTicketCode());
                p.put("incidentId", i.getIncidentId());
                p.put("addressCode", i.getAddressCode());
                p.put("accountNo", i.getAccountNo());
                p.put("startAt", i.getStartAt());
                p.put("endAt", i.getEndAt());
                p.put("startReceiptId", i.getStartReceiptId());
                p.put("startReceiptCode", i.getStartReceiptCode());
                p.put("startReceiptType", i.getStartReceiptType());
                p.put("startReceiptAt", i.getStartReceiptAt());
                p.put("recoveryReceiptId", i.getRecoveryReceiptId());
                p.put("recoveryReceiptCode", i.getRecoveryReceiptCode());
                p.put("recoveryReceiptType", i.getRecoveryReceiptType());
                p.put("recoveryReceiptAt", i.getRecoveryReceiptAt());
                p.put("recoveryCorrectedAt", i.getRecoveryCorrectedAt());
                p.put("effectiveRecoveryAt", i.getEndAt());
                p.put("recoverySourceType", i.getRecoverySourceType());
                p.put("repressureStatus", i.getRepressureStatus());
                return p;
            }).toList());
            list.add(m);
        }
        return list;
    }

    private List<String> buildExplanation(ServiceAccount account, ContractLevel level,
                                          List<OutageInterval> intervals,
                                          OutageUnionCalculator.CalcResult calc,
                                          long rawMinutes, CompensationDay row,
                                          List<LedgerEntry> ledger,
                                          List<CompensationNotice> notices,
                                          AccountBill bill,
                                          long openCount, long revokedCount, long voidCount) {
        List<String> L = new ArrayList<>();
        String levelName = level == null ? account.getContractLevelCode()
                : level.getName() + "（" + level.getCode() + "，每个抵扣 "
                + level.getCreditAmountCny() + " 元）";
        L.add("【账户与合同】服务账户 " + account.getAccountNo()
                + "（" + nz(account.getCustomerName()) + "），合同等级：" + levelName + "。");
        L.add("【计量口径】只统计该账户名下地址 " + account.getAddressCodes()
                + " 在同一自然日 " + calc.day() + "（" + OutageUnionCalculator.SETTLEMENT_ZONE.getId()
                + "）的实际停水区间；先对时间区间求并集，再每满 4 小时计 1 个服务抵扣，每日最多 2 个。"
                + "邻居账户的地址/区间属于其他账户，不会并入本账户。");

        L.add("【采信的抢修单与回执来源】");
        if (intervals.isEmpty()) {
            L.add("  - 当前没有任何已登记的停水区间。");
        }
        for (OutageInterval i : intervals) {
            StringBuilder sb = new StringBuilder("  - 区间 ").append(i.getIntervalCode());
            if (i.getTicketCode() != null) {
                sb.append(" / 抢修单 ").append(i.getTicketCode());
            }
            if (i.getIncidentId() != null) {
                sb.append(" / 事故#").append(i.getIncidentId());
            }
            sb.append("，地址 ").append(i.getAddressCode())
                    .append("，停水 ").append(fmt(i.getStartAt())).append(" ~ ");
            if (i.getEndAt() != null) {
                sb.append(fmt(i.getEndAt()));
            } else {
                sb.append("恢复时间尚未成立");
            }
            sb.append("，状态 ").append(stateName(i.getState())).append("。");
            if (i.getStartReceiptCode() != null || i.getStartReceiptType() != null) {
                sb.append("开始依据 ").append(nz(i.getStartReceiptType()))
                        .append(" 回执 ").append(nz(i.getStartReceiptCode()));
                if (i.getStartReceiptAt() != null) {
                    sb.append("（").append(fmt(i.getStartReceiptAt())).append("）");
                }
                sb.append("；");
            }
            if ("RECOVERED".equals(i.getState())) {
                sb.append("恢复依据 ").append(nz(i.getRecoveryReceiptType()))
                        .append(" 回执 ").append(nz(i.getRecoveryReceiptCode()))
                        .append("，来源 ").append(nz(i.getRecoverySourceType()));
                if (i.getRecoveryReceiptAt() != null) {
                    sb.append("，回执时刻 ").append(fmt(i.getRecoveryReceiptAt()));
                }
                if (i.getRecoveryCorrectedAt() != null) {
                    sb.append("，校时/更正后 ").append(fmt(i.getRecoveryCorrectedAt()));
                }
                if (i.getRepressureStatus() != null) {
                    sb.append("，复压核验 ").append(i.getRepressureStatus());
                }
                sb.append("。");
            } else if ("RECOVERY_REVOKED".equals(i.getState())) {
                sb.append("原恢复因复压失败（失败回执 ")
                        .append(nz(i.getFailedReceiptCode())).append("）被推翻，等待新的恢复回执。");
            } else if ("VOID".equals(i.getState())) {
                sb.append("该时间片已作废：").append(nz(i.getVoidReason()));
                if (i.getVoidCorrectionRef() != null) {
                    sb.append("（更正依据 ").append(i.getVoidCorrectionRef()).append("）");
                }
            } else {
                sb.append("尚无通过复压核验的恢复回执；通知发送时刻、工单关闭时刻均不能作为恢复时间。");
            }
            L.add(sb.toString());
        }

        L.add("【时间并集】各有效区间原始时长合计 " + rawMinutes + " 分钟，重叠/相接被合并的部分为 "
                + Math.max(0, rawMinutes - calc.unionMinutes()) + " 分钟（不重复计算）；并集为 "
                + formatBlocks(calc) + "，合计 " + calc.unionMinutes() + " 分钟（"
                + calc.unionHours() + " 小时）。");
        L.add("【抵扣计算】floor(" + calc.unionMinutes() + " / 240) = "
                + calc.creditsBeforeDailyCap() + " 个"
                + (calc.dailyCapApplied() ? "，触发每日 2 个上限后为 " + calc.creditCount() + " 个"
                : "，未超每日 2 个上限")
                + "；未满 4 小时的零头 " + calc.uncountedMinutes() + " 分钟不计。最终 "
                + calc.creditCount() + " 个抵扣 × "
                + (level == null ? 0 : level.getCreditAmountCny()) + " 元 = "
                + (calc.creditCount() * (level == null ? 0 : level.getCreditAmountCny())) + " 元。");

        for (OutageUnionCalculator.CreditEvidence ce : calc.credits()) {
            List<String> addrs = ce.addressCodes().stream().sorted().toList();
            L.add("  ↳ 第 " + ce.ordinal() + " 个抵扣覆盖并集时间片 "
                    + fmt(ce.coveredStartAt()) + " ~ " + fmt(ce.coveredEndAt())
                    + "（计 " + ce.countedMinutes() + " 分钟），来自地址 " + addrs
                    + "，可反查区间 " + ce.intervalCodes() + " 及其开始/恢复回执来源。");
        }

        if (revokedCount > 0 || openCount > 0 || voidCount > 0) {
            L.add("【未计入项】恢复未成立/待重新核定 " + (openCount + revokedCount)
                    + " 段、因地址移出影响范围作废 " + voidCount
                    + " 段；这些区间不参与当前并集，待有效恢复回执或更正后重算。");
        }

        if (!notices.isEmpty()) {
            L.add("【居民看到的通知】共 " + notices.size() + " 条（只追加不修改）：");
            for (CompensationNotice n : notices) {
                L.add("  - #" + n.getId() + " " + n.getKind() + " " + fmt(n.getCreatedAt())
                        + "：居民看到 " + n.getSeenCreditCount() + " 个抵扣 / "
                        + n.getSeenAmountCny() + " 元 —— " + nz(n.getTitle()));
            }
        }
        if (!ledger.isEmpty()) {
            L.add("【差额账本】共 " + ledger.size() + " 条分录：");
            for (LedgerEntry e : ledger) {
                L.add("  - " + e.getEntryNo() + " " + e.getEntryType() + " / " + e.getReasonCode()
                        + "：抵扣差额 " + signed(e.getCreditDelta()) + "，金额差额 "
                        + signed(e.getAmountDeltaCny()) + " 元；更正后余额 "
                        + e.getCreditAfter() + " 个 / " + e.getAmountAfterCny() + " 元"
                        + (e.getBillNo() == null ? "" : "（账单 " + e.getBillNo() + "）") + "。");
            }
        }
        if (bill != null) {
            L.add("【账单】" + bill.getBillNo() + "（账期 " + bill.getPeriod() + "）原始 "
                    + bill.getOriginalCreditCount() + " 个 / " + bill.getOriginalAmountCny()
                    + " 元保持不变；出账后差额累计 " + signed(bill.getAdjustmentCreditCount())
                    + " 个 / " + signed(bill.getAdjustmentAmountCny()) + " 元；当前应付 "
                    + bill.getCurrentCreditCount() + " 个 / " + bill.getCurrentAmountCny() + " 元。");
        }
        if (row != null && row.getSettledCreditCount() != null) {
            L.add("【结算快照】结算/出账时居民确认的原始数值为 " + row.getSettledCreditCount()
                    + " 个抵扣 / " + row.getSettledAmountCny()
                    + " 元，该数值被永久保留，后续更正只通过差额分录调整。");
        }
        return L;
    }

    private String formatBlocks(OutageUnionCalculator.CalcResult calc) {
        if (calc.unionBlocks().isEmpty()) {
            return "无有效时长块";
        }
        List<String> parts = new ArrayList<>();
        for (OutageUnionCalculator.TimeSpan b : calc.unionBlocks()) {
            parts.add(fmt(b.startAt()) + "-" + hm(b.endAt()) + "(" + b.minutes() + "分钟)");
        }
        return String.join("、", parts);
    }

    private static String fmt(OffsetDateTime t) {
        return t == null ? "—" : t.atZoneSameInstant(OutageUnionCalculator.SETTLEMENT_ZONE).format(FMT);
    }

    private static String hm(OffsetDateTime t) {
        return t == null ? "—"
                : t.atZoneSameInstant(OutageUnionCalculator.SETTLEMENT_ZONE)
                        .format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    private static String stateName(String s) {
        return switch (s == null ? "" : s) {
            case "OPEN" -> "未恢复";
            case "RECOVERED" -> "已凭有效回执恢复";
            case "RECOVERY_REVOKED" -> "恢复被复压失败推翻";
            case "VOID" -> "已作废（移出影响范围）";
            default -> s;
        };
    }

    private static String signed(Integer v) {
        int x = v == null ? 0 : v;
        return x > 0 ? "+" + x : String.valueOf(x);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
