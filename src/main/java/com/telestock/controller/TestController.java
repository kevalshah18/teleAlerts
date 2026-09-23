package com.telestock.controller;

import com.telestock.ledger.LedgerService;
import com.telestock.model.Position;
import com.telestock.repository.PositionRepository;
import com.telestock.repository.TradeRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/test")
@RequiredArgsConstructor
public class TestController {
    private final LedgerService ledgerService;
    private final PositionRepository positionRepository;
    private final TradeRecordRepository tradeRecordRepository;

    @GetMapping("/buy")
    public String forceBuy() {
        ledgerService.executeBuy("RELIANCE.NS", 2500.0, 1, "TEST AI REASON: Strong fundamentals and sector growth.");
        return "Buy executed";
    }

    @GetMapping("/sell")
    public String forceSell() {
        List<Position> pos = positionRepository.findAll();
        if (!pos.isEmpty()) {
            ledgerService.executeSell(pos.get(0), 2550.0); // Sell for a profit
            return "Sell executed";
        }
        return "No positions to sell";
    }

    @GetMapping("/cleanup")
    public String cleanup() {
        positionRepository.deleteAll();
        tradeRecordRepository.deleteAll();
        return "Cleanup done";
    }
}
