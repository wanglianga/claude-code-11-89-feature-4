package com.waterrepair.compensation;

import com.waterrepair.common.ApiException;
import com.waterrepair.receipt.FieldReceipt;
import com.waterrepair.receipt.FieldReceiptRepository;
import com.waterrepair.incident.Incident;
import com.waterrepair.incident.IncidentRepository;
import com.waterrepair.incident.IsolationPlan;
import com.waterrepair.incident.IsolationPlanRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 停水补偿核算核心服务：区间写入 → 账户日并集重算 → 临时通知 / 结算 / 差额分录。
 *
 * <p>所有事实变更都经过 {@link #reevaluate} 这一个收口，保证“临时值、最终值、
 * 差额账本、客服解释”来自同一份计算结果。</p>
 */
@Service
public class CompensationService {

    /** 禁止充当恢复时间的来源（通知发送、工单关闭本身都不是恢复回执）。 */
    private static final List<String> FORBIDDEN_RECOVERY_SOURCES =
            List.of("NOTIFICATION", "NOTIFY", "SMS_NOTIFICATION", "WORK_ORDER_CLOSE",
                    "WORKORDER_CLOSE", "ORDER_CLOSE", "TICKET_CLOSE", "INCIDENT_CLOSE", "CLOSE");

    /** 构成“居民看到的数值”的通知类型；REASSESSING 等状态通知不移动该基线。 */
    private static final List<String> SUBSTANTIVE_NOTICE_KINDS =
            List.of("PROVISIONAL", "SUPPLEMENT", "BILL_ISSUED");

    private final ContractLevelRepository levelRepo;
    private final ServiceAccountRepository accountRepo;
    private final OutageIntervalRepository intervalRepo;
    private final OutageIntervalRevisionRepository revisionRepo;
    private final CompensationDayRepository dayRepo;
    private final LedgerEntryRepository ledgerRepo;
    private final CompensationNoticeRepository noticeRepo;
    private final AccountBillRepository billRepo;
    private final IncidentRepository incidentRepo;
    private final FieldReceiptRepository receiptRepo;
    private final IsolationPlanRepository planRepo;

    public CompensationService(ContractLevelRepository levelRepo,
                               ServiceAccountRepository accountRepo,
                               OutageIntervalRepository intervalRepo,
                               OutageIntervalRevisionRepository revisionRepo,
                               CompensationDayRepository dayRepo,
                               LedgerEntryRepository ledgerRepo,
                               CompensationNoticeRepository noticeRepo,
                               AccountBillRepository billRepo,
                               IncidentRepository incidentRepo,
                               FieldReceiptRepository receiptRepo,
                               IsolationPlanRepository planRepo) {
        this.levelRepo = levelRepo;
        this.accountRepo = accountRepo;
        this.intervalRepo = intervalRepo;
        this.revisionRepo = revisionRepo;
        this.dayRepo = dayRepo;
        this.ledgerRepo = ledgerRepo;
        this.noticeRepo = noticeRepo;
        this.billRepo = billRepo;
        this.incidentRepo = incidentRepo;
        this.receiptRepo = receiptRepo;
        this.planRepo = planRepo;
    }

    /** 一次重算产生的落库结果，供控制器组装响应。 */
    public record MutationOutcome(CompensationDay day,
                                  OutageUnionCalculator.CalcResult calc,
                                  List<LedgerEntry> newLedgerEntries,
                                  List<CompensationNotice> newNotices) {
    }

    public enum NoticeMode {
        NONE, PROVISIONAL, SUPPLEMENT, REASSESSING
    }

    // ------------------------------------------------------------------
    // 基础数据
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ContractLevel> levels() {
        return levelRepo.findAll();
    }

    @Transactional(readOnly = true)
    public List<ServiceAccount> accounts() {
        return accountRepo.findAll();
    }

    @Transactional(readOnly = true)
    public ServiceAccount requireAccount(String accountNo) {
        if (accountNo == null || accountNo.isBlank()) {
            throw ApiException.badRequest("ACCOUNT_REQUIRED", "必须指定服务账户 accountNo");
        }
        return accountRepo.findByAccountNo(accountNo.trim())
                .orElseThrow(() -> ApiException.notFound("ACCOUNT_NOT_FOUND",
                        "服务账户不存在: " + accountNo));
    }

    @Transactional(readOnly = true)
    public ServiceAccount account(String accountNo) {
        return requireAccount(accountNo);
    }

    @Transactional
    public ServiceAccount createAccount(CompensationDtos.CreateAccountRequest req) {
        if (req.accountNo() == null || req.accountNo().isBlank()) {
            throw ApiException.badRequest("ACCOUNT_NO_REQUIRED", "accountNo 不能为空");
        }
        if (accountRepo.findByAccountNo(req.accountNo()).isPresent()) {
            throw ApiException.conflict("ACCOUNT_EXISTS", "服务账户已存在: " + req.accountNo(), null);
        }
        String levelCode = req.contractLevelCode() == null ? "CL-RES" : req.contractLevelCode();
        if (!levelRepo.existsById(levelCode)) {
            throw ApiException.badRequest("CONTRACT_LEVEL_NOT_FOUND", "合同等级不存在: " + levelCode);
        }
        return accountRepo.save(new ServiceAccount(req.accountNo(), req.customerName(),
                levelCode, req.addressCodes(), req.phone()));
    }

    @Transactional(readOnly = true)
    public List<ServiceAccount> accountsAtAddress(String addressCode) {
        return accountRepo.findByAddressCode(addressCode);
    }

    public int unitAmount(String accountNo) {
        ServiceAccount a = requireAccount(accountNo);
        return levelRepo.findById(a.getContractLevelCode())
                .map(ContractLevel::getCreditAmountCny).orElse(0);
    }

    // ------------------------------------------------------------------
    // 区间写入
    // ------------------------------------------------------------------

    @Transactional
    public Map<String, Object> upsertInterval(CompensationDtos.UpsertIntervalRequest req,
                                               String operator) {
        ServiceAccount account = resolveAccount(req.accountNo(), req.addressCode());
        validateRecoverySource(req.recoverySourceType(), req.endAt() != null);

        if (req.startAt() == null) {
            throw ApiException.badRequest("START_TIME_REQUIRED",
                    "停水开始时刻 startAt 不能为空（通知/关单时间也不能替代）");
        }
        LocalDate day = parseDay(req.day(), req.startAt());

        String code = (req.intervalCode() == null || req.intervalCode().isBlank())
                ? defaultIntervalCode(req.intervalCode(), req.ticketCode(),
                        req.addressCode(), account.getAccountNo(), day)
                : req.intervalCode().trim();

        OutageInterval interval = intervalRepo.findByIntervalCode(code).orElseGet(OutageInterval::new);
        boolean isNew = interval.getId() == null;
        OffsetDateTime formerEnd = interval.getEndAt();
        OffsetDateTime now = OffsetDateTime.now();
        if (isNew) {
            interval.setIntervalCode(code);
            interval.setAccountNo(account.getAccountNo());
            interval.setRevisionNo(0);
            interval.setCreatedAt(now);
            interval.setCreatedBy(operator);
        }
        interval.setAddressCode(req.addressCode() == null ? firstAddress(account) : req.addressCode());
        if (req.addressCode() != null && !account.getAddressCodes().contains(req.addressCode())) {
            // 账户与地址的关联允许在业务录入时补全（一个账户可管理多个受水地址）。
            account.getAddressCodes().add(req.addressCode());
            accountRepo.save(account);
        }
        interval.setTicketCode(req.ticketCode());
        interval.setIncidentId(req.incidentId());
        interval.setCorrectionRef(req.correctionRef());
        interval.setDay(day);
        interval.setStartAt(req.startAt());
        if (req.endAt() != null) {
            interval.setEndAt(req.endAt());
            interval.setState("RECOVERED");
        } else if (isNew) {
            interval.setState("OPEN");
        }
        interval.setStartReceiptId(req.startReceiptId());
        interval.setStartReceiptCode(req.startReceiptCode());
        interval.setStartReceiptType(req.startReceiptType());
        interval.setStartReceiptAt(req.startReceiptAt());
        interval.setRecoveryReceiptId(req.recoveryReceiptId());
        interval.setRecoveryReceiptCode(req.recoveryReceiptCode());
        interval.setRecoveryReceiptType(req.recoveryReceiptType());
        interval.setRecoveryReceiptAt(req.recoveryReceiptAt());
        interval.setRecoveryCorrectedAt(req.recoveryCorrectedAt());
        if (req.recoverySourceType() != null) {
            interval.setRecoverySourceType(req.recoverySourceType().trim().toUpperCase());
        } else if (req.endAt() != null) {
            interval.setRecoverySourceType("RECOVERY_RECEIPT");
        }
        interval.setRepressureStatus(req.repressureStatus());
        interval.setNote(req.note());
        interval.setUpdatedAt(now);
        interval.setRevisionNo(interval.getRevisionNo() + 1);
        OutageInterval saved = intervalRepo.save(interval);

        writeRevision(code, saved.getRevisionNo(), "UPSERT", "interval", null,
                req.endAt() == null ? "OPEN" : req.endAt().toString(),
                req.note(), req.recoveryReceiptCode(), operator);

        // 路线判定：账期批量模式不推临时通知；其余在带恢复时刻时默认推临时抵扣。
        boolean batch = "BATCH".equalsIgnoreCase(req.mode());
        CompensationDay existingDay =
                dayRepo.findByAccountNoAndDay(account.getAccountNo(), day).orElse(null);
        boolean daySettled = existingDay != null && isSettled(existingDay);
        boolean dayBilled = existingDay != null && "BILLED".equals(existingDay.getStatus());
        // 已出账后的补报走 LATE_REPORT_AFTER_BILL，其余晚到重叠事件走 OVERLAP_LATE_EVENT。
        String changeReason = dayBilled ? "LATE_REPORT_AFTER_BILL" : "OVERLAP_LATE_EVENT";
        NoticeMode mode;
        if (daySettled) {
            mode = NoticeMode.SUPPLEMENT; // reevaluate 内已结算分支会自动出差额+补差说明
        } else if (batch) {
            mode = NoticeMode.NONE;
        } else if (Boolean.TRUE.equals(req.pushProvisional())) {
            mode = NoticeMode.PROVISIONAL;
        } else if (req.endAt() != null && !Boolean.FALSE.equals(req.pushProvisional())) {
            mode = NoticeMode.PROVISIONAL;
        } else {
            mode = NoticeMode.NONE;
        }

        MutationOutcome outcome = reevaluateForInterval(interval, formerEnd,
                changeReason, mode,
                List.of(nz(req.ticketCode()), nz(req.recoveryReceiptCode())), operator);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("interval", saved);
        result.put("created", isNew);
        result.put("evaluation", outcome);
        return result;
    }

    @Transactional
    public MutationOutcome recordRecovery(String code, CompensationDtos.RecoveryRequest req,
                                          String operator) {
        OutageInterval interval = requireInterval(code);
        validateRecoverySource(req.recoverySourceType(), true);
        OffsetDateTime effectiveEnd = req.endAt() != null ? req.endAt()
                : req.recoveryCorrectedAt() != null ? req.recoveryCorrectedAt()
                : req.recoveryReceiptAt();
        if (effectiveEnd == null) {
            throw ApiException.badRequest("RECOVERY_TIME_REQUIRED",
                    "必须提供 endAt 或恢复回执时刻；通知发送/工单关闭时间不能替代恢复回执");
        }
        OffsetDateTime formerEnd = interval.getEndAt();
        String oldEnd = interval.getEndAt() == null ? null : interval.getEndAt().toString();
        interval.setEndAt(effectiveEnd);
        interval.setState("RECOVERED");
        interval.setRecoveryReceiptId(req.recoveryReceiptId());
        interval.setRecoveryReceiptCode(req.recoveryReceiptCode());
        interval.setRecoveryReceiptType(req.recoveryReceiptType() == null
                ? "REPRESSURE" : req.recoveryReceiptType());
        interval.setRecoveryReceiptAt(req.recoveryReceiptAt());
        interval.setRecoveryCorrectedAt(req.recoveryCorrectedAt());
        interval.setRecoverySourceType(req.recoverySourceType() == null
                ? "REPRESSURE_RECEIPT" : req.recoverySourceType().trim().toUpperCase());
        interval.setRepressureStatus(req.repressureStatus() == null
                ? "PASSED" : req.repressureStatus());
        if (req.note() != null) {
            interval.setNote(req.note());
        }
        touch(interval);
        writeRevision(code, interval.getRevisionNo(), "RECOVERY", "endAt", oldEnd,
                effectiveEnd.toString(), "登记恢复回执并计算临时抵扣",
                req.recoveryReceiptCode(), operator);
        return reevaluateForInterval(interval, formerEnd,
                "RECOVERY_REGISTERED", NoticeMode.PROVISIONAL,
                List.of(nz(req.recoveryReceiptCode())), operator);
    }

    @Transactional
    public MutationOutcome correctRecovery(String code,
                                           CompensationDtos.CorrectRecoveryRequest req,
                                           String operator) {
        OutageInterval interval = requireInterval(code);
        OffsetDateTime newEnd = req.newEndAt() != null ? req.newEndAt()
                : req.newRecoveryCorrectedAt() != null ? req.newRecoveryCorrectedAt()
                : req.recoveryReceiptAt();
        if (newEnd == null) {
            throw ApiException.badRequest("CORRECTED_TIME_REQUIRED",
                    "必须提供 newEndAt / newRecoveryCorrectedAt / recoveryReceiptAt 之一作为更正后的恢复时刻");
        }
        OffsetDateTime formerEnd = interval.getEndAt();
        String oldEnd = interval.getEndAt() == null ? null : interval.getEndAt().toString();
        interval.setEndAt(newEnd);
        interval.setState("RECOVERED");
        if (req.newRecoveryCorrectedAt() != null) {
            interval.setRecoveryCorrectedAt(req.newRecoveryCorrectedAt());
        } else {
            interval.setRecoveryCorrectedAt(newEnd);
        }
        if (req.correctedReceiptCode() != null) {
            interval.setRecoveryReceiptCode(req.correctedReceiptCode());
        }
        if (req.recoveryReceiptType() != null) {
            interval.setRecoveryReceiptType(req.recoveryReceiptType());
        }
        if (req.recoveryReceiptAt() != null) {
            interval.setRecoveryReceiptAt(req.recoveryReceiptAt());
        }
        if (req.repressureStatus() != null) {
            interval.setRepressureStatus(req.repressureStatus());
        } else {
            interval.setRepressureStatus("PASSED");
        }
        if (req.correctionRef() != null) {
            interval.setCorrectionRef(req.correctionRef());
        }
        touch(interval);
        writeRevision(code, interval.getRevisionNo(), "CORRECT_RECOVERY", "endAt",
                oldEnd, newEnd.toString(), req.reason(), req.correctedReceiptCode(), operator);

        NoticeMode mode = Boolean.FALSE.equals(req.sendSupplement())
                ? NoticeMode.NONE : NoticeMode.SUPPLEMENT;
        return reevaluateForInterval(interval, formerEnd,
                "RECOVERY_TIME_CORRECTION", mode,
                List.of(nz(req.correctedReceiptCode()), nz(req.correctionRef())), operator);
    }

    @Transactional
    public MutationOutcome repressureFailure(String code,
                                            CompensationDtos.RepressureFailureRequest req,
                                            String operator) {
        OutageInterval interval = requireInterval(code);
        OffsetDateTime formerEnd = interval.getEndAt();
        String oldEnd = interval.getEndAt() == null ? null : interval.getEndAt().toString();
        // 原“恢复”不成立：有效 endAt 撤下，区间不再参与已满时长并集，等待新的恢复回执。
        interval.setEndAt(null);
        interval.setState("RECOVERY_REVOKED");
        interval.setRepressureStatus("FAILED");
        interval.setFailedReceiptCode(req.failedReceiptCode());
        interval.setFailedAt(req.failedAt() == null ? OffsetDateTime.now() : req.failedAt());
        touch(interval);
        writeRevision(code, interval.getRevisionNo(), "REPRESSURE_FAILURE", "endAt",
                oldEnd, "REVOKED", req.reason(), req.failedReceiptCode(), operator);

        // 未结算：直接重算（当前有效时长可能缩短），但只发“重新核定”状态通知，
        // 居民上一次看到的临时数值保留在原通知中、不被状态通知移动基线。
        return reevaluateForInterval(interval, formerEnd,
                "REPRESSURE_REOPEN", NoticeMode.REASSESSING,
                List.of(nz(req.failedReceiptCode())), operator);
    }

    @Transactional
    public MutationOutcome removeInterval(String code,
                                          CompensationDtos.RemoveIntervalRequest req,
                                          String operator) {
        OutageInterval interval = requireInterval(code);
        interval.setState("VOID");
        interval.setVoidReason(req.reason());
        interval.setVoidCorrectionRef(req.correctionRef());
        interval.setVoidedAt(OffsetDateTime.now());
        touch(interval);
        writeRevision(code, interval.getRevisionNo(), "REMOVE", "state",
                "ACTIVE", "VOID", req.reason(), req.correctionRef(), operator);
        return reevaluateForInterval(interval, interval.getEndAt(),
                "ADDRESS_REMOVED", NoticeMode.SUPPLEMENT,
                List.of(nz(req.correctionRef())), operator);
    }

    @Transactional
    public Map<String, Object> scopeRemoval(CompensationDtos.ScopeRemovalRequest req,
                                            String operator) {
        if (req.addressCode() == null || req.addressCode().isBlank()) {
            throw ApiException.badRequest("ADDRESS_REQUIRED", "scope-removals 必须指定 addressCode");
        }
        List<OutageInterval> targets = new ArrayList<>();
        if (req.incidentId() != null) {
            for (OutageInterval i : intervalRepo.findByIncidentId(req.incidentId())) {
                if (req.addressCode().equals(i.getAddressCode()) && !"VOID".equals(i.getState())) {
                    targets.add(i);
                }
            }
        }
        if (req.ticketCode() != null && !req.ticketCode().isBlank()) {
            for (OutageInterval i : intervalRepo.findByTicketCodeAndAddressCode(
                    req.ticketCode(), req.addressCode())) {
                if (!"VOID".equals(i.getState()) && !targets.contains(i)) {
                    targets.add(i);
                }
            }
        }
        if (targets.isEmpty()) {
            throw ApiException.notFound("SCOPE_INTERVAL_NOT_FOUND",
                    "未找到事故/抢修单下地址 " + req.addressCode() + " 的有效停水区间");
        }
        List<String> affectedDays = new ArrayList<>();
        for (OutageInterval i : targets) {
            i.setState("VOID");
            i.setVoidReason(req.reason() == null ? "地址被移出已确认影响范围" : req.reason());
            i.setVoidCorrectionRef(req.correctionRef());
            i.setVoidedAt(OffsetDateTime.now());
            touch(i);
            writeRevision(i.getIntervalCode(), i.getRevisionNo(), "REMOVE", "state",
                    "ACTIVE", "VOID", req.reason(), req.correctionRef(), operator);
            reevaluateForInterval(i, i.getEndAt(), "ADDRESS_REMOVED",
                    NoticeMode.SUPPLEMENT,
                    List.of(nz(req.correctionRef())), operator);
            affectedDays.add(i.getAccountNo() + "@" + i.getDay());
        }
        return Map.of("voidedCount", targets.size(),
                "intervalCodes", targets.stream().map(OutageInterval::getIntervalCode).toList(),
                "accountDays", affectedDays);
    }

    // ------------------------------------------------------------------
    // 结算与账期
    // ------------------------------------------------------------------

    @Transactional
    public LedgerEntry settleDay(String accountNo, LocalDate day, String route,
                                 String note, String operator) {
        requireAccount(accountNo);
        CompensationDay row = dayRepo.findByAccountNoAndDay(accountNo, day).orElse(null);
        if (row != null && row.getSettlementEntryId() != null) {
            return ledgerRepo.findById(row.getSettlementEntryId()).orElseThrow();
        }
        // 先用最新事实重算一次（ACTIVE 不写账本）。
        MutationOutcome outcome = reevaluate(accountNo, day, "SETTLE_RECALC",
                NoticeMode.NONE, null, List.of(), operator);
        row = outcome.day();

        int unit = unitAmount(accountNo);
        LedgerEntry entry = new LedgerEntry();
        entry.setAccountNo(accountNo);
        entry.setDay(day);
        entry.setPeriod(YearMonth.from(day).toString());
        entry.setEntryType("SETTLEMENT");
        entry.setReasonCode("BATCH".equalsIgnoreCase(route)
                ? "BATCH_PERIOD_SETTLEMENT" : "INITIAL_SETTLEMENT");
        entry.setReasonNote(note);
        entry.setCreditDelta(outcome.calc().creditCount());
        entry.setAmountDeltaCny(outcome.calc().creditCount() * unit);
        entry.setCreditAfter(outcome.calc().creditCount());
        entry.setAmountAfterCny(outcome.calc().creditCount() * unit);
        entry.setCreatedBy(operator);
        entry.setCreatedAt(OffsetDateTime.now());
        LedgerEntry saved = ledgerRepo.save(entry);
        saved.setEntryNo("LE-" + String.format("%06d", saved.getId()));
        saved = ledgerRepo.save(saved);

        row.setStatus("SETTLED");
        row.setSettledCreditCount(outcome.calc().creditCount());
        row.setSettledAmountCny(outcome.calc().creditCount() * unit);
        row.setSettledAt(OffsetDateTime.now());
        row.setSettlementEntryId(saved.getId());
        dayRepo.save(row);
        return saved;
    }

    @Transactional
    public Map<String, Object> issuePeriod(String period, Boolean pushBillNotice, String operator) {
        final YearMonth ym;
        try {
            ym = YearMonth.parse(period);
        } catch (Exception e) {
            throw ApiException.badRequest("BAD_PERIOD", "账期格式应为 yyyy-MM，例如 2026-10");
        }
        LocalDate from = ym.atDay(1);
        LocalDate to = ym.atEndOfMonth();

        List<CompensationDay> days =
                dayRepo.findByDayBetweenOrderByDayAscAccountNoAsc(from, to);
        // 账期内若只有区间还没有 day 行（理论上 upsert 已建），兜底不需要处理。

        for (CompensationDay d : new ArrayList<>(days)) {
            if (!isSettled(d)) {
                settleDay(d.getAccountNo(), d.getDay(), "BATCH",
                        "账期 " + period + " 结束统一合并区间后结算", operator);
            }
        }
        // 重新读取结算后的行。
        days = dayRepo.findByDayBetweenOrderByDayAscAccountNoAsc(from, to);

        List<AccountBill> bills = new ArrayList<>();
        boolean push = pushBillNotice == null || pushBillNotice;
        for (String accountNo : days.stream().map(CompensationDay::getAccountNo).distinct().toList()) {
            AccountBill existing = billRepo.findByAccountNoAndPeriod(accountNo, period).orElse(null);
            if (existing != null) {
                bills.add(existing);
                continue;
            }
            List<CompensationDay> myDays = days.stream()
                    .filter(d -> d.getAccountNo().equals(accountNo)).toList();
            int origCredits = myDays.stream()
                    .mapToInt(d -> nz(d.getSettledCreditCount())).sum();
            int origAmount = myDays.stream()
                    .mapToInt(d -> nz(d.getSettledAmountCny())).sum();

            AccountBill bill = new AccountBill();
            bill.setBillNo("BILL-" + period.replace("-", "") + "-" + accountNo);
            bill.setAccountNo(accountNo);
            bill.setPeriod(period);
            bill.setOriginalCreditCount(origCredits);
            bill.setOriginalAmountCny(origAmount);
            bill.setAdjustmentCreditCount(0);
            bill.setAdjustmentAmountCny(0);
            bill.setCurrentCreditCount(origCredits);
            bill.setCurrentAmountCny(origAmount);
            for (CompensationDay d : myDays) {
                bill.getItems().add(new AccountBill.Item(d.getDay(), d.getUnionMinutes(),
                        d.getSettledCreditCount(), d.getSettledAmountCny()));
                d.setStatus("BILLED");
                dayRepo.save(d);
            }
            bill.setIssuedBy(operator);
            bill.setIssuedAt(OffsetDateTime.now());
            AccountBill savedBill = billRepo.save(bill);

            for (CompensationDay d : myDays) {
                d.setBillId(savedBill.getId());
                d.setBillNo(savedBill.getBillNo());
                dayRepo.save(d);
                LedgerEntry le = d.getSettlementEntryId() == null ? null
                        : ledgerRepo.findById(d.getSettlementEntryId()).orElse(null);
                if (le != null && le.getBillId() == null) {
                    le.setBillId(savedBill.getId());
                    le.setBillNo(savedBill.getBillNo());
                    le.setPeriod(period);
                    ledgerRepo.save(le);
                }
            }

            if (push) {
                CompensationNotice notice = new CompensationNotice();
                notice.setAccountNo(accountNo);
                notice.setDay(from);
                notice.setKind("BILL_ISSUED");
                notice.setTitle(period + " 账期停水补偿账单已出具");
                notice.setContent("您 " + period + " 账期的停水服务补偿账单已出具：共 "
                        + origCredits + " 个服务抵扣，合计 " + origAmount
                        + " 元。账单原始数值已固定；如后续补报或更正，将以差额分录另发补差说明。");
                notice.setSeenCreditCount(origCredits);
                notice.setSeenAmountCny(origAmount);
                notice.setReasonCode("BILL_ISSUED");
                notice.setCreatedAt(OffsetDateTime.now());
                noticeRepo.save(notice);
            }
            bills.add(savedBill);
        }
        return Map.of("period", period, "billCount", bills.size(),
                "billNos", bills.stream().map(AccountBill::getBillNo).toList(),
                "bills", bills);
    }

    @Transactional(readOnly = true)
    public List<AccountBill> bills(String accountNo, String period) {
        if (accountNo != null && !accountNo.isBlank() && period != null && !period.isBlank()) {
            return billRepo.findByAccountNoAndPeriod(accountNo, period).map(List::of).orElseGet(List::of);
        }
        if (period != null && !period.isBlank()) {
            return billRepo.findByPeriodOrderByAccountNoAsc(period);
        }
        if (accountNo != null && !accountNo.isBlank()) {
            return billRepo.findByAccountNoOrderByPeriodDesc(accountNo);
        }
        return billRepo.findAll();
    }

    @Transactional(readOnly = true)
    public AccountBill bill(String billNo) {
        return billRepo.findByBillNo(billNo)
                .orElseThrow(() -> ApiException.notFound("BILL_NOT_FOUND", "账单不存在: " + billNo));
    }

    // ------------------------------------------------------------------
    // 从真实事故回执同步区间（只认真实 ISOLATION / PASSED REPRESSURE 回执）
    // ------------------------------------------------------------------

    @Transactional
    public Map<String, Object> syncIncident(Long incidentId, String operator) {
        Incident incident = incidentRepo.findById(incidentId)
                .orElseThrow(() -> ApiException.notFound("INCIDENT_NOT_FOUND", "事故不存在: " + incidentId));
        IsolationPlan plan = planRepo
                .findFirstByIncidentIdAndStatusOrderByVersionNoDesc(incidentId, "ACTIVE")
                .or(() -> planRepo.findByIncidentIdOrderByVersionNoAscIdAsc(incidentId).stream()
                        .reduce((a, b) -> b))
                .orElse(null);
        if (plan == null || plan.getAffectedAddressCodes().isEmpty()) {
            throw ApiException.conflict("NO_AFFECTED_ADDRESSES",
                    "事故尚无含受影响地址快照的隔离方案，不能生成补偿区间", null);
        }
        List<FieldReceipt> receipts = receiptRepo.findByIncidentIdOrderByIdAsc(incidentId);
        FieldReceipt startReceipt = receipts.stream()
                .filter(r -> "ISOLATION".equals(r.getType())).findFirst().orElse(null);
        FieldReceipt passedRecovery = receipts.stream()
                .filter(r -> "REPRESSURE".equals(r.getType())
                        && "PASSED".equals(r.getVerificationStatus()))
                .reduce((a, b) -> b).orElse(null);
        List<String> failedCodes = receipts.stream()
                .filter(r -> "REPRESSURE".equals(r.getType())
                        && "FAILED".equals(r.getVerificationStatus()))
                .map(FieldReceipt::getReadingCode).toList();

        List<String> synced = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (String addressCode : plan.getAffectedAddressCodes()) {
            List<ServiceAccount> accs = accountRepo.findByAddressCode(addressCode);
            if (accs.isEmpty()) {
                skipped.add(addressCode);
                continue;
            }
            ServiceAccount account = accs.get(0);
            String code = "SYNC-I" + incidentId + "-" + addressCode;
            OutageInterval interval = intervalRepo.findByIntervalCode(code).orElseGet(OutageInterval::new);
            OffsetDateTime now = OffsetDateTime.now();
            boolean isNew = interval.getId() == null;
            if (isNew) {
                interval.setIntervalCode(code);
                interval.setRevisionNo(0);
                interval.setCreatedAt(now);
                interval.setCreatedBy(operator);
            }
            interval.setAccountNo(account.getAccountNo());
            interval.setAddressCode(addressCode);
            interval.setIncidentId(incidentId);
            interval.setTicketCode(incident.getCode());
            if (startReceipt != null) {
                interval.setStartAt(startReceipt.getEventAt());
                interval.setDay(startReceipt.getEventAt()
                        .atZoneSameInstant(OutageUnionCalculator.SETTLEMENT_ZONE).toLocalDate());
                interval.setStartReceiptId(startReceipt.getId());
                interval.setStartReceiptCode("RCPT-" + startReceipt.getId());
                interval.setStartReceiptType(startReceipt.getType());
                interval.setStartReceiptAt(startReceipt.getEventAt());
            } else if (interval.getStartAt() == null) {
                // 没有隔离回执时不能编造停水开始时刻。
                skipped.add(addressCode + "(缺少ISOLATION回执)");
                continue;
            }
            if (passedRecovery != null) {
                interval.setEndAt(passedRecovery.getEventAt());
                interval.setState("RECOVERED");
                interval.setRecoveryReceiptId(passedRecovery.getId());
                interval.setRecoveryReceiptCode("RCPT-" + passedRecovery.getId());
                interval.setRecoveryReceiptType("REPRESSURE");
                interval.setRecoveryReceiptAt(passedRecovery.getEventAt());
                interval.setRecoverySourceType("REPRESSURE_RECEIPT");
                interval.setRepressureStatus("PASSED");
            } else {
                interval.setState("OPEN");
            }
            interval.getSourceRefs().clear();
            for (String fc : failedCodes) {
                if (fc != null) {
                    interval.getSourceRefs().add("FAILED_REPRESSURE:" + fc);
                }
            }
            interval.setUpdatedAt(now);
            interval.setRevisionNo(interval.getRevisionNo() + 1);
            intervalRepo.save(interval);
            reevaluate(account.getAccountNo(), interval.getDay(), "INCIDENT_SYNC",
                    NoticeMode.NONE, code, List.of(), operator);
            synced.add(code);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("incidentId", incidentId);
        result.put("incidentCode", incident.getCode() == null ? "" : incident.getCode());
        result.put("affectedAddressCodes", plan.getAffectedAddressCodes());
        result.put("syncedIntervalCodes", synced);
        result.put("skipped", skipped);
        result.put("startReceiptId", startReceipt == null ? null : startReceipt.getId());
        result.put("recoveryReceiptId", passedRecovery == null ? null : passedRecovery.getId());
        result.put("failedRepressureReadingCodes", failedCodes);
        return result;
    }

    // ------------------------------------------------------------------
    // 统一重算收口
    // ------------------------------------------------------------------

    @Transactional
    public MutationOutcome reevaluate(String accountNo, LocalDate day, String reasonCode,
                                      NoticeMode mode, String relatedIntervalCode,
                                      List<String> refs, String operator) {
        ServiceAccount account = requireAccount(accountNo);
        int unit = levelRepo.findById(account.getContractLevelCode())
                .map(ContractLevel::getCreditAmountCny).orElse(0);

        List<OutageInterval> intervals = intervalsOfDay(accountNo, day);
        List<OutageUnionCalculator.CalcSlice> slices = intervals.stream()
                .map(i -> new OutageUnionCalculator.CalcSlice(i.getIntervalCode(),
                        i.getAccountNo(), i.getAddressCode(), i.getStartAt(),
                        i.getEndAt(), i.getState()))
                .toList();
        OutageUnionCalculator.CalcResult calc = OutageUnionCalculator.calculate(day, slices);
        int newCredits = calc.creditCount();
        int newAmount = newCredits * unit;

        CompensationDay row = dayRepo.findByAccountNoAndDay(accountNo, day)
                .orElseGet(() -> {
                    CompensationDay d = new CompensationDay();
                    d.setAccountNo(accountNo);
                    d.setDay(day);
                    d.setCreatedAt(OffsetDateTime.now());
                    return d;
                });
        int oldFinal = nz(row.getFinalCreditCount());
        boolean settled = isSettled(row);

        row.setUnionMinutes(calc.unionMinutes());
        row.setFinalCreditCount(newCredits);
        row.setFinalAmountCny(newAmount);
        row.setUpdatedAt(OffsetDateTime.now());

        List<LedgerEntry> newEntries = new ArrayList<>();
        List<CompensationNotice> newNotices = new ArrayList<>();

        if (settled && newCredits != oldFinal) {
            int oldAmount = oldFinal * unit;
            LedgerEntry diff = new LedgerEntry();
            diff.setAccountNo(accountNo);
            diff.setDay(day);
            diff.setPeriod(YearMonth.from(day).toString());
            diff.setEntryType("DIFF");
            diff.setReasonCode(reasonCode);
            diff.setReasonNote(defaultReasonNote(reasonCode));
            diff.setCreditDelta(newCredits - oldFinal);
            diff.setAmountDeltaCny(newAmount - oldAmount);
            diff.setCreditAfter(newCredits);
            diff.setAmountAfterCny(newAmount);
            diff.setPreservedSeenCreditCount(row.getSettledCreditCount());
            diff.setPreservedSeenAmountCny(row.getSettledAmountCny());
            diff.setBillId(row.getBillId());
            diff.setBillNo(row.getBillNo());
            if (refs != null) {
                diff.getSourceRefs().addAll(refs.stream().filter(r -> r != null && !r.isBlank()).toList());
            }
            diff.setCreatedBy(operator);
            diff.setCreatedAt(OffsetDateTime.now());
            LedgerEntry savedDiff = ledgerRepo.save(diff);
            savedDiff.setEntryNo("LE-" + String.format("%06d", savedDiff.getId()));
            ledgerRepo.save(savedDiff);
            newEntries.add(savedDiff);

            if (row.getBillId() != null) {
                recomputeBillAggregates(row.getBillId());
            }

            CompensationNotice supplement = buildValueNotice(accountNo, day, "SUPPLEMENT",
                    oldFinal, newCredits, unit, reasonCode, relatedIntervalCode);
            newNotices.add(noticeRepo.save(supplement));
        } else if (!settled) {
            switch (mode) {
                case PROVISIONAL -> {
                    CompensationNotice n = buildValueNotice(accountNo, day, "PROVISIONAL",
                            oldFinal, newCredits, unit, reasonCode, relatedIntervalCode);
                    newNotices.add(noticeRepo.save(n));
                    row.setProvisionalCreditCount(newCredits);
                    row.setProvisionalAmountCny(newAmount);
                    row.setProvisionalAt(OffsetDateTime.now());
                }
                case SUPPLEMENT -> {
                    Integer baseline = substantiveSeenCredits(accountNo, day);
                    if (baseline != null && baseline != newCredits) {
                        CompensationNotice n = buildValueNotice(accountNo, day, "SUPPLEMENT",
                                baseline, newCredits, unit, reasonCode, relatedIntervalCode);
                        newNotices.add(noticeRepo.save(n));
                    }
                }
                case REASSESSING -> {
                    Integer baseline = substantiveSeenCredits(accountNo, day);
                    int seen = baseline == null ? oldFinal : baseline;
                    CompensationNotice n = new CompensationNotice();
                    n.setAccountNo(accountNo);
                    n.setDay(day);
                    n.setKind("REASSESSING");
                    n.setChannel("SIMULATED_PUSH");
                    n.setTitle("停水时长重新核定通知");
                    n.setContent("相关抢修单的复压未通过，原报恢复时间不成立，该地址停水区间正在"
                            + "重新核定；您此前看到的 " + seen + " 个服务抵扣数值保持不变，"
                            + "核定完成后将另发临时抵扣或补差说明。通知发送时刻与工单关闭时刻"
                            + "均不会被当作恢复时间。");
                    n.setSeenCreditCount(seen);
                    n.setSeenAmountCny(seen * unit);
                    n.setPreviousCreditCount(seen);
                    n.setDeltaCredits(0);
                    n.setDeltaAmountCny(0);
                    n.setReasonCode(reasonCode);
                    n.setRelatedIntervalCode(relatedIntervalCode);
                    n.setCreatedAt(OffsetDateTime.now());
                    newNotices.add(noticeRepo.save(n));
                }
                case NONE -> {
                }
            }
        }

        dayRepo.save(row);
        return new MutationOutcome(row, calc, newEntries, newNotices);
    }

    // ------------------------------------------------------------------
    // 查询辅助
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<OutageInterval> intervals(String accountNo, LocalDate day, String addressCode) {
        List<OutageInterval> list;
        if (accountNo != null && day != null) {
            list = intervalsOfDay(accountNo, day);
        } else if (accountNo != null) {
            list = intervalRepo.findByAccountNoOrderByDayAscIdAsc(accountNo);
        } else {
            list = intervalRepo.findAll();
        }
        if (addressCode != null && !addressCode.isBlank()) {
            list = list.stream().filter(i -> addressCode.equals(i.getAddressCode())).toList();
        }
        return list;
    }

    @Transactional(readOnly = true)
    public OutageInterval requireInterval(String code) {
        return intervalRepo.findByIntervalCode(code)
                .orElseThrow(() -> ApiException.notFound("INTERVAL_NOT_FOUND",
                        "停水区间不存在: " + code));
    }

    @Transactional(readOnly = true)
    public List<OutageIntervalRevision> revisions(String code) {
        requireInterval(code);
        return revisionRepo.findByIntervalCodeOrderByIdAsc(code);
    }

    @Transactional(readOnly = true)
    public List<LedgerEntry> ledger(String accountNo, LocalDate day) {
        if (accountNo != null && day != null) {
            return ledgerRepo.findByAccountNoAndDayOrderByIdAsc(accountNo, day);
        }
        if (accountNo != null) {
            return ledgerRepo.findByAccountNoOrderByIdAsc(accountNo);
        }
        return ledgerRepo.findAll();
    }

    @Transactional(readOnly = true)
    public List<CompensationNotice> notices(String accountNo, LocalDate day) {
        if (accountNo != null && day != null) {
            return noticeRepo.findByAccountNoAndDayOrderByIdAsc(accountNo, day);
        }
        if (accountNo != null) {
            return noticeRepo.findByAccountNoOrderByIdAsc(accountNo);
        }
        return noticeRepo.findAll();
    }

    @Transactional(readOnly = true)
    public CompensationDay dayRow(String accountNo, LocalDate day) {
        return dayRepo.findByAccountNoAndDay(accountNo, day).orElse(null);
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /** 与某自然日有交集的区间（跨午夜区间也会被取出，由并集计算器裁剪到当日）。 */
    private List<OutageInterval> intervalsOfDay(String accountNo, LocalDate day) {
        OffsetDateTime dayStart = day.atStartOfDay(OutageUnionCalculator.SETTLEMENT_ZONE)
                .toOffsetDateTime();
        OffsetDateTime dayEnd = day.plusDays(1).atStartOfDay(OutageUnionCalculator.SETTLEMENT_ZONE)
                .toOffsetDateTime();
        return intervalRepo.findIntersectingDay(accountNo, dayStart, dayEnd);
    }

    private static LocalDate shDay(OffsetDateTime t) {
        return t.atZoneSameInstant(OutageUnionCalculator.SETTLEMENT_ZONE).toLocalDate();
    }

    /**
     * 区间变更后，重算它可能影响到的所有自然日（跨午夜、或恢复时间被延长/撤销时，
     * 旧日界与新日界都要覆盖）。返回区间起始日（主日）的重算结果以组装响应。
     */
    private MutationOutcome reevaluateForInterval(OutageInterval interval,
                                                  OffsetDateTime formerEnd,
                                                  String reasonCode, NoticeMode mode,
                                                  List<String> refs, String operator) {
        LocalDate first = interval.getDay();
        LocalDate last = first;
        if (interval.getEndAt() != null) {
            last = shDay(interval.getEndAt());
        }
        if (formerEnd != null) {
            LocalDate oldLast = shDay(formerEnd);
            if (oldLast.isAfter(last)) {
                last = oldLast;
            }
        }
        MutationOutcome primary = null;
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
            MutationOutcome o = reevaluate(interval.getAccountNo(), d, reasonCode, mode,
                    interval.getIntervalCode(), refs, operator);
            if (d.equals(first)) {
                primary = o;
            }
        }
        return primary;
    }

    private boolean isSettled(CompensationDay row) {
        return row.getSettlementEntryId() != null
                || "SETTLED".equals(row.getStatus())
                || "BILLED".equals(row.getStatus());
    }

    private Integer substantiveSeenCredits(String accountNo, LocalDate day) {
        List<CompensationNotice> list =
                noticeRepo.findByAccountNoAndDayOrderByIdAsc(accountNo, day);
        for (int i = list.size() - 1; i >= 0; i--) {
            CompensationNotice n = list.get(i);
            if (SUBSTANTIVE_NOTICE_KINDS.contains(n.getKind()) && n.getSeenCreditCount() != null) {
                return n.getSeenCreditCount();
            }
        }
        return null;
    }

    private void recomputeBillAggregates(Long billId) {
        AccountBill bill = billRepo.findById(billId).orElseThrow();
        int adjC = 0;
        int adjA = 0;
        for (LedgerEntry e : ledgerRepo.findByBillIdOrderByIdAsc(billId)) {
            if ("DIFF".equals(e.getEntryType())) {
                adjC += nz(e.getCreditDelta());
                adjA += nz(e.getAmountDeltaCny());
            }
        }
        bill.setAdjustmentCreditCount(adjC);
        bill.setAdjustmentAmountCny(adjA);
        bill.setCurrentCreditCount(bill.getOriginalCreditCount() + adjC);
        bill.setCurrentAmountCny(bill.getOriginalAmountCny() + adjA);
        billRepo.save(bill);
    }

    private CompensationNotice buildValueNotice(String accountNo, LocalDate day, String kind,
                                                int previousCredits, int newCredits, int unit,
                                                String reasonCode, String relatedIntervalCode) {
        int delta = newCredits - previousCredits;
        CompensationNotice n = new CompensationNotice();
        n.setAccountNo(accountNo);
        n.setDay(day);
        n.setKind(kind);
        n.setChannel("SIMULATED_PUSH");
        n.setReasonCode(reasonCode);
        n.setRelatedIntervalCode(relatedIntervalCode);
        n.setPreviousCreditCount(previousCredits);
        n.setSeenCreditCount(newCredits);
        n.setSeenAmountCny(newCredits * unit);
        n.setDeltaCredits(delta);
        n.setDeltaAmountCny(delta * unit);
        n.setCreatedAt(OffsetDateTime.now());
        if ("PROVISIONAL".equals(kind)) {
            n.setTitle("停水服务抵扣临时通知");
            n.setContent("根据当前已收到的停水/恢复回执并按同一账户同一自然日合并重叠区间，"
                    + "您 " + day + " 的实际停水并集时长暂计可享 " + newCredits
                    + " 个服务抵扣（约 " + (newCredits * unit) + " 元）。"
                    + "该数值为临时核算，若后续出现重叠事件、恢复时间校时更正或补报，"
                    + "我们将重新核算并向您发送补差说明。");
        } else {
            String direction = delta >= 0 ? "补发" : "调减";
            n.setTitle("停水服务抵扣补差说明");
            n.setContent("经重新核算，您 " + day + " 的服务抵扣由 " + previousCredits
                    + " 个调整为 " + newCredits + " 个，" + direction + "差额 "
                    + Math.abs(delta) + " 个抵扣（" + Math.abs(delta * unit)
                    + " 元）。最终金额以差额账本与账单调整为准；您此前看到的 "
                    + previousCredits + " 个抵扣数值仍保留在原通知中可查。");
        }
        return n;
    }

    private ServiceAccount resolveAccount(String accountNo, String addressCode) {
        if (accountNo != null && !accountNo.isBlank()) {
            return requireAccount(accountNo);
        }
        if (addressCode == null || addressCode.isBlank()) {
            throw ApiException.badRequest("ACCOUNT_OR_ADDRESS_REQUIRED",
                    "accountNo 与 addressCode 至少提供一个");
        }
        List<ServiceAccount> accs = accountRepo.findByAddressCode(addressCode.trim());
        if (accs.isEmpty()) {
            throw ApiException.notFound("ADDRESS_ACCOUNT_NOT_FOUND",
                    "地址 " + addressCode + " 未关联服务账户，请先创建服务账户或显式传入 accountNo");
        }
        if (accs.size() > 1) {
            throw ApiException.unprocessable("ACCOUNT_AMBIGUOUS",
                    "地址 " + addressCode + " 关联了多个服务账户，请显式指定 accountNo",
                    Map.of("accountNos", accs.stream().map(ServiceAccount::getAccountNo).toList()));
        }
        return accs.get(0);
    }

    private void validateRecoverySource(String sourceType, boolean hasEndAt) {
        if (sourceType == null) {
            return;
        }
        String normalized = sourceType.trim().toUpperCase().replace('-', '_');
        if (FORBIDDEN_RECOVERY_SOURCES.contains(normalized)) {
            throw ApiException.unprocessable("RECOVERY_SOURCE_NOT_RECEIPT",
                    "通知发送时刻或工单关闭本身不能充当恢复时间；恢复时间必须来自恢复类回执"
                            + "（REPRESSURE/RECOVERY RECEIPT，且复压通过）",
                    Map.of("recoverySourceType", sourceType,
                            "allowedExamples", List.of("REPRESSURE_RECEIPT", "RECOVERY_RECEIPT")));
        }
        if (!hasEndAt && sourceType != null) {
            // 仅声明来源但没有任何时刻不构成错误，后续 recovery 调用会要求时刻。
        }
    }

    private String defaultIntervalCode(String supplied, String ticketCode, String addressCode,
                                       String accountNo, LocalDate day) {
        if (ticketCode != null && !ticketCode.isBlank() && addressCode != null) {
            return "OI-" + ticketCode.replaceAll("[^A-Za-z0-9_-]", "_") + "-" + addressCode;
        }
        return "OI-" + accountNo + "-" + day + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String firstAddress(ServiceAccount account) {
        return account.getAddressCodes().isEmpty() ? "UNMAPPED" : account.getAddressCodes().get(0);
    }

    private LocalDate parseDay(String day, OffsetDateTime fallback) {
        if (day != null && !day.isBlank()) {
            try {
                return LocalDate.parse(day.trim(), DateTimeFormatter.ISO_LOCAL_DATE);
            } catch (Exception e) {
                throw ApiException.badRequest("BAD_DAY", "day 格式应为 yyyy-MM-dd: " + day);
            }
        }
        if (fallback == null) {
            throw ApiException.badRequest("DAY_REQUIRED", "无法从空的 startAt 推导自然日，请显式传 day");
        }
        return fallback.atZoneSameInstant(OutageUnionCalculator.SETTLEMENT_ZONE).toLocalDate();
    }

    private void touch(OutageInterval interval) {
        interval.setUpdatedAt(OffsetDateTime.now());
        interval.setRevisionNo(interval.getRevisionNo() + 1);
        intervalRepo.save(interval);
    }

    private void writeRevision(String code, Integer revisionNo, String action, String field,
                               String oldValue, String newValue, String reason,
                               String referenceCode, String operator) {
        OutageIntervalRevision r = new OutageIntervalRevision();
        r.setIntervalCode(code);
        r.setRevisionNo(revisionNo);
        r.setAction(action);
        r.setFieldName(field);
        r.setOldValue(oldValue);
        r.setNewValue(newValue);
        r.setReason(reason);
        r.setReferenceCode(referenceCode);
        r.setCreatedBy(operator);
        r.setCreatedAt(OffsetDateTime.now());
        revisionRepo.save(r);
    }

    private String defaultReasonNote(String code) {
        return switch (code == null ? "" : code) {
            case "RECOVERY_TIME_CORRECTION" -> "恢复回执校时/更正后，按账户日并集规则重算并补差额";
            case "REPRESSURE_REOPEN" -> "复压失败导致停水重新开始，原恢复不成立，重算并补差额";
            case "ADDRESS_REMOVED" -> "地址被移出已确认影响范围，对应时间片作废并重算差额";
            case "LATE_REPORT_AFTER_BILL" -> "已出账后收到补报，按并集规则重算并以差额分录更正";
            case "OVERLAP_LATE_EVENT" -> "重叠停水事件晚到，按并集规则重算并补差额";
            default -> "事实变更后按账户日并集规则重算并补差额";
        };
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
