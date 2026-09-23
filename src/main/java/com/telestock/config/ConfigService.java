package com.telestock.config;

import com.telestock.model.SystemConfig;
import com.telestock.repository.SystemConfigRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ConfigService {
    private final SystemConfigRepository repository;
    
    private SystemConfig currentConfig;
    
    @PostConstruct
    public void init() {
        currentConfig = repository.findById(1L).orElseGet(() -> {
            SystemConfig defaultConfig = new SystemConfig();
            return repository.save(defaultConfig);
        });
    }
    
    public SystemConfig getConfig() {
        return repository.findById(1L).orElse(currentConfig);
    }
    
    public SystemConfig updateConfig(SystemConfig newConfig) {
        newConfig.setId(1L);
        currentConfig = repository.save(newConfig);
        return currentConfig;
    }
}
