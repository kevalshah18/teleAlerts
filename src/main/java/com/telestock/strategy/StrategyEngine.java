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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
@RequiredArgsConstructor
@Slf4j
public class StrategyEngine {
    private final LiveMarketDataService dataService;
    private final ConfigService configService;
    private final GeminiAiService aiService;
    private final LedgerService ledgerService;
    private final PositionRepository positionRepository;
    
    public static final int MAX_CONCURRENT_POSITIONS = 5;
    
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
                List<Double> closes = candleCloses.computeIfAbsent(symbol, k -> new CopyOnWriteArrayList<>());
                closes.add(candle.close);
                while (closes.size() > 50) {
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
                            long currentPositions = positionRepository.count();
                            if (currentPositions >= MAX_CONCURRENT_POSITIONS) {
                                log.info("Max concurrent positions ({}) reached. Skipping candidate BUY for {}", MAX_CONCURRENT_POSITIONS, symbol);
                                continue;
                            }
                            
                            SystemConfig config = configService.getConfig();
                            double availableCapital = (config != null && config.getAvailableCapital() != null) ? config.getAvailableCapital() : 0.0;
                            if (availableCapital < ltp) {
                                log.info("Available capital Rs {:.2f} is less than LTP Rs {:.2f} for {}. Skipping candidate BUY.", availableCapital, ltp, symbol);
                                continue;
                            }
                            
                            log.info("Candidate BUY Signal for {}", symbol);
                            CandidateSignal signal = new CandidateSignal(symbol, ltp, "BUY", rsi14, LocalDateTime.now());
                            
                            // AI Veto
                            GeminiAiService.GeminiDecision decision = aiService.evaluateSignal(symbol, ltp, "BUY");
                            if (decision.isApproval()) {
                                currentPositions = positionRepository.count();
                                if (currentPositions >= MAX_CONCURRENT_POSITIONS) {
                                    log.warn("Max concurrent positions reached during AI veto evaluation for {}. Order aborted.", symbol);
                                    continue;
                                }
                                int openSlots = (int) (MAX_CONCURRENT_POSITIONS - currentPositions);
                                config = configService.getConfig();
                                availableCapital = (config != null && config.getAvailableCapital() != null) ? config.getAvailableCapital() : 0.0;
                                
                                int qty = calculatePositionSize(ltp, availableCapital, openSlots);
                                if (qty > 0) {
                                    ledgerService.executeBuy(symbol, ltp, qty, decision.getReasoning());
                                } else {
                                    log.warn("Calculated position size is 0 for {}. Order aborted.", symbol);
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    
    public void checkExitConditions(String symbol, double ltp) {
        positionRepository.findBySymbol(symbol).ifPresent(pos -> {
            if (ltp >= pos.getTarget() || ltp <= pos.getStopLoss()) {
                ledgerService.executeSell(pos, ltp);
            }
        });
    }

    public double calculateEMA(List<Double> prices, int period) {
        if (prices == null || prices.isEmpty() || period <= 0) {
            return 0.0;
        }
        if (prices.size() < period) {
            double sum = 0.0;
            for (Double p : prices) {
                sum += (p != null ? p : 0.0);
            }
            return sum / prices.size();
        }

        double multiplier = 2.0 / (period + 1);

        // Seed with Simple Moving Average (SMA) of the first 'period' elements
        double sum = 0.0;
        for (int i = 0; i < period; i++) {
            sum += prices.get(i);
        }
        double ema = sum / period;

        // Apply exponential smoothing over all subsequent prices
        for (int i = period; i < prices.size(); i++) {
            ema = (prices.get(i) - ema) * multiplier + ema;
        }

        return ema;
    }
    
    public double calculateRSI(List<Double> prices, int period) {
        if (prices == null || period <= 0 || prices.size() <= period) {
            return 50.0; // Insufficient history (< period + 1 prices), return neutral
        }

        int start = prices.size() - period - 1;
        double gains = 0.0;
        double losses = 0.0;

        for (int i = start; i < prices.size() - 1; i++) {
            double diff = prices.get(i + 1) - prices.get(i);
            if (diff > 0.0) {
                gains += diff;
            } else if (diff < 0.0) {
                losses += -diff;
            }
        }

        double avgGain = gains / period;
        double avgLoss = losses / period;

        // Flat market guard: no price movement is neutral 50.0, not 100.0
        if (avgGain == 0.0 && avgLoss == 0.0) {
            return 50.0;
        }
        if (avgLoss == 0.0) {
            return 100.0; // Pure upward movement with zero pullbacks
        }

        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    public int calculatePositionSize(double ltp, double availableCapital, int openSlots) {
        if (openSlots <= 0 || availableCapital <= 0.0 || ltp <= 0.0 || availableCapital < ltp) {
            return 0;
        }
        double capitalPerSlot = availableCapital / openSlots;
        int qty = (int) (capitalPerSlot / ltp);
        if (qty == 0 && availableCapital >= ltp) {
            qty = 1;
        }
        if ((qty * ltp) > availableCapital) {
            qty = (int) (availableCapital / ltp);
        }
        return qty;
    }

    public void addCandleClose(String symbol, double close) {
        List<Double> closes = candleCloses.computeIfAbsent(symbol, k -> new CopyOnWriteArrayList<>());
        closes.add(close);
        while (closes.size() > 50) {
            closes.remove(0); // keep last 50 candles
        }
    }

    public List<Double> getCandleCloses(String symbol) {
        return candleCloses.getOrDefault(symbol, Collections.emptyList());
    }

    public Map<String, List<Double>> getAllCandleCloses() {
        return Collections.unmodifiableMap(candleCloses);
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
