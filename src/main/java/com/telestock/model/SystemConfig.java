package com.telestock.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Data;

@Entity
@Data
public class SystemConfig {
    @Id
    private Long id = 1L;
    private Boolean tradingEnabled = true;
    private Double availableCapital = 10000.0;
}
