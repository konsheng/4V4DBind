package com.vvvvdddd.bind.core;

import java.util.List;

public record BindingPage(List<BindingRecord> records, long total, int page, int pageSize) {
    public BindingPage {
        CoreChecks.require(records != null && total >= 0 && page >= 1 && page <= InputLimits.MAX_PAGE
                && pageSize >= 1 && pageSize <= InputLimits.MAX_PAGE_SIZE && records.size() <= pageSize,
                ErrorCode.INVALID_INPUT);
        records = List.copyOf(records);
    }
}
