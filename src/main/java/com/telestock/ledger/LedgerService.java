package com.telestock.ledger;

import com.telestock.config.ConfigService;
import com.telestock.model.Position;
import com.telestock.model.SystemConfig;
import com.telestock.model.TradeRecord;
import com.telestock.repository.PositionRepository;
import com.telestock.repository.TradeRecordRepository;
import com.telestock.telegram.TelegramService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class LedgerService {
    private final PositionRepository positionRepository;
    private final TradeRecordRepository tradeRecordRepository;
    private final TelegramService telegramService;
    private final ConfigService configService;
    
    public void executeBuy(String symbol, double price, int quantity, String geminiReasoning) {
        SystemConfig config = configService.getConfig();
        double buyValue = price * quantity;
        
        if (config.getAvailableCapital() < buyValue) {
            log.warn("Insufficient capital to buy {} shares of {}. Needed: {}, Available: {}", quantity, symbol, buyValue, config.getAvailableCapital());
            return;
        }
        
        // Deduct capital
        config.setAvailableCapital(config.getAvailableCapital() - buyValue);
        configService.updateConfig(config);
        
        Position position = new Position();
        position.setSymbol(symbol);
        position.setQuantity(quantity);
        position.setEntryPrice(price);
        position.setStopLoss(price * 0.985); // -1.5%
        position.setTarget(price * 1.03);  // +3.0%
        position.setEntryTime(LocalDateTime.now());
        position.setGeminiReasoning(geminiReasoning);
        
        positionRepository.save(position);
        
        String msg = String.format("📈 *BUY Executed* \nSymbol: %s\nEntry: Rs %.2f\nQty: %d\nCost: Rs %.2f\nSL: Rs %.2f\nTarget: Rs %.2f\nCapital Left: Rs %.2f\nReason: %s",
                symbol, price, quantity, buyValue, position.getStopLoss(), position.getTarget(), config.getAvailableCapital(), geminiReasoning);
        telegramService.sendMessage(msg);
    }
    
    public void executeSell(Position position, double exitPrice) {
        TradeRecord record = new TradeRecord();
        record.setSymbol(position.getSymbol());
        record.setQuantity(position.getQuantity());
        record.setEntryPrice(position.getEntryPrice());
        record.setExitPrice(exitPrice);
        record.setEntryTime(position.getEntryTime());
        record.setExitTime(LocalDateTime.now());
        record.setGeminiReasoning(position.getGeminiReasoning());
        
        double buyValue = position.getEntryPrice() * position.getQuantity();
        double sellValue = exitPrice * position.getQuantity();
        
        double grossPnl = sellValue - buyValue;
        record.setGrossPnl(grossPnl);
        
        // Taxes & Charges Calculation
        double brokerageBuy = Math.min(20.0, buyValue * 0.0003);
        double brokerageSell = Math.min(20.0, sellValue * 0.0003);
        double totalBrokerage = brokerageBuy + brokerageSell;
        
        double stt = (buyValue + sellValue) * 0.001; // 0.1% on both sides for delivery
        double exchangeCharge = (buyValue + sellValue) * 0.0000345;
        double sebiCharge = (buyValue + sellValue) * 0.000001; // 10 per crore
        double stampDuty = buyValue * 0.00015;
        double gst = (totalBrokerage + exchangeCharge + sebiCharge) * 0.18;
        
        record.setBrokerage(totalBrokerage);
        record.setStt(stt);
        record.setExchangeTurnoverCharge(exchangeCharge);
        record.setSebiCharges(sebiCharge);
        record.setStampDuty(stampDuty);
        record.setGst(gst);
        
        double totalCharges = totalBrokerage + stt + exchangeCharge + sebiCharge + stampDuty + gst;
        double netPnl = grossPnl - totalCharges;
        
        record.setNetPnl(netPnl);
        
        tradeRecordRepository.save(record);
        positionRepository.delete(position);
        
        // Add capital back (buyValue invested + netPnl)
        SystemConfig config = configService.getConfig();
        double returnedCapital = buyValue + netPnl;
        config.setAvailableCapital(config.getAvailableCapital() + returnedCapital);
        configService.updateConfig(config);
        
        String msg = String.format("📉 *SELL Executed* \nSymbol: %s\nEntry: Rs %.2f\nExit: Rs %.2f\nGross PnL: Rs %.2f\nNet PnL: Rs %.2f\nNew Capital: Rs %.2f\nReason: %s",
                position.getSymbol(), position.getEntryPrice(), exitPrice, grossPnl, netPnl, config.getAvailableCapital(), position.getGeminiReasoning());
        telegramService.sendMessage(msg);
    }
}
