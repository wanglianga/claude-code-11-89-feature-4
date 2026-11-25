package com.waterrepair.compensation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 供水服务合同等级。不同等级约定每个“服务抵扣”对应的补偿金额（元，整数）。
 */
@Entity
@Table(name = "comp_contract_level")
public class ContractLevel {

    /** 等级代码，如 CL-RES / CL-PLUS / CL-BIZ */
    @Id
    @Column(length = 32)
    private String code;

    @Column(nullable = false, length = 64)
    private String name;

    /** 每 1 个服务抵扣抵扣/补偿的金额（人民币元） */
    @Column(nullable = false)
    private Integer creditAmountCny;

    @Column(length = 256)
    private String slaNote;

    public ContractLevel() {
    }

    public ContractLevel(String code, String name, Integer creditAmountCny, String slaNote) {
        this.code = code;
        this.name = name;
        this.creditAmountCny = creditAmountCny;
        this.slaNote = slaNote;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public Integer getCreditAmountCny() {
        return creditAmountCny;
    }

    public String getSlaNote() {
        return slaNote;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setCreditAmountCny(Integer creditAmountCny) {
        this.creditAmountCny = creditAmountCny;
    }

    public void setSlaNote(String slaNote) {
        this.slaNote = slaNote;
    }
}
