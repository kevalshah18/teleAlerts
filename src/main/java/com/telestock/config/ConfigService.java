package com.telestock.config;

import com.telestock.model.SystemConfig;
import com.telestock.repository.SystemConfigRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ConfigService {
    private final SystemConfigRepository repository;
    
    private SystemConfig currentConfig;
    
    @PostConstruct
    public void init() {
        currentConfig = repository.findById(1L).orElseGet(() -> {
            SystemConfig defaultConfig = new SystemConfig();
            return repository.save(defaultConfig);
        });

        // Auto-heal: If capital was depleted by a failed trade or corrupted below Rs 10.0, restore to benchmark
        if (currentConfig.getAvailableCapital() == null || currentConfig.getAvailableCapital() < 10.0) {
            log.info("Self-healing available capital from Rs {} to benchmark Rs 10000.00", currentConfig.getAvailableCapital());
            currentConfig.setAvailableCapital(10000.0);
            currentConfig = repository.save(currentConfig);
        }
    }
    
    public SystemConfig getConfig() {
        return repository.findById(1L).orElse(currentConfig);
    }
    
    public SystemConfig updateConfig(SystemConfig newConfig) {
        newConfig.setId(1L);
        currentConfig = repository.save(newConfig);
        return currentConfig;
    }

    public SystemConfig resetCapital(double amount) {
        SystemConfig config = getConfig();
        config.setAvailableCapital(amount > 0 ? amount : 10000.0);
        return updateConfig(config);
    }
}
