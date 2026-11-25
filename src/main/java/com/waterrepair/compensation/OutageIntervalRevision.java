package com.waterrepair.compensation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * 停水区间的一次变更留痕：创建、登记恢复、恢复时间校时更正、复压失败重开、移出影响范围作废。
 * 旧值/新值均以可展示字符串保存，保证客服争议页能讲清“时间片为什么变了”。
 */
@Entity
@Table(name = "comp_interval_revision")
public class OutageIntervalRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String intervalCode;

    @Column(nullable = false)
    private Integer revisionNo;

    /** CREATE / RECOVERY / CORRECT_RECOVERY / REPRESSURE_FAILURE / REMOVE */
    @Column(nullable = false, length = 32)
    private String action;

    @Column(length = 64)
    private String fieldName;

    @Column(length = 256)
    private String oldValue;

    @Column(length = 256)
    private String newValue;

    @Column(length = 512)
    private String reason;

    @Column(length = 64)
    private String referenceCode;

    @Column(length = 64)
    private String createdBy;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    public OutageIntervalRevision() {
    }

    public Long getId() {
        return id;
    }

    public String getIntervalCode() {
        return intervalCode;
    }

    public void setIntervalCode(String intervalCode) {
        this.intervalCode = intervalCode;
    }

    public Integer getRevisionNo() {
        return revisionNo;
    }

    public void setRevisionNo(Integer revisionNo) {
        this.revisionNo = revisionNo;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getFieldName() {
        return fieldName;
    }

    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }

    public String getOldValue() {
        return oldValue;
    }

    public void setOldValue(String oldValue) {
        this.oldValue = oldValue;
    }

    public String getNewValue() {
        return newValue;
    }

    public void setNewValue(String newValue) {
        this.newValue = newValue;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getReferenceCode() {
        return referenceCode;
    }

    public void setReferenceCode(String referenceCode) {
        this.referenceCode = referenceCode;
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
