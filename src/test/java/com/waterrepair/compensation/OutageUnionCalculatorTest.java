package com.waterrepair.compensation;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 账户日时间并集 + 每满 4 小时 1 个抵扣 + 每日最多 2 个的核心规则测试。
 * 重点锁定题面 U7 样例：重叠工单不得分别累计。
 */
class OutageUnionCalculatorTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 20);
    private static final ZoneOffset ZONE = ZoneOffset.ofHours(8);

    private static OffsetDateTime t(int h, int m) {
        return DAY.atTime(h, m).atOffset(ZONE);
    }

    private OutageUnionCalculator.CalcSlice recovered(String code, int sh, int sm, int eh, int em) {
        return new OutageUnionCalculator.CalcSlice(code, "U7", "A07",
                t(sh, sm), t(eh, em), "RECOVERED");
    }

    @Test
    void u7OverlappingIntervalsUnionToFiveHoursOneCredit() {
        // 10:00-12:00 与 11:00-15:00 → 并集 10:00-15:00 = 300 分钟 → 1 个抵扣（不是 2 个）。
        var result = OutageUnionCalculator.calculate(DAY, List.of(
                recovered("OI-1", 10, 0, 12, 0),
                recovered("OI-2", 11, 0, 15, 0)));

        assertEquals(300, result.unionMinutes());
        assertEquals(1, result.unionBlocks().size());
        assertEquals(t(10, 0), result.unionBlocks().get(0).startAt());
        assertEquals(t(15, 0), result.unionBlocks().get(0).endAt());
        assertEquals(1, result.creditsBeforeDailyCap());
        assertEquals(1, result.creditCount());
        assertEquals(60, result.uncountedMinutes());
        assertEquals(1, result.credits().size());
        // 第一个抵扣覆盖并集时间轴前 240 分钟：10:00-14:00，且反查到两张工单区间。
        var credit = result.credits().get(0);
        assertEquals(t(10, 0), credit.coveredStartAt());
        assertEquals(t(14, 0), credit.coveredEndAt());
        assertTrue(credit.intervalCodes().contains("OI-1"));
        assertTrue(credit.intervalCodes().contains("OI-2"));
        assertTrue(credit.addressCodes().contains("A07"));
    }

    @Test
    void u7CorrectedRecoveryToEightHoursGivesTwoCredits() {
        // 第二次复压失败、恢复回执更正为 18:00 → 10:00-18:00 = 480 分钟 → 2 个抵扣。
        var result = OutageUnionCalculator.calculate(DAY, List.of(
                recovered("OI-1", 10, 0, 12, 0),
                recovered("OI-2", 11, 0, 18, 0)));

        assertEquals(480, result.unionMinutes());
        assertEquals(2, result.creditCount());
        assertEquals(2, result.credits().size());
        assertEquals(t(10, 0), result.credits().get(0).coveredStartAt());
        assertEquals(t(14, 0), result.credits().get(0).coveredEndAt());
        assertEquals(t(14, 0), result.credits().get(1).spans().get(0).startAt());
        assertEquals(t(18, 0), result.credits().get(1).coveredEndAt());
    }

    @Test
    void dailyCapIsTwoEvenForVeryLongUnion() {
        var result = OutageUnionCalculator.calculate(DAY, List.of(
                recovered("OI-1", 0, 0, 23, 0))); // 1380 分钟 → floor=5，封顶 2
        assertEquals(1380, result.unionMinutes());
        assertEquals(5, result.creditsBeforeDailyCap());
        assertEquals(2, result.creditCount());
        assertTrue(result.dailyCapApplied());
        assertEquals(2, result.credits().size());
    }

    @Test
    void touchingAndDisjointBlocksSumUnionWithoutDoubleCount() {
        // 08:00-10:00(120) + 10:00-12:00(120) 端点相接合并为 240 → 1；
        // 另有 13:00-15:00(120)，总并集 360 → floor=1。
        var result = OutageUnionCalculator.calculate(DAY, List.of(
                recovered("A", 8, 0, 10, 0),
                recovered("B", 10, 0, 12, 0),
                recovered("C", 13, 0, 15, 0)));
        assertEquals(360, result.unionMinutes());
        assertEquals(2, result.unionBlocks().size()); // 上午相接合并、下午独立
        assertEquals(1, result.creditCount());
    }

    @Test
    void revokedOrOpenIntervalsDoNotCount() {
        var open = new OutageUnionCalculator.CalcSlice("OPEN-1", "U7", "A07",
                t(10, 0), null, "OPEN");
        var revoked = new OutageUnionCalculator.CalcSlice("REV-1", "U7", "A07",
                t(10, 0), t(15, 0), "RECOVERY_REVOKED");
        var result = OutageUnionCalculator.calculate(DAY, List.of(open, revoked));
        assertEquals(0, result.unionMinutes());
        assertEquals(0, result.creditCount());
    }

    @Test
    void exactlyFourHoursCountsOne() {
        var result = OutageUnionCalculator.calculate(DAY, List.of(
                recovered("X", 11, 0, 15, 0)));
        assertEquals(240, result.unionMinutes());
        assertEquals(1, result.creditCount());
        assertEquals(0, result.uncountedMinutes());
    }

    @Test
    void crossMidnightIntervalIsClippedToEachNaturalDay() {
        // 22:00(当日) ~ 次日06:00，共 480 分钟：当日裁出 120 分钟（0 个），
        // 次日裁出 360 分钟（floor(360/240)=1 个），自然日之间不互相借时长。
        var nightOwl = new OutageUnionCalculator.CalcSlice("N", "U7", "A07",
                DAY.atTime(22, 0).atOffset(ZONE),
                DAY.plusDays(1).atTime(6, 0).atOffset(ZONE), "RECOVERED");
        var day1 = OutageUnionCalculator.calculate(DAY, List.of(nightOwl));
        assertEquals(120, day1.unionMinutes());
        assertEquals(0, day1.creditCount());

        var day2 = OutageUnionCalculator.calculate(DAY.plusDays(1), List.of(nightOwl));
        assertEquals(360, day2.unionMinutes());
        assertEquals(1, day2.creditCount());
        assertEquals(t(0, 0).plusDays(1), day2.unionBlocks().get(0).startAt());
    }
}
