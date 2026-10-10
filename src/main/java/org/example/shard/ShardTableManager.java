package org.example.shard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Creates and inspects physical shard tables.
 *
 * Every shard table is created with "CREATE TABLE IF NOT EXISTS ... LIKE template",
 * which copies columns and indexes from the template table.
 *
 * @author thunderstorm.shen on 2026/09/22
 */
@Service
public class ShardTableManager {

    private static final Logger log = LoggerFactory.getLogger(ShardTableManager.class);

    /** Number of shard periods (e.g. months) created at startup, including the current one. */
    private static final int INITIAL_SHARD_COUNT = 6;

    private final JdbcTemplate jdbcTemplate;
    private final List<ShardTableDescriptor> descriptors;

    public ShardTableManager(JdbcTemplate jdbcTemplate,
                             List<ShardTableDescriptor> descriptors) {
        this.jdbcTemplate = jdbcTemplate;
        this.descriptors = descriptors;
    }

    /** All registered sharded table descriptors. */
    public List<ShardTableDescriptor> getDescriptors() {
        return descriptors;
    }

    /** Create the template table plus shard tables for the next {@link #INITIAL_SHARD_COUNT} periods. */
    public void initializeAll() {
        for (ShardTableDescriptor descriptor : descriptors) {
            createTemplateTable(descriptor);
            LocalDateTime time = LocalDateTime.now();
            for (int i = 0; i < INITIAL_SHARD_COUNT; i++) {
                ensureShardTable(descriptor, time);
                time = descriptor.strategy().next(time);
            }
        }
    }

    /** Create the template table if it does not exist. */
    public void createTemplateTable(ShardTableDescriptor descriptor) {
        String ddl = descriptor.createTemplateTableDDL();
        log.info("Create template table DDL: {}", ddl);
        jdbcTemplate.execute(ddl);
        log.info("Shard template table ensured: {}", descriptor.templateTable());
    }

    /** Ensure the shard table that holds the given time exists. */
    public void ensureShardTable(ShardTableDescriptor descriptor, LocalDateTime time) {
        String physicalTable = descriptor.templateTable() + "_"
                + descriptor.strategy().suffix(time);
        String sql = "CREATE TABLE IF NOT EXISTS `" + physicalTable + "` LIKE `"
                + descriptor.templateTable() + "`";
        log.info("Create shard table DDL: {}", sql);
        jdbcTemplate.execute(sql);
        log.debug("Shard table ensured: {}", physicalTable);
    }

    /** Backfill all shard tables overlapping [start, end), useful for manual ops. */
    public void ensureShardTables(ShardTableDescriptor descriptor,
                                  LocalDateTime start, LocalDateTime end) {
        descriptor.strategy().enumerate(start, end)
                .forEach(t -> ensureShardTable(descriptor, t));
    }

    /** List existing physical shard tables of one descriptor, ordered by name. */
    public List<String> listPhysicalTables(ShardTableDescriptor descriptor) {
        String template = descriptor.templateTable();
        // Only accept a numeric suffix (yyyy / yyyyMM / yyyyMMdd), so manual
        // tables like api_log_backup are not reported as shard tables
        String numericSuffix = "^" + template + "_[0-9]+$";
        return jdbcTemplate.queryForList(
                "SELECT TABLE_NAME FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME REGEXP ? "
                        + "ORDER BY TABLE_NAME",
                String.class, numericSuffix);
    }
}
