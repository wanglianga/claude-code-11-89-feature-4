package com.waterrepair.compensation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 推送给居民的抵扣通知/补差说明（SIMULATED_PUSH，落库可查，不接真实通道）。
 *
 * <p>通知行只追加、永不修改：即使后来并集/时间更正使最终值变化，
 * {@code seenCreditCount/seenAmountCny} 仍保留居民当时在短信/页面上看到的数值。</p>
 */
@Entity
@Table(name = "comp_notice")
public class CompensationNotice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_no", nullable = false, length = 32)
    private String accountNo;

    @Column(nullable = false)
    private LocalDate day;

    /** PROVISIONAL（每次恢复后的临时抵扣）/ SUPPLEMENT（补差说明）/ STATUS / BILL_ISSUED */
    @Column(nullable = false, length = 24)
    private String kind;

    @Column(nullable = false, length = 24)
    private String channel = "SIMULATED_PUSH";

    @Column(length = 128)
    private String title;

    @Lob
    @Column(nullable = false)
    private String content;

    /** 居民在这条通知中看到的抵扣数/金额（永久保留） */
    private Integer seenCreditCount;
    private Integer seenAmountCny;

    private Integer previousCreditCount;
    private Integer deltaCredits;
    private Integer deltaAmountCny;

    @Column(length = 40)
    private String reasonCode;

    @Column(length = 64)
    private String relatedIntervalCode;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

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

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Integer getSeenCreditCount() {
        return seenCreditCount;
    }

    public void setSeenCreditCount(Integer seenCreditCount) {
        this.seenCreditCount = seenCreditCount;
    }

    public Integer getSeenAmountCny() {
        return seenAmountCny;
    }

    public void setSeenAmountCny(Integer seenAmountCny) {
        this.seenAmountCny = seenAmountCny;
    }

    public Integer getPreviousCreditCount() {
        return previousCreditCount;
    }

    public void setPreviousCreditCount(Integer previousCreditCount) {
        this.previousCreditCount = previousCreditCount;
    }

    public Integer getDeltaCredits() {
        return deltaCredits;
    }

    public void setDeltaCredits(Integer deltaCredits) {
        this.deltaCredits = deltaCredits;
    }

    public Integer getDeltaAmountCny() {
        return deltaAmountCny;
    }

    public void setDeltaAmountCny(Integer deltaAmountCny) {
        this.deltaAmountCny = deltaAmountCny;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(String reasonCode) {
        this.reasonCode = reasonCode;
    }

    public String getRelatedIntervalCode() {
        return relatedIntervalCode;
    }

    public void setRelatedIntervalCode(String relatedIntervalCode) {
        this.relatedIntervalCode = relatedIntervalCode;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
