package org.example.shard;

import java.util.List;

/**
 * Simple page wrapper for sharded range queries.
 *
 * @author thunderstorm.shen on 2026/09/22
 */
public record PageResult<T>(long total, int page, int size, List<T> items) {
}
