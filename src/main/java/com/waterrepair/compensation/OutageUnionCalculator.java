package com.waterrepair.compensation;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 停水补偿核算的纯领域算法。
 *
 * <p>规则（题面）：对同一服务账户、同一自然日（Asia/Shanghai）内所有“有有效恢复回执”的
 * 实际停水区间，<b>先求时间并集</b>（重叠/相接区间合并，重叠部分绝不重复计时），
 * 再按每满 {@value #MINUTES_PER_CREDIT} 分钟（4 小时）计 1 个服务抵扣，
 * 每日最多 {@value #MAX_CREDITS_PER_DAY} 个。</p>
 *
 * <p>本类不依赖数据库与 Spring，便于单测直接验证 U7 样例：
 * 10:00-12:00 与 11:00-15:00 → 并集 10:00-15:00（300 分钟）→ 1 个抵扣；
 * 恢复时间更正为 18:00 后 → 10:00-18:00（480 分钟）→ 2 个抵扣。</p>
 */
public final class OutageUnionCalculator {

    /** 合同约定的结算时区：自然日按此时区切分。 */
    public static final ZoneId SETTLEMENT_ZONE = ZoneId.of("Asia/Shanghai");

    /** 每满 4 小时 = 240 分钟计 1 个服务抵扣。 */
    public static final int MINUTES_PER_CREDIT = 240;

    /** 每个账户每个自然日最多 2 个抵扣。 */
    public static final int MAX_CREDITS_PER_DAY = 2;

    private OutageUnionCalculator() {
    }

    /** 参与并集计算的最小区间视图；endAt 为 null（未恢复/恢复被推翻）的区间不参与已满时长计算。 */
    public record CalcSlice(String intervalCode,
                            String accountNo,
                            String addressCode,
                            OffsetDateTime startAt,
                            OffsetDateTime endAt,
                            String status) {
    }

    /** 合并后的连续时间块（半开区间语义，端点相接会合并以便解释）。 */
    public record TimeSpan(OffsetDateTime startAt, OffsetDateTime endAt, long minutes) {
    }

    /**
     * 单个抵扣的证据块：该抵扣对应的 240 个“并集分钟”可能跨越多个合并时间块
     * （中间的停水空档不计入并集），并可反查到贡献这些分钟的原始抢修区间。
     */
    public record CreditEvidence(int ordinal,
                                 List<TimeSpan> spans,
                                 OffsetDateTime coveredStartAt,
                                 OffsetDateTime coveredEndAt,
                                 long countedMinutes,
                                 List<String> intervalCodes,
                                 List<String> addressCodes) {
    }

    public record CalcResult(LocalDate day,
                             List<TimeSpan> unionBlocks,
                             long unionMinutes,
                             double unionHours,
                             int creditsBeforeDailyCap,
                             int creditCount,
                             int maxCreditsPerDay,
                             int fullHoursPerCredit,
                             long uncountedMinutes,
                             boolean dailyCapApplied,
                             List<CreditEvidence> credits) {
    }

    /** 按默认规则（4 小时 1 个、每日最多 2 个）计算。 */
    public static CalcResult calculate(LocalDate day, List<CalcSlice> slices) {
        return calculate(day, slices, MAX_CREDITS_PER_DAY);
    }

    public static CalcResult calculate(LocalDate day, List<CalcSlice> slices, int maxCreditsPerDay) {
        ZonedDateTime dayStart = day.atStartOfDay(SETTLEMENT_ZONE);
        ZonedDateTime dayEnd = day.plusDays(1).atStartOfDay(SETTLEMENT_ZONE);

        // 1) 裁剪到该自然日，并过滤掉没有有效恢复时刻的区间。
        List<Clipped> clipped = new ArrayList<>();
        if (slices != null) {
            for (CalcSlice s : slices) {
                if (s == null || s.startAt() == null || s.endAt() == null) {
                    continue;
                }
                if (!"RECOVERED".equals(s.status())) {
                    continue;
                }
                ZonedDateTime st = s.startAt().atZoneSameInstant(SETTLEMENT_ZONE);
                ZonedDateTime en = s.endAt().atZoneSameInstant(SETTLEMENT_ZONE);
                if (st.isBefore(dayStart)) {
                    st = dayStart;
                }
                if (en.isAfter(dayEnd)) {
                    en = dayEnd;
                }
                if (!en.isAfter(st)) {
                    continue;
                }
                clipped.add(new Clipped(s, st, en));
            }
        }

        // 2) 按开始时刻排序后求并集（重叠/相接合并）。
        clipped.sort(Comparator.comparing(c -> c.start));
        List<TimeSpan> blocks = new ArrayList<>();
        List<ZonedDateTime[]> merged = new ArrayList<>();
        for (Clipped c : clipped) {
            if (merged.isEmpty()) {
                merged.add(new ZonedDateTime[]{c.start, c.end});
            } else {
                ZonedDateTime[] last = merged.get(merged.size() - 1);
                if (!c.start.isAfter(last[1])) {
                    // 重叠或端点相接：延长当前块（重叠分钟只保留一次）。
                    if (c.end.isAfter(last[1])) {
                        last[1] = c.end;
                    }
                } else {
                    merged.add(new ZonedDateTime[]{c.start, c.end});
                }
            }
        }
        long unionMinutes = 0;
        for (ZonedDateTime[] m : merged) {
            long mins = Duration.between(m[0], m[1]).toMinutes();
            unionMinutes += mins;
            blocks.add(new TimeSpan(m[0].toOffsetDateTime(), m[1].toOffsetDateTime(), mins));
        }

        // 3) 每满 4 小时 1 个，向下取整；再施加每日上限。
        int beforeCap = (int) (unionMinutes / MINUTES_PER_CREDIT);
        int creditCount = Math.min(Math.max(maxCreditsPerDay, 0), beforeCap);
        boolean capApplied = beforeCap > creditCount;

        // 4) 在并集时间轴上顺序切出每个抵扣对应的 240 分钟，并反查原始区间。
        List<CreditEvidence> credits = new ArrayList<>();
        int ordinal = 1;
        long need = MINUTES_PER_CREDIT;
        List<TimeSpan> currentSpans = new ArrayList<>();
        ZonedDateTime creditStart = null;
        for (TimeSpan block : blocks) {
            if (ordinal > creditCount) {
                break;
            }
            ZonedDateTime cursor = block.startAt().atZoneSameInstant(SETTLEMENT_ZONE);
            ZonedDateTime blockEnd = block.endAt().atZoneSameInstant(SETTLEMENT_ZONE);
            while (cursor.isBefore(blockEnd) && ordinal <= creditCount) {
                long blockLeft = Duration.between(cursor, blockEnd).toMinutes();
                long take = Math.min(blockLeft, need);
                ZonedDateTime segEnd = cursor.plusMinutes(take);
                if (creditStart == null) {
                    creditStart = cursor;
                }
                currentSpans.add(new TimeSpan(cursor.toOffsetDateTime(), segEnd.toOffsetDateTime(), take));
                need -= take;
                cursor = segEnd;
                if (need == 0) {
                    credits.add(buildEvidence(ordinal, currentSpans, creditStart, segEnd, clipped));
                    ordinal++;
                    need = MINUTES_PER_CREDIT;
                    currentSpans = new ArrayList<>();
                    creditStart = null;
                }
            }
        }

        long countedMinutes = (long) creditCount * MINUTES_PER_CREDIT;
        long uncounted = Math.max(0, unionMinutes - countedMinutes);
        double hours = Math.round(unionMinutes / 60.0 * 100.0) / 100.0;
        return new CalcResult(day, blocks, unionMinutes, hours, beforeCap, creditCount,
                maxCreditsPerDay, MINUTES_PER_CREDIT / 60, uncounted, capApplied, credits);
    }

    private static CreditEvidence buildEvidence(int ordinal,
                                                List<TimeSpan> spans,
                                                ZonedDateTime start,
                                                ZonedDateTime end,
                                                List<Clipped> clipped) {
        Set<String> intervalCodes = new LinkedHashSet<>();
        Set<String> addressCodes = new LinkedHashSet<>();
        for (TimeSpan span : spans) {
            ZonedDateTime ss = span.startAt().atZoneSameInstant(SETTLEMENT_ZONE);
            ZonedDateTime se = span.endAt().atZoneSameInstant(SETTLEMENT_ZONE);
            for (Clipped c : clipped) {
                // 半开区间相交：c.start < se && ss < c.end
                if (c.start.isBefore(se) && ss.isBefore(c.end)) {
                    if (c.slice.intervalCode() != null) {
                        intervalCodes.add(c.slice.intervalCode());
                    }
                    if (c.slice.addressCode() != null) {
                        addressCodes.add(c.slice.addressCode());
                    }
                }
            }
        }
        return new CreditEvidence(ordinal, List.copyOf(spans),
                start.toOffsetDateTime(), end.toOffsetDateTime(),
                (long) MINUTES_PER_CREDIT,
                List.copyOf(intervalCodes), List.copyOf(addressCodes));
    }

    private record Clipped(CalcSlice slice, ZonedDateTime start, ZonedDateTime end) {
    }
}
