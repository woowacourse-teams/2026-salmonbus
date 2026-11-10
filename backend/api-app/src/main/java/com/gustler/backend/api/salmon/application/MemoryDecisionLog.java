package com.gustler.backend.api.salmon.application;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 프로세스 안의 판정 기록. 재시작하면 사라진다. */
public final class MemoryDecisionLog implements DecisionLog {
    private final List<SalmonDecisionRecord> records = new CopyOnWriteArrayList<>();

    @Override
    public void append(SalmonDecisionRecord record) {
        records.add(record);
    }

    public List<SalmonDecisionRecord> records() {
        return List.copyOf(records);
    }
}
