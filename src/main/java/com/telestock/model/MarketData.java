package com.telestock.model;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class MarketData {
    private String symbol;
    private double ltp;
    private double open;
    private double high;
    private double low;
    private long volume;
    private LocalDateTime timestamp;
}
