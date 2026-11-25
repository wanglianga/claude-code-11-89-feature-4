package com.waterrepair.compensation;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 补偿账本分录（差额账本）。
 *
 * <p>每个账户日首次结算写一条 SETTLEMENT（原始应付）；此后任何事实变更
 * （恢复校时、复压重开、重叠补报、地址移出、已出账后补报）都<b>不改写</b>该分录，
 * 而是追加 DIFF 差额分录：带符号的抵扣数/金额增量 + 更正后的 after 快照。
 * 居民账单上最初看到的数值由此被永久保留。</p>
 */
@Entity
@Table(name = "comp_ledger_entry")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, length = 64)
    private String entryNo;

    @Column(name = "account_no", nullable = false, length = 32)
    private String accountNo;

    @Column(nullable = false)
    private LocalDate day;

    /** 账期 yyyy-MM，可空（日结算未出账时） */
    @Column(length = 16)
    private String period;

    /** SETTLEMENT / DIFF */
    @Column(nullable = false, length = 16)
    private String entryType;

    /**
     * INITIAL_SETTLEMENT / BATCH_PERIOD_SETTLEMENT / RECOVERY_TIME_CORRECTION /
     * REPRESSURE_REOPEN / OVERLAP_LATE_EVENT / ADDRESS_REMOVED / LATE_REPORT_AFTER_BILL
     */
    @Column(nullable = false, length = 40)
    private String reasonCode;

    @Column(length = 512)
    private String reasonNote;

    @Column(nullable = false)
    private Integer creditDelta;

    @Column(nullable = false)
    private Integer amountDeltaCny;

    @Column(nullable = false)
    private Integer creditAfter;

    @Column(nullable = false)
    private Integer amountAfterCny;

    /** 该分录发生前，居民已看到/已结算的原始抵扣数（便于争议解释） */
    private Integer preservedSeenCreditCount;
    private Integer preservedSeenAmountCny;

    private Long billId;

    @Column(length = 32)
    private String billNo;

    /** 幂等键：同一业务更正不允许重复写差额 */
    @Column(unique = true, length = 128)
    private String idempotencyKey;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "comp_ledger_ref", joinColumns = @JoinColumn(name = "entry_id"))
    @OrderColumn(name = "idx")
    @Column(name = "ref_code", length = 128)
    private List<String> sourceRefs = new ArrayList<>();

    @Column(length = 64)
    private String createdBy;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    public Long getId() {
        return id;
    }

    public String getEntryNo() {
        return entryNo;
    }

    public void setEntryNo(String entryNo) {
        this.entryNo = entryNo;
    }

    public String getAccountNo() {
        return accountNo;
    }

    public void setAccountNo(String accountNo) {
        this.accountNo = accountNo;
    }

    public LocalDate getDay() {
        return day;
    }

    public void setDay(LocalDate day) {
        this.day = day;
    }

    public String getPeriod() {
        return period;
    }

    public void setPeriod(String period) {
        this.period = period;
    }

    public String getEntryType() {
        return entryType;
    }

    public void setEntryType(String entryType) {
        this.entryType = entryType;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(String reasonCode) {
        this.reasonCode = reasonCode;
    }

    public String getReasonNote() {
        return reasonNote;
    }

    public void setReasonNote(String reasonNote) {
        this.reasonNote = reasonNote;
    }

    public Integer getCreditDelta() {
        return creditDelta;
    }

    public void setCreditDelta(Integer creditDelta) {
        this.creditDelta = creditDelta;
    }

    public Integer getAmountDeltaCny() {
        return amountDeltaCny;
    }

    public void setAmountDeltaCny(Integer amountDeltaCny) {
        this.amountDeltaCny = amountDeltaCny;
    }

    public Integer getCreditAfter() {
        return creditAfter;
    }

    public void setCreditAfter(Integer creditAfter) {
        this.creditAfter = creditAfter;
    }

    public Integer getAmountAfterCny() {
        return amountAfterCny;
    }

    public void setAmountAfterCny(Integer amountAfterCny) {
        this.amountAfterCny = amountAfterCny;
    }

    public Integer getPreservedSeenCreditCount() {
        return preservedSeenCreditCount;
    }

    public void setPreservedSeenCreditCount(Integer preservedSeenCreditCount) {
        this.preservedSeenCreditCount = preservedSeenCreditCount;
    }

    public Integer getPreservedSeenAmountCny() {
        return preservedSeenAmountCny;
    }

    public void setPreservedSeenAmountCny(Integer preservedSeenAmountCny) {
        this.preservedSeenAmountCny = preservedSeenAmountCny;
    }

    public Long getBillId() {
        return billId;
    }

    public void setBillId(Long billId) {
        this.billId = billId;
    }

    public String getBillNo() {
        return billNo;
    }

    public void setBillNo(String billNo) {
        this.billNo = billNo;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public List<String> getSourceRefs() {
        return sourceRefs;
    }

    public void setSourceRefs(List<String> sourceRefs) {
        this.sourceRefs = sourceRefs == null ? new ArrayList<>() : new ArrayList<>(sourceRefs);
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
