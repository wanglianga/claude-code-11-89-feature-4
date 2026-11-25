package com.waterrepair.compensation;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
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
 * 服务账户的账期账单（路线 B：账期结束统一合并区间后出具）。
 *
 * <p>{@code original*} 与 {@link Item} 是出账时的不可变快照——居民原先看到的账单数值；
 * 出账后的补报/更正只产生 {@link LedgerEntry} DIFF 分录，汇总到
 * {@code adjustment*} 与 {@code current*}，绝不改原始账单行。</p>
 */
@Entity
@Table(name = "comp_account_bill")
public class AccountBill {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, length = 40)
    private String billNo;

    @Column(name = "account_no", nullable = false, length = 32)
    private String accountNo;

    /** yyyy-MM 账期 */
    @Column(nullable = false, length = 16)
    private String period;

    /** ISSUED */
    @Column(nullable = false, length = 16)
    private String status = "ISSUED";

    @Column(nullable = false)
    private Integer originalCreditCount = 0;

    @Column(nullable = false)
    private Integer originalAmountCny = 0;

    /** 出账后 DIFF 分录累计（带符号） */
    @Column(nullable = false)
    private Integer adjustmentCreditCount = 0;

    @Column(nullable = false)
    private Integer adjustmentAmountCny = 0;

    @Column(nullable = false)
    private Integer currentCreditCount = 0;

    @Column(nullable = false)
    private Integer currentAmountCny = 0;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "comp_bill_item", joinColumns = @JoinColumn(name = "bill_id"))
    @OrderColumn(name = "idx")
    private List<Item> items = new ArrayList<>();

    @Column(length = 64)
    private String issuedBy;

    @Column(nullable = false)
    private OffsetDateTime issuedAt;

    /** 账单中的一个账户日行（出账快照，不可变）。 */
    @Embeddable
    public static class Item {
        @Column(name = "day_date")
        private LocalDate day;
        private Long unionMinutes;
        private Integer creditCount;
        private Integer amountCny;

        public Item() {
        }

        public Item(LocalDate day, Long unionMinutes, Integer creditCount, Integer amountCny) {
            this.day = day;
            this.unionMinutes = unionMinutes;
            this.creditCount = creditCount;
            this.amountCny = amountCny;
        }

        public LocalDate getDay() {
            return day;
        }

        public void setDay(LocalDate day) {
            this.day = day;
        }

        public Long getUnionMinutes() {
            return unionMinutes;
        }

        public void setUnionMinutes(Long unionMinutes) {
            this.unionMinutes = unionMinutes;
        }

        public Integer getCreditCount() {
            return creditCount;
        }

        public void setCreditCount(Integer creditCount) {
            this.creditCount = creditCount;
        }

        public Integer getAmountCny() {
            return amountCny;
        }

        public void setAmountCny(Integer amountCny) {
            this.amountCny = amountCny;
        }
    }

    public Long getId() {
        return id;
    }

    public String getBillNo() {
        return billNo;
    }

    public void setBillNo(String billNo) {
        this.billNo = billNo;
    }

    public String getAccountNo() {
        return accountNo;
    }

    public void setAccountNo(String accountNo) {
        this.accountNo = accountNo;
    }

    public String getPeriod() {
        return period;
    }

    public void setPeriod(String period) {
        this.period = period;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getOriginalCreditCount() {
        return originalCreditCount;
    }

    public void setOriginalCreditCount(Integer originalCreditCount) {
        this.originalCreditCount = originalCreditCount;
    }

    public Integer getOriginalAmountCny() {
        return originalAmountCny;
    }

    public void setOriginalAmountCny(Integer originalAmountCny) {
        this.originalAmountCny = originalAmountCny;
    }

    public Integer getAdjustmentCreditCount() {
        return adjustmentCreditCount;
    }

    public void setAdjustmentCreditCount(Integer adjustmentCreditCount) {
        this.adjustmentCreditCount = adjustmentCreditCount;
    }

    public Integer getAdjustmentAmountCny() {
        return adjustmentAmountCny;
    }

    public void setAdjustmentAmountCny(Integer adjustmentAmountCny) {
        this.adjustmentAmountCny = adjustmentAmountCny;
    }

    public Integer getCurrentCreditCount() {
        return currentCreditCount;
    }

    public void setCurrentCreditCount(Integer currentCreditCount) {
        this.currentCreditCount = currentCreditCount;
    }

    public Integer getCurrentAmountCny() {
        return currentAmountCny;
    }

    public void setCurrentAmountCny(Integer currentAmountCny) {
        this.currentAmountCny = currentAmountCny;
    }

    public List<Item> getItems() {
        return items;
    }

    public void setItems(List<Item> items) {
        this.items = items == null ? new ArrayList<>() : new ArrayList<>(items);
    }

    public String getIssuedBy() {
        return issuedBy;
    }

    public void setIssuedBy(String issuedBy) {
        this.issuedBy = issuedBy;
    }

    public OffsetDateTime getIssuedAt() {
        return issuedAt;
    }

    public void setIssuedAt(OffsetDateTime issuedAt) {
        this.issuedAt = issuedAt;
    }
}
