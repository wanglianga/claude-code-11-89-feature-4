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

import java.util.ArrayList;
import java.util.List;

/**
 * 供水服务账户（如样例 U7）。一个账户可关联一个或多个受水地址；
 * 补偿并集严格按 accountNo + 自然日 计算，邻居账户（不同 accountNo）永不并入。
 */
@Entity
@Table(name = "comp_service_account",
        uniqueConstraints = @UniqueConstraint(columnNames = "account_no"))
public class ServiceAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_no", nullable = false, unique = true, length = 32)
    private String accountNo;

    @Column(length = 128)
    private String customerName;

    @Column(nullable = false, length = 32)
    private String contractLevelCode;

    /** ACTIVE / SUSPENDED */
    @Column(nullable = false, length = 16)
    private String status = "ACTIVE";

    /** 该账户关联的受水地址编码（network.address_point.code） */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "comp_account_address",
            joinColumns = @JoinColumn(name = "account_id"))
    @OrderColumn(name = "idx")
    @Column(name = "address_code", length = 32)
    private List<String> addressCodes = new ArrayList<>();

    @Column(length = 32)
    private String phone;

    public ServiceAccount() {
    }

    public ServiceAccount(String accountNo, String customerName, String contractLevelCode,
                          List<String> addressCodes, String phone) {
        this.accountNo = accountNo;
        this.customerName = customerName;
        this.contractLevelCode = contractLevelCode;
        if (addressCodes != null) {
            this.addressCodes = new ArrayList<>(addressCodes);
        }
        this.phone = phone;
    }

    public Long getId() {
        return id;
    }

    public String getAccountNo() {
        return accountNo;
    }

    public void setAccountNo(String accountNo) {
        this.accountNo = accountNo;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public String getContractLevelCode() {
        return contractLevelCode;
    }

    public void setContractLevelCode(String contractLevelCode) {
        this.contractLevelCode = contractLevelCode;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public List<String> getAddressCodes() {
        return addressCodes;
    }

    public void setAddressCodes(List<String> addressCodes) {
        this.addressCodes = addressCodes == null ? new ArrayList<>() : new ArrayList<>(addressCodes);
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }
}
