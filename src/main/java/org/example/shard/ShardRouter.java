package org.example.shard;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Routes logical sharded tables to physical table names based on time.
 *
 * @author thunderstorm.shen on 2026/09/22
 */
@Component
public class ShardRouter {

    private final int maxTablesPerQuery;

    public ShardRouter(@Value("${shard.max-tables-per-query:400}") int maxTablesPerQuery) {
        this.maxTablesPerQuery = maxTablesPerQuery;
    }

    /** Physical table holding data for the given time, e.g. api_log_202609. */
    public String physicalTable(ShardTableDescriptor descriptor, LocalDateTime time) {
        return descriptor.templateTable() + "_" + descriptor.strategy().suffix(time);
    }

    /**
     * All physical tables overlapping the half-open range [start, end).
     *
     * Fallback rules when boundaries are missing:
     *   - both null  -> the shard of "now"
     *   - only one   -> the shard of that boundary
     *
     * @throws IllegalStateException when the range spans too many shard tables
     *         (guards against accidentally generating huge UNION ALL statements)
     */
    public List<String> physicalTables(ShardTableDescriptor descriptor,
                                       LocalDateTime start, LocalDateTime end) {
        LocalDateTime effectiveStart = start != null ? start : (end != null ? end : LocalDateTime.now());
        LocalDateTime effectiveEnd = end != null ? end : effectiveStart;

        if (!effectiveEnd.isAfter(effectiveStart)) {
            // Empty or reversed range: resolve to the single shard of the start point
            return List.of(physicalTable(descriptor, effectiveStart));
        }

        // Iterate period by period and bail out as soon as the guard is hit,
        // so a huge DAY-granularity range never expands to millions of entries
        ShardStrategy strategy = descriptor.strategy();
        List<String> tables = new ArrayList<>();
        LocalDateTime cursor = strategy.truncate(effectiveStart);
        while (cursor.isBefore(effectiveEnd)) {
            if (tables.size() >= maxTablesPerQuery) {
                throw new IllegalStateException(
                        "Time range spans more than " + maxTablesPerQuery
                                + " shard tables for '" + descriptor.templateTable()
                                + "'. Please narrow the query range.");
            }
            tables.add(physicalTable(descriptor, cursor));
            cursor = strategy.next(cursor);
        }
        return tables;
    }
}
