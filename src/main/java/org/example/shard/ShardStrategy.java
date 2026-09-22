package org.example.shard;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Time-based table sharding strategies.
 *
 * Physical table name pattern: {template}_{suffix}
 *   YEAR  -> api_log_2026
 *   MONTH -> api_log_202609
 *   DAY   -> api_log_20260922
 *
 * @author thunderstorm.shen on 2026/09/22
 */
public enum ShardStrategy {

    YEAR("yyyy", Period.ofYears(1)),
    MONTH("yyyyMM", Period.ofMonths(1)),
    DAY("yyyyMMdd", Period.ofDays(1));

    private final DateTimeFormatter suffixFormatter;
    private final Period step;

    ShardStrategy(String suffixPattern, Period step) {
        this.suffixFormatter = DateTimeFormatter.ofPattern(suffixPattern);
        this.step = step;
    }

    /** Build the table-name suffix for the given time, e.g. 2026 / 202609 / 20260922. */
    public String suffix(LocalDateTime time) {
        return time.format(suffixFormatter);
    }

    /** Truncate a time down to the first moment of its shard period. */
    public LocalDateTime truncate(LocalDateTime time) {
        LocalDate date = time.toLocalDate();
        return switch (this) {
            case YEAR -> date.withDayOfYear(1).atStartOfDay();
            case MONTH -> date.withDayOfMonth(1).atStartOfDay();
            case DAY -> date.atStartOfDay();
        };
    }

    /** Return the first moment of the next shard period. */
    public LocalDateTime next(LocalDateTime time) {
        return truncate(time).plus(step);
    }

    /**
     * Enumerate every shard start time overlapping the half-open range [start, end).
     * Returns an empty list when start or end is null (caller decides fallback).
     */
    public List<LocalDateTime> enumerate(LocalDateTime start, LocalDateTime end) {
        List<LocalDateTime> result = new ArrayList<>();
        if (start == null || end == null || !end.isAfter(start)) {
            return result;
        }
        LocalDateTime cursor = truncate(start);
        while (cursor.isBefore(end)) {
            result.add(cursor);
            cursor = cursor.plus(step);
        }
        return result;
    }
}
