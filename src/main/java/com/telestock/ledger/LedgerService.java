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
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
@Slf4j
public class LedgerService {
    private final PositionRepository positionRepository;
    private final TradeRecordRepository tradeRecordRepository;
    private final TelegramService telegramService;
    private final ConfigService configService;
    
    @Transactional
    public synchronized void executeBuy(String symbol, double price, int quantity, String geminiReasoning) {
        if (quantity <= 0 || price <= 0) {
            log.warn("Invalid quantity ({}) or price ({}) for {}. Buy aborted.", quantity, price, symbol);
            return;
        }

        SystemConfig config = configService.getConfig();
        double buyValue = price * quantity;
        
        if (config.getAvailableCapital() < buyValue) {
            log.warn("Insufficient capital to buy {} shares of {}. Needed: {}, Available: {}", quantity, symbol, buyValue, config.getAvailableCapital());
            return;
        }
        
        // Deduct capital
        config.setAvailableCapital(config.getAvailableCapital() - buyValue);
        configService.updateConfig(config);
        
        String safeReason = geminiReasoning;
        if (safeReason != null && safeReason.length() > 1000) {
            safeReason = safeReason.substring(0, 1000);
        }

        Position position = new Position();
        position.setSymbol(symbol);
        position.setQuantity(quantity);
        position.setEntryPrice(price);
        position.setStopLoss(price * 0.985); // -1.5%
        position.setTarget(price * 1.03);  // +3.0%
        LocalDateTime nowIst = LocalDateTime.now(ZoneId.of("Asia/Kolkata"));
        position.setEntryTime(nowIst);
        position.setGeminiReasoning(safeReason);
        
        positionRepository.save(position);

        String timeStr = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"))
                .format(DateTimeFormatter.ofPattern("dd-MMM-yyyy hh:mm a 'IST'"));
        
        String msg = String.format(
                "🟢 *TRADE SIGNAL: BUY (ENTRY)*\n" +
                "━━━━━━━━━━━━━━━━━━━━━\n" +
                "📌 *Stock*: `%s`\n" +
                "🕒 *Time*: `%s`\n" +
                "💰 *Entry Price*: `Rs %.2f`\n" +
                "📦 *Quantity*: `%d` shares (Total: Rs %.2f)\n" +
                "🎯 *Target (Sell Limit)*: `Rs %.2f` (+3.0%%)\n" +
                "🛑 *Stop Loss Limit*: `Rs %.2f` (-1.5%%)\n" +
                "💼 *Capital Left*: `Rs %.2f`\n" +
                "🧠 *AI Reasoning*: %s\n" +
                "━━━━━━━━━━━━━━━━━━━━━\n" +
                "💡 *Broker Setup*: Place a Target Sell Limit at *Rs %.2f* and Stop Loss at *Rs %.2f*",
                symbol, timeStr, price, quantity, buyValue, position.getTarget(), position.getStopLoss(), config.getAvailableCapital(), safeReason, position.getTarget(), position.getStopLoss());
        telegramService.sendMessage(msg);
    }
    
    @Transactional
    public synchronized void executeSell(Position position, double exitPrice) {
        if (position == null || exitPrice <= 0) {
            return;
        }

        String safeReason = position.getGeminiReasoning();
        if (safeReason != null && safeReason.length() > 1000) {
            safeReason = safeReason.substring(0, 1000);
        }

        TradeRecord record = new TradeRecord();
        record.setSymbol(position.getSymbol());
        record.setQuantity(position.getQuantity());
        record.setEntryPrice(position.getEntryPrice());
        record.setExitPrice(exitPrice);
        LocalDateTime nowIst = LocalDateTime.now(ZoneId.of("Asia/Kolkata"));
        record.setEntryTime(position.getEntryTime());
        record.setExitTime(nowIst);
        record.setGeminiReasoning(safeReason);
        
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
        
        String timeStr = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"))
                .format(DateTimeFormatter.ofPattern("dd-MMM-yyyy hh:mm a 'IST'"));
        String pnlHeader = netPnl >= 0 ? "🟢 *TARGET HIT (PROFIT BOOKED)*" : "🔴 *STOP LOSS HIT (EXIT)*";
        double pnlPct = ((exitPrice - position.getEntryPrice()) / position.getEntryPrice()) * 100.0;
        String msg = String.format(
                "%s\n" +
                "━━━━━━━━━━━━━━━━━━━━━\n" +
                "📌 *Stock*: `%s`\n" +
                "🕒 *Time*: `%s`\n" +
                "📥 *Entry Price*: `Rs %.2f`\n" +
                "📤 *Exit Price*: `Rs %.2f`\n" +
                "💵 *Net PnL*: `%sRs %.2f` (%+.2f%%)\n" +
                "💼 *Total Capital*: `Rs %.2f`\n" +
                "━━━━━━━━━━━━━━━━━━━━━",
                pnlHeader, position.getSymbol(), timeStr, position.getEntryPrice(), exitPrice,
                (netPnl >= 0 ? "+" : ""), netPnl, pnlPct,
                config.getAvailableCapital());
        telegramService.sendMessage(msg);
    }
}
