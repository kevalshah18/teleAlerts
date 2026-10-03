package com.telestock.feed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telestock.config.ConfigService;
import com.telestock.model.MarketData;
import com.telestock.strategy.StrategyEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class LiveMarketDataService {
    private final ConfigService configService;
    private final NseSymbolDiscoveryService symbolDiscoveryService;
    @org.springframework.beans.factory.annotation.Autowired @org.springframework.context.annotation.Lazy private StrategyEngine strategyEngine;
    private final RestClient restClient = RestClient.create();
    private final ObjectMapper mapper = new ObjectMapper();
    
    private final Map<String, MarketData> latestDataMap = new ConcurrentHashMap<>();
    private int currentBatchIndex = 0;
    
    @Scheduled(fixedRate = 2000)
    public void pollMarketData() {
        if (!configService.getConfig().getTradingEnabled()) {
            return;
        }
        
        List<String> symbols = symbolDiscoveryService.getActiveSymbols();
        if (symbols == null || symbols.isEmpty()) return;
        
        int batchSize = 20;
        int totalBatches = (int) Math.ceil((double) symbols.size() / batchSize);
        
        if (currentBatchIndex >= totalBatches) {
            currentBatchIndex = 0;
        }
        
        int startIdx = currentBatchIndex * batchSize;
        int endIdx = Math.min(startIdx + batchSize, symbols.size());
        List<String> batch = symbols.subList(startIdx, endIdx);
        
        currentBatchIndex++;
        
        String symbolStr = String.join(",", batch);
        try {
            String url = "https://query1.finance.yahoo.com/v8/finance/spark?interval=5m&range=2d&symbols=" + symbolStr;
            String response = restClient.get()
                    .uri(url)
                    .header("User-Agent", "Mozilla/5.0")
                    .retrieve()
                    .body(String.class);
            
            JsonNode root = mapper.readTree(response);
            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String sym = field.getKey();
                JsonNode node = field.getValue();
                
                double ltp = node.path("previousClose").asDouble(0);
                if (node.has("regularMarketPrice")) {
                    ltp = node.path("regularMarketPrice").asDouble(0);
                } 
                
                if (node.has("close") && node.path("close").isArray() && node.path("close").size() > 0) {
                    JsonNode closeArr = node.path("close");
                    
                    // DYNAMIC WARM-UP: If engine has no history, feed it the last 2 days instantly!
                    if (strategyEngine.getCandleCloses(sym).isEmpty()) {
                        for(int i = 0; i < closeArr.size(); i++) {
                            if (!closeArr.get(i).isNull()) {
                                strategyEngine.addCandleClose(sym, closeArr.get(i).asDouble());
                            }
                        }
                    }
                    
                    // Grab the absolute latest price for LTP
                    for(int i = closeArr.size()-1; i>=0; i--) {
                        if (!closeArr.get(i).isNull()) {
                            ltp = closeArr.get(i).asDouble();
                            break;
                        }
                    }
                }

                if (ltp > 0) {
                    MarketData data = new MarketData();
                    data.setSymbol(sym);
                    data.setLtp(ltp);
                    data.setOpen(node.path("previousClose").asDouble(ltp));
                    data.setHigh(ltp); 
                    data.setLow(ltp);
                    data.setVolume(10000L); 
                    data.setTimestamp(LocalDateTime.now());
                    
                    latestDataMap.put(sym, data);
                }
            }
        } catch (Exception e) {
            log.error("Batch failed: " + e.getMessage());
        }
    }
    
    public MarketData getLatestData(String symbol) {
        return latestDataMap.get(symbol);
    }
    
    public Map<String, MarketData> getAllLatestData() {
        return latestDataMap;
    }
}
