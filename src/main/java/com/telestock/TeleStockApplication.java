package com.telestock;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.TimeZone;

@SpringBootApplication
@EnableScheduling
@EnableAsync
public class TeleStockApplication {

	@PostConstruct
	public void init() {
		// Enforce Indian Standard Time (IST) across all JVM operations, logs, and dates
		TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
	}

	public static void main(String[] args) {
		SpringApplication.run(TeleStockApplication.class, args);
	}
}
