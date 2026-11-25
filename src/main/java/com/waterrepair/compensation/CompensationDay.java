package com.waterrepair.compensation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 账户×自然日的核算状态与快照。
 *
 * <p>状态推进：ACTIVE（可能已推过临时抵扣，但尚未结算，变更直接重算）
 * → SETTLED（已生成 SETTLEMENT 分录，之后变更只能追加 DIFF 差额分录）
 * → BILLED（已出账，账单原始数值不可变，补报同样走 DIFF）。</p>
 *
 * <p>{@code settled*} 字段保留居民在结算/出账时看到的原始数值；
 * {@code final*} 字段是按最新事实重算后的当前数值（= 最近一条账本分录的 after 值）。</p>
 */
@Entity
@Table(name = "comp_account_day",
        uniqueConstraints = @UniqueConstraint(columnNames = {"account_no", "day"}))
public class CompensationDay {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_no", nullable = false, length = 32)
    private String accountNo;

    @Column(nullable = false)
    private LocalDate day;

    /** ACTIVE / SETTLED / BILLED */
    @Column(nullable = false, length = 16)
    private String status = "ACTIVE";

    @Column(nullable = false)
    private Long unionMinutes = 0L;

    /** 按最新区间重算的抵扣数/金额 */
    @Column(nullable = false)
    private Integer finalCreditCount = 0;

    @Column(nullable = false)
    private Integer finalAmountCny = 0;

    /** 最近一次临时推送时的数值（推送本身以 notice 表为准，这里仅冗余） */
    private Integer provisionalCreditCount;
    private Integer provisionalAmountCny;
    private OffsetDateTime provisionalAt;

    /** 结算/出账时冻结的原始数值（居民原先看到的数值，永不被重算覆盖） */
    private Integer settledCreditCount;
    private Integer settledAmountCny;
    private OffsetDateTime settledAt;
    private Long settlementEntryId;

    private Long billId;

    @Column(length = 32)
    private String billNo;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    public Long getId() {
        return id;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getUnionMinutes() {
        return unionMinutes;
    }

    public void setUnionMinutes(Long unionMinutes) {
        this.unionMinutes = unionMinutes;
    }

    public Integer getFinalCreditCount() {
        return finalCreditCount;
    }

    public void setFinalCreditCount(Integer finalCreditCount) {
        this.finalCreditCount = finalCreditCount;
    }

    public Integer getFinalAmountCny() {
        return finalAmountCny;
    }

    public void setFinalAmountCny(Integer finalAmountCny) {
        this.finalAmountCny = finalAmountCny;
    }

    public Integer getProvisionalCreditCount() {
        return provisionalCreditCount;
    }

    public void setProvisionalCreditCount(Integer provisionalCreditCount) {
        this.provisionalCreditCount = provisionalCreditCount;
    }

    public Integer getProvisionalAmountCny() {
        return provisionalAmountCny;
    }

    public void setProvisionalAmountCny(Integer provisionalAmountCny) {
        this.provisionalAmountCny = provisionalAmountCny;
    }

    public OffsetDateTime getProvisionalAt() {
        return provisionalAt;
    }

    public void setProvisionalAt(OffsetDateTime provisionalAt) {
        this.provisionalAt = provisionalAt;
    }

    public Integer getSettledCreditCount() {
        return settledCreditCount;
    }

    public void setSettledCreditCount(Integer settledCreditCount) {
        this.settledCreditCount = settledCreditCount;
    }

    public Integer getSettledAmountCny() {
        return settledAmountCny;
    }

    public void setSettledAmountCny(Integer settledAmountCny) {
        this.settledAmountCny = settledAmountCny;
    }

    public OffsetDateTime getSettledAt() {
        return settledAt;
    }

    public void setSettledAt(OffsetDateTime settledAt) {
        this.settledAt = settledAt;
    }

    public Long getSettlementEntryId() {
        return settlementEntryId;
    }

    public void setSettlementEntryId(Long settlementEntryId) {
        this.settlementEntryId = settlementEntryId;
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

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
