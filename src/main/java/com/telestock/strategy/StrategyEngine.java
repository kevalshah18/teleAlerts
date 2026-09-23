package com.telestock.strategy;

import com.telestock.ai.GeminiAiService;
import com.telestock.config.ConfigService;
import com.telestock.feed.LiveMarketDataService;
import com.telestock.ledger.LedgerService;
import com.telestock.model.CandidateSignal;
import com.telestock.model.MarketData;
import com.telestock.model.Position;
import com.telestock.model.SystemConfig;
import com.telestock.repository.PositionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class StrategyEngine {
    private final LiveMarketDataService dataService;
    private final ConfigService configService;
    private final GeminiAiService aiService;
    private final LedgerService ledgerService;
    private final PositionRepository positionRepository;
    
    // Store 5-min candle close prices
    private final Map<String, List<Double>> candleCloses = new ConcurrentHashMap<>();
    
    // Track current 5-min candle
    private final Map<String, CurrentCandle> currentCandles = new ConcurrentHashMap<>();

    @Scheduled(fixedRate = 10000) // Poll every 10s
    public void evaluateSignals() {
        if (!configService.getConfig().getTradingEnabled()) return;
        
        Map<String, MarketData> latestData = dataService.getAllLatestData();
        for (Map.Entry<String, MarketData> entry : latestData.entrySet()) {
            String symbol = entry.getKey();
            double ltp = entry.getValue().getLtp();
            
            checkExitConditions(symbol, ltp);
            
            // Build 5-min candles
            CurrentCandle candle = currentCandles.computeIfAbsent(symbol, k -> new CurrentCandle(ltp, LocalDateTime.now()));
            candle.update(ltp);
            
            // If 5 minutes have passed, close the candle
            if (ChronoUnit.MINUTES.between(candle.startTime, LocalDateTime.now()) >= 5) {
                List<Double> closes = candleCloses.computeIfAbsent(symbol, k -> new ArrayList<>());
                closes.add(candle.close);
                if (closes.size() > 50) {
                    closes.remove(0); // keep last 50 candles
                }
                
                // Reset for next candle
                currentCandles.put(symbol, new CurrentCandle(ltp, LocalDateTime.now()));
                
                // Evaluate strategy on closed candles
                if (closes.size() >= 21) {
                    double ema9 = calculateEMA(closes, 9);
                    double ema21 = calculateEMA(closes, 21);
                    double rsi14 = calculateRSI(closes, 14);
                    
                    // Crossover buy logic
                    if (ema9 > ema21 && rsi14 >= 45 && rsi14 <= 65) {
                        if (positionRepository.findBySymbol(symbol).isEmpty()) {
                            log.info("Candidate BUY Signal for {}", symbol);
                            CandidateSignal signal = new CandidateSignal(symbol, ltp, "BUY", rsi14, LocalDateTime.now());
                            
                            // AI Veto
                            GeminiAiService.GeminiDecision decision = aiService.evaluateSignal(symbol, ltp, "BUY");
                            if (decision.isApproval()) {
                                int qty = (int) (10000 / ltp); // Simulate 10000 capital per trade
                                if (qty == 0) qty = 1;
                                ledgerService.executeBuy(symbol, ltp, qty, decision.getReasoning());
                            }
                        }
                    }
                }
            }
        }
    }
    
    private void checkExitConditions(String symbol, double ltp) {
        positionRepository.findBySymbol(symbol).ifPresent(pos -> {
            if (ltp >= pos.getTarget() || ltp <= pos.getStopLoss()) {
                ledgerService.executeSell(pos, ltp);
            }
        });
    }

    private double calculateEMA(List<Double> prices, int period) {
        double multiplier = 2.0 / (period + 1);
        double ema = prices.get(prices.size() - period);
        for (int i = prices.size() - period + 1; i < prices.size(); i++) {
            ema = (prices.get(i) - ema) * multiplier + ema;
        }
        return ema;
    }
    
    private double calculateRSI(List<Double> prices, int period) {
        double gains = 0;
        double losses = 0;
        for (int i = prices.size() - period; i < prices.size() - 1; i++) {
            double diff = prices.get(i+1) - prices.get(i);
            if (diff > 0) gains += diff;
            else losses -= diff;
        }
        double avgGain = gains / period;
        double avgLoss = losses / period;
        if (avgLoss == 0) return 100;
        double rs = avgGain / avgLoss;
        return 100 - (100 / (1 + rs));
    }
    
    private static class CurrentCandle {
        double open, high, low, close;
        LocalDateTime startTime;
        
        CurrentCandle(double price, LocalDateTime startTime) {
            this.open = price;
            this.high = price;
            this.low = price;
            this.close = price;
            this.startTime = startTime;
        }
        
        void update(double price) {
            this.high = Math.max(this.high, price);
            this.low = Math.min(this.low, price);
            this.close = price;
        }
    }
}
