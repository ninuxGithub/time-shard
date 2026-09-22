package org.example.shard;

/**
 * Describes one logical table that is physically sharded by time.
 *
 * Implementations are registered as Spring beans and collected automatically by
 * {@link ShardTableManager}, so no classpath scanning is needed - this keeps the
 * component fully GraalVM native-image friendly.
 *
 * @author thunderstorm.shen on 2026/09/22
 */
public interface ShardTableDescriptor {

    /**
     * Template (base) table name. Physical shard tables are named
     * {template}_{suffix}, e.g. template "api_log" -> "api_log_202609".
     * The template table itself is created from {@link #createTemplateTableDDL()}
     * and used only as the source for "CREATE TABLE ... LIKE".
     */
    String templateTable();

    /** Shard granularity for this table. */
    ShardStrategy strategy();

    /**
     * Idempotent DDL that creates the template table (columns + indexes).
     * Shard tables are created with "CREATE TABLE shard LIKE template",
     * which copies the structure and indexes automatically.
     */
    String createTemplateTableDDL();

    /** Time column used for routing and range predicates. */
    default String timeColumn() {
        return "created_at";
    }
}
