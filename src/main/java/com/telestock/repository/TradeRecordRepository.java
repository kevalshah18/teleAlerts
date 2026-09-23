package com.telestock.repository;

import com.telestock.model.TradeRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TradeRecordRepository extends JpaRepository<TradeRecord, Long> {
}
