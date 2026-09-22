package org.example.shard;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Base repository for time-sharded tables backed by JdbcTemplate.
 *
 * Routing rules:
 *   - insert: routed to the single shard table of the entity's shard time
 *   - range query: every overlapping shard table is queried and merged with
 *     UNION ALL, then sorted/paged globally
 *
 * Subclasses define table metadata, row mapping and insert bindings only.
 *
 * @author thunderstorm.shen on 2026/09/22
 */
public abstract class AbstractShardedRepository<T> {

    protected final JdbcTemplate jdbcTemplate;
    protected final ShardRouter shardRouter;

    protected AbstractShardedRepository(JdbcTemplate jdbcTemplate, ShardRouter shardRouter) {
        this.jdbcTemplate = jdbcTemplate;
        this.shardRouter = shardRouter;
    }

    // ---- metadata supplied by subclasses ----

    protected abstract ShardTableDescriptor descriptor();

    protected abstract RowMapper<T> rowMapper();

    /** Inserted columns excluding the auto-generated id. */
    protected abstract String[] insertColumns();

    /** Bind insert parameters in the exact order of {@link #insertColumns()}. */
    protected abstract void bindInsert(PreparedStatement ps, T entity) throws SQLException;

    /** Extract the time that determines which shard table receives this entity. */
    protected abstract LocalDateTime shardTimeOf(T entity);

    /** Backfill the auto-generated id after insert. */
    protected abstract void applyGeneratedId(T entity, long id);

    // ---- write ----

    /** Insert into the shard table derived from the entity's shard time. */
    public T save(T entity) {
        ShardTableDescriptor descriptor = descriptor();
        String physicalTable = shardRouter.physicalTable(descriptor, shardTimeOf(entity));

        String[] columns = insertColumns();
        String placeholders = String.join(",", java.util.Collections.nCopies(columns.length, "?"));
        String sql = "INSERT INTO `" + physicalTable + "` ("
                + String.join(",", columns) + ") VALUES (" + placeholders + ")";

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update((PreparedStatementCreator) con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            bindInsert(ps, entity);
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key != null) {
            applyGeneratedId(entity, key.longValue());
        }
        return entity;
    }

    // ---- read ----

    /**
     * Query entities over the half-open range [start, end).
     *
     * @param filterSql    extra WHERE fragment WITHOUT the "WHERE" keyword,
     *                     starting with "AND", e.g. "AND app_key = ?"; null allowed
     * @param filterParams bind params for the filter fragment, repeated per shard
     * @param desc         true = newest first
     */
    public List<T> queryRange(LocalDateTime start, LocalDateTime end,
                              String filterSql, Object[] filterParams,
                              boolean desc, int limit, long offset) {
        QueryParts parts = buildUnionQuery(start, end, filterSql, filterParams);

        String direction = desc ? "DESC" : "ASC";
        String sql = parts.sql()
                + " ORDER BY " + descriptor().timeColumn() + " " + direction + ", id " + direction
                + " LIMIT " + limit + " OFFSET " + offset;

        return jdbcTemplate.query(sql, rowMapper(), parts.args().toArray());
    }

    /** Count entities over the half-open range [start, end). */
    public long countRange(LocalDateTime start, LocalDateTime end,
                           String filterSql, Object[] filterParams) {
        QueryParts parts = buildUnionQuery(start, end, filterSql, filterParams);
        String sql = "SELECT COUNT(*) FROM (" + parts.sql() + ") shard_union";
        Long count = jdbcTemplate.queryForObject(sql, Long.class, parts.args().toArray());
        return count == null ? 0L : count;
    }

    // ---- internals ----

    private QueryParts buildUnionQuery(LocalDateTime start, LocalDateTime end,
                                       String filterSql, Object[] filterParams) {
        ShardTableDescriptor descriptor = descriptor();
        List<String> tables = shardRouter.physicalTables(descriptor, start, end);
        String timeColumn = descriptor.timeColumn();

        List<String> selects = new ArrayList<>(tables.size());
        List<Object> args = new ArrayList<>();

        for (String table : tables) {
            StringBuilder sb = new StringBuilder("SELECT * FROM `").append(table).append("` WHERE 1=1");
            if (start != null) {
                sb.append(" AND ").append(timeColumn).append(" >= ?");
                args.add(start);
            }
            if (end != null) {
                sb.append(" AND ").append(timeColumn).append(" < ?");
                args.add(end);
            }
            if (filterSql != null && !filterSql.isBlank()) {
                sb.append(' ').append(filterSql);
                if (filterParams != null) {
                    args.addAll(List.of(filterParams));
                }
            }
            selects.add(sb.toString());
        }

        return new QueryParts(String.join(" UNION ALL ", selects), args);
    }

    private record QueryParts(String sql, List<Object> args) {
    }
}
