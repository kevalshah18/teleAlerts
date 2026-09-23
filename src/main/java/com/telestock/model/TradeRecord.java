package com.telestock.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Data
public class TradeRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private String symbol;
    private int quantity;
    private double entryPrice;
    private double exitPrice;
    private LocalDateTime entryTime;
    private LocalDateTime exitTime;
    
    // Taxes & Charges
    private double brokerage;
    private double stt;
    private double exchangeTurnoverCharge;
    private double sebiCharges;
    private double stampDuty;
    private double gst;
    
    // PnL
    private double grossPnl;
    private double netPnl;
    
    private String geminiReasoning;
}
