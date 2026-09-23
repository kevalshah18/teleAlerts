package com.telestock.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CandidateSignal {
    private String symbol;
    private double currentPrice;
    private String type; // "BUY" or "SELL"
    private double rsi;
    private LocalDateTime timestamp;
}
