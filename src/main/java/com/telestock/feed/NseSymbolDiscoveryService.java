package com.telestock.feed;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Service
@Slf4j
public class NseSymbolDiscoveryService {
    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonFactory factory = new JsonFactory();
    
    private List<String> activeSymbols = new ArrayList<>();
    
    private final List<String> fallbackNifty50 = Arrays.asList(
        "RELIANCE.NS","TCS.NS","HDFCBANK.NS","ICICIBANK.NS","BHARTIARTL.NS","SBIN.NS","INFY.NS",
        "LICI.NS","ITC.NS","HINDUNILVR.NS","LARSEN.NS","BAJFINANCE.NS","HCLTECH.NS","MARUTI.NS",
        "SUNPHARMA.NS","TATAMOTORS.NS","M&M.NS","KOTAKBANK.NS","ASIANPAINT.NS","TITAN.NS","ONGC.NS",
        "NTPC.NS","ADANIENT.NS","ULTRACEMCO.NS","POWERGRID.NS","BAJAJFINSV.NS","COALINDIA.NS",
        "ADANIPORTS.NS","TATASTEEL.NS","BAJAJ-AUTO.NS","JSWSTEEL.NS","HAL.NS","GRASIM.NS","ZOMATO.NS",
        "TECHM.NS","WIPRO.NS","SIEMENS.NS","HINDALCO.NS","NESTLEIND.NS","DLF.NS","IOC.NS","LTIM.NS",
        "BEL.NS","INDUSINDBK.NS","CHOLAFIN.NS","SBILIFE.NS","PIDILITIND.NS","TRENT.NS","EICHERMOT.NS","DRREDDY.NS"
    );

        @PostConstruct
    public void init() {
        // Start with NIFTY 50 immediately so the server doesn't crash on cold boot
        activeSymbols = new ArrayList<>(fallbackNifty50);
        
        // Fetch the massive 70MB JSON in a background thread so we don't block Tomcat from opening the port!
        new Thread(() -> {
            refreshSymbols();
        }).start();
    }

    // Refresh every day at 8 AM IST
    @Scheduled(cron = "0 0 8 * * ?")
    public void refreshSymbols() {
        log.info("Fetching dynamic active NSE symbols from public master list...");
        try {
            URL url = new URL("https://margincalculator.angelbroking.com/OpenAPI_File/files/OpenAPIScripMaster.json");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            
            try (InputStream in = conn.getInputStream();
                 JsonParser parser = factory.createParser(in)) {
                 
                List<String> tempSymbols = new ArrayList<>();
                
                if (parser.nextToken() == JsonToken.START_ARRAY) {
                    while (parser.nextToken() == JsonToken.START_OBJECT) {
                        JsonNode node = mapper.readTree(parser);
                        String exchSeg = node.path("exch_seg").asText("");
                        String symbol = node.path("symbol").asText("");
                        
                        // AngelOne has "-EQ" suffix for NSE Equities.
                        if ("NSE".equals(exchSeg) && symbol.endsWith("-EQ")) {
                            String cleanSymbol = symbol.replace("-EQ", "");
                            if (cleanSymbol.matches("^[A-Z0-9]+$")) {
                                tempSymbols.add(cleanSymbol + ".NS");
                            }
                        }
                    }
                }
                
                if (!tempSymbols.isEmpty()) {
                    activeSymbols = tempSymbols;
                    log.info("Successfully discovered {} active NSE stocks dynamically.", activeSymbols.size());
                } else {
                    log.warn("AngelOne JSON returned 0 valid NSE equities. Falling back to NIFTY 50.");
                    activeSymbols = fallbackNifty50;
                }
            }
        } catch (Exception e) {
            log.error("Failed to fetch dynamic symbols. Falling back to NIFTY 50.", e);
            activeSymbols = fallbackNifty50;
        }
    }

    public List<String> getActiveSymbols() {
        return activeSymbols;
    }
}
