package com.waterrepair.compensation;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 补偿核算模块的请求 DTO 集合。响应大多由 CompensationQueryService 组装为 Map 视图。
 */
public final class CompensationDtos {

    private CompensationDtos() {
    }

    /** 创建/更新一条停水区间（按 intervalCode 幂等 upsert）。 */
    public record UpsertIntervalRequest(
            String intervalCode,
            String accountNo,
            String addressCode,
            String ticketCode,
            Long incidentId,
            String correctionRef,
            String day,
            OffsetDateTime startAt,
            OffsetDateTime endAt,
            Long startReceiptId,
            String startReceiptCode,
            String startReceiptType,
            OffsetDateTime startReceiptAt,
            Long recoveryReceiptId,
            String recoveryReceiptCode,
            String recoveryReceiptType,
            OffsetDateTime recoveryReceiptAt,
            OffsetDateTime recoveryCorrectedAt,
            String recoverySourceType,
            String repressureStatus,
            /** 本次写入后是否立即给居民推一条临时抵扣（路线 A）。 */
            Boolean pushProvisional,
            /** IMMEDIATE / BATCH，仅作路线标记与默认推送策略。 */
            String mode,
            String note) {
    }

    /** 登记/补登恢复回执（“每次恢复先算临时抵扣并推送”）。 */
    public record RecoveryRequest(
            OffsetDateTime endAt,
            Long recoveryReceiptId,
            String recoveryReceiptCode,
            String recoveryReceiptType,
            OffsetDateTime recoveryReceiptAt,
            OffsetDateTime recoveryCorrectedAt,
            String recoverySourceType,
            String repressureStatus,
            Boolean pushProvisional,
            String note) {
    }

    /** 恢复回执校时更正（可由复压失败后的新回执触发）。 */
    public record CorrectRecoveryRequest(
            OffsetDateTime newEndAt,
            OffsetDateTime newRecoveryCorrectedAt,
            String correctedReceiptCode,
            String recoveryReceiptType,
            OffsetDateTime recoveryReceiptAt,
            String repressureStatus,
            String correctionRef,
            String reason,
            /** 更正后是否强制发补差说明（默认 true，只要数值相对居民上次所见变化）。 */
            Boolean sendSupplement) {
    }

    /** 复压失败：原恢复不成立，停水重新开始/仍在持续，等待新的恢复回执。 */
    public record RepressureFailureRequest(
            String failedReceiptCode,
            OffsetDateTime failedAt,
            String reason) {
    }

    /** 将一条区间作废（该抢修单对应的地址被移出影响范围）。 */
    public record RemoveIntervalRequest(
            String reason,
            String correctionRef) {
    }

    /** 按 事故/抢修单 + 地址 批量作废水区间（压力复核把地址移出影响范围时调用）。 */
    public record ScopeRemovalRequest(
            Long incidentId,
            String ticketCode,
            String addressCode,
            String correctionRef,
            String reason) {
    }

    public record SettleDayRequest(
            /** IMMEDIATE / BATCH_PERIOD，写入 SETTLEMENT 原因。 */
            String route,
            String note) {
    }

    public record IssuePeriodRequest(
            /** 出账后是否给每户发 BILL_ISSUED 通知，默认 true。 */
            Boolean pushBillNotice) {
    }

    public record CreateAccountRequest(
            String accountNo,
            String customerName,
            String contractLevelCode,
            List<String> addressCodes,
            String phone) {
    }

    /** 无状态试算：直接给区间数组，返回账户日并集/抵扣证据，不落库。 */
    public record CalculateRequest(
            String day,
            String accountNo,
            String contractLevelCode,
            Integer maxCreditsPerDay,
            List<UpsertIntervalRequest> intervals) {
    }
}
