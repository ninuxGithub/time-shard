package org.example.shard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Creates template/current/next shard tables on startup and pre-creates the
 * upcoming shard table on a daily schedule, so writes never hit a missing table
 * when crossing a year/month/day boundary.
 *
 * @author thunderstorm.shen on 2026/09/22
 */
@Component
public class ShardTableInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ShardTableInitializer.class);

    private final ShardTableManager shardTableManager;

    public ShardTableInitializer(ShardTableManager shardTableManager) {
        this.shardTableManager = shardTableManager;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            shardTableManager.initializeAll();
            log.info("Shard tables initialized on startup");
        } catch (Exception e) {
            // Do not abort application startup if DB is temporarily unavailable
            log.error("Failed to initialize shard tables on startup", e);
        }
    }

    /**
     * Daily at 00:05 make sure the next period's shard tables exist.
     * Running it every day is cheap: CREATE TABLE IF NOT EXISTS is a no-op
     * when the table already exists.
     */
    @Scheduled(cron = "0 5 0 * * *")
    public void preCreateUpcomingTables() {
        LocalDateTime nextPeriodStart = LocalDateTime.now();
        for (ShardTableDescriptor descriptor : shardTableManager.getDescriptors()) {
            try {
                ensureCurrentAndNext(descriptor, nextPeriodStart);
            } catch (Exception e) {
                log.error("Failed to pre-create shard table for {}",
                        descriptor.templateTable(), e);
            }
        }
    }

    private void ensureCurrentAndNext(ShardTableDescriptor descriptor, LocalDateTime now) {
        shardTableManager.ensureShardTable(descriptor, now);
        shardTableManager.ensureShardTable(descriptor, descriptor.strategy().next(now));
    }
}
