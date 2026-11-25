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
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 一条“实际停水区间”：通常来自某张抢修单/事故在某个受影响地址上的一次停水，
 * 关联服务账户、停水开始回执与恢复回执来源。
 *
 * <p>关键纪律：</p>
 * <ul>
 *   <li>恢复时间只能来自恢复类回执（REPRESSURE/RECOVERY RECEIPT，且复压须 PASSED），
 *       通知发送时刻、工单关闭时刻不允许充当恢复时间；</li>
 *   <li>复压失败会使原“恢复”失效（state=RECOVERY_REVOKED，endAt 置空等待新回执）；</li>
 *   <li>恢复回执校时更正直接更新 endAt，并在 {@link OutageIntervalRevision} 留痕；</li>
 *   <li>地址被移出影响范围时，对应抢修单+地址的区间置 VOID，不参与并集。</li>
 * </ul>
 */
@Entity
@Table(name = "comp_outage_interval",
        uniqueConstraints = @UniqueConstraint(columnNames = "interval_code"))
public class OutageInterval {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 业务幂等编码，如 OI-U7-1 / SYNC-I2-A03 */
    @Column(name = "interval_code", nullable = false, unique = true, length = 64)
    private String intervalCode;

    @Column(name = "account_no", nullable = false, length = 32)
    private String accountNo;

    @Column(nullable = false, length = 32)
    private String addressCode;

    /** 抢修工单/事故编码（两张抢修单即两个 ticketCode） */
    @Column(length = 64)
    private String ticketCode;

    private Long incidentId;

    /** 结算自然日（Asia/Shanghai），由 startAt 推导并可显式指定 */
    @Column(nullable = false)
    private LocalDate day;

    @Column(nullable = false)
    private OffsetDateTime startAt;

    /** 有效恢复时刻；仅 state=RECOVERED 时非空且参与计算 */
    private OffsetDateTime endAt;

    /** OPEN（尚无有效恢复）/ RECOVERED / RECOVERY_REVOKED（复压失败推翻原恢复）/ VOID（移出影响范围） */
    @Column(nullable = false, length = 24)
    private String state = "OPEN";

    // ---- 停水开始回执来源 ----
    private Long startReceiptId;
    @Column(length = 64)
    private String startReceiptCode;
    @Column(length = 24)
    private String startReceiptType;
    private OffsetDateTime startReceiptAt;

    // ---- 恢复回执来源（含校时更正） ----
    private Long recoveryReceiptId;
    @Column(length = 64)
    private String recoveryReceiptCode;
    @Column(length = 24)
    private String recoveryReceiptType;
    /** 恢复回执自身的业务时刻（校时/更正前留底） */
    private OffsetDateTime recoveryReceiptAt;
    /** 校时更正后的回执时刻；为空表示未更正 */
    private OffsetDateTime recoveryCorrectedAt;
    /** REPRESSURE_RECEIPT / RECOVERY_RECEIPT；NOTIFICATION、WORK_ORDER_CLOSE 被禁止 */
    @Column(length = 32)
    private String recoverySourceType;
    /** PASSED / FAILED */
    @Column(length = 16)
    private String repressureStatus;

    @Column(length = 64)
    private String failedReceiptCode;
    private OffsetDateTime failedAt;

    /** 外部更正记录（如 pressure review 的 CorrectionRecord.id 或编码） */
    @Column(length = 64)
    private String correctionRef;

    @Column(length = 512)
    private String note;

    // ---- VOID 信息 ----
    @Column(length = 512)
    private String voidReason;
    @Column(length = 64)
    private String voidCorrectionRef;
    private OffsetDateTime voidedAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "comp_interval_ref", joinColumns = @JoinColumn(name = "interval_id"))
    @OrderColumn(name = "idx")
    @Column(name = "ref_code", length = 128)
    private List<String> sourceRefs = new ArrayList<>();

    @Column(nullable = false)
    private Integer revisionNo = 0;

    @Column(length = 64)
    private String createdBy;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public String getIntervalCode() {
        return intervalCode;
    }

    public void setIntervalCode(String intervalCode) {
        this.intervalCode = intervalCode;
    }

    public String getAccountNo() {
        return accountNo;
    }

    public void setAccountNo(String accountNo) {
        this.accountNo = accountNo;
    }

    public String getAddressCode() {
        return addressCode;
    }

    public void setAddressCode(String addressCode) {
        this.addressCode = addressCode;
    }

    public String getTicketCode() {
        return ticketCode;
    }

    public void setTicketCode(String ticketCode) {
        this.ticketCode = ticketCode;
    }

    public Long getIncidentId() {
        return incidentId;
    }

    public void setIncidentId(Long incidentId) {
        this.incidentId = incidentId;
    }

    public LocalDate getDay() {
        return day;
    }

    public void setDay(LocalDate day) {
        this.day = day;
    }

    public OffsetDateTime getStartAt() {
        return startAt;
    }

    public void setStartAt(OffsetDateTime startAt) {
        this.startAt = startAt;
    }

    public OffsetDateTime getEndAt() {
        return endAt;
    }

    public void setEndAt(OffsetDateTime endAt) {
        this.endAt = endAt;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public Long getStartReceiptId() {
        return startReceiptId;
    }

    public void setStartReceiptId(Long startReceiptId) {
        this.startReceiptId = startReceiptId;
    }

    public String getStartReceiptCode() {
        return startReceiptCode;
    }

    public void setStartReceiptCode(String startReceiptCode) {
        this.startReceiptCode = startReceiptCode;
    }

    public String getStartReceiptType() {
        return startReceiptType;
    }

    public void setStartReceiptType(String startReceiptType) {
        this.startReceiptType = startReceiptType;
    }

    public OffsetDateTime getStartReceiptAt() {
        return startReceiptAt;
    }

    public void setStartReceiptAt(OffsetDateTime startReceiptAt) {
        this.startReceiptAt = startReceiptAt;
    }

    public Long getRecoveryReceiptId() {
        return recoveryReceiptId;
    }

    public void setRecoveryReceiptId(Long recoveryReceiptId) {
        this.recoveryReceiptId = recoveryReceiptId;
    }

    public String getRecoveryReceiptCode() {
        return recoveryReceiptCode;
    }

    public void setRecoveryReceiptCode(String recoveryReceiptCode) {
        this.recoveryReceiptCode = recoveryReceiptCode;
    }

    public String getRecoveryReceiptType() {
        return recoveryReceiptType;
    }

    public void setRecoveryReceiptType(String recoveryReceiptType) {
        this.recoveryReceiptType = recoveryReceiptType;
    }

    public OffsetDateTime getRecoveryReceiptAt() {
        return recoveryReceiptAt;
    }

    public void setRecoveryReceiptAt(OffsetDateTime recoveryReceiptAt) {
        this.recoveryReceiptAt = recoveryReceiptAt;
    }

    public OffsetDateTime getRecoveryCorrectedAt() {
        return recoveryCorrectedAt;
    }

    public void setRecoveryCorrectedAt(OffsetDateTime recoveryCorrectedAt) {
        this.recoveryCorrectedAt = recoveryCorrectedAt;
    }

    public String getRecoverySourceType() {
        return recoverySourceType;
    }

    public void setRecoverySourceType(String recoverySourceType) {
        this.recoverySourceType = recoverySourceType;
    }

    public String getRepressureStatus() {
        return repressureStatus;
    }

    public void setRepressureStatus(String repressureStatus) {
        this.repressureStatus = repressureStatus;
    }

    public String getFailedReceiptCode() {
        return failedReceiptCode;
    }

    public void setFailedReceiptCode(String failedReceiptCode) {
        this.failedReceiptCode = failedReceiptCode;
    }

    public OffsetDateTime getFailedAt() {
        return failedAt;
    }

    public void setFailedAt(OffsetDateTime failedAt) {
        this.failedAt = failedAt;
    }

    public String getCorrectionRef() {
        return correctionRef;
    }

    public void setCorrectionRef(String correctionRef) {
        this.correctionRef = correctionRef;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public String getVoidReason() {
        return voidReason;
    }

    public void setVoidReason(String voidReason) {
        this.voidReason = voidReason;
    }

    public String getVoidCorrectionRef() {
        return voidCorrectionRef;
    }

    public void setVoidCorrectionRef(String voidCorrectionRef) {
        this.voidCorrectionRef = voidCorrectionRef;
    }

    public OffsetDateTime getVoidedAt() {
        return voidedAt;
    }

    public void setVoidedAt(OffsetDateTime voidedAt) {
        this.voidedAt = voidedAt;
    }

    public List<String> getSourceRefs() {
        return sourceRefs;
    }

    public void setSourceRefs(List<String> sourceRefs) {
        this.sourceRefs = sourceRefs == null ? new ArrayList<>() : new ArrayList<>(sourceRefs);
    }

    public Integer getRevisionNo() {
        return revisionNo;
    }

    public void setRevisionNo(Integer revisionNo) {
        this.revisionNo = revisionNo;
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

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
