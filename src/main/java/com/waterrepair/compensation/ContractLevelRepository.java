package com.waterrepair.compensation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ContractLevelRepository extends JpaRepository<ContractLevel, String> {
}
