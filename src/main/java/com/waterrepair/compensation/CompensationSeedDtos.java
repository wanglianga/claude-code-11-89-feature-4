package com.waterrepair.compensation;

import java.util.List;

/** 补偿模块样例数据（samples/compensation.json）。 */
public final class CompensationSeedDtos {

    private CompensationSeedDtos() {
    }

    public record CompensationSeed(List<LevelSeed> contractLevels,
                                   List<AccountSeed> serviceAccounts) {
    }

    public record LevelSeed(String code, String name, Integer creditAmountCny, String slaNote) {
    }

    public record AccountSeed(String accountNo, String customerName, String contractLevelCode,
                              List<String> addressCodes, String phone) {
    }
}
