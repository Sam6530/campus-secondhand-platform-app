package com.campus.shared.db;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * B 维护的共享迁移序列执行器（不绑定具体迁移工具品牌）：
 * - 目录 classpath:db/migration/V{n}__*.sql 按版本升序执行；
 * - schema_migrations 记录版本、脚本名、校验和与时间，重复启动只做校验；
 * - 已执行脚本被改动直接失败启动，要求新增升级脚本；
 * - 多实例启动用 MySQL 命名锁串行化，避免并发建表。
 */
@Component
public class DatabaseMigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseMigrationRunner.class);
    private static final Pattern FILE_PATTERN = Pattern.compile("^V(\\d+)__(.+)\\.sql$");

    private final DataSource dataSource;
    private final boolean enabled;

    public DatabaseMigrationRunner(DataSource dataSource,
                                   @Value("${campus.migration.enabled:true}") boolean enabled) {
        this.dataSource = dataSource;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.warn("campus.migration.enabled=false：启动跳过结构迁移，仅用于已确认结构的环境");
            return;
        }
        migrate();
    }

    public synchronized void migrate() {
        List<Migration> migrations = loadMigrations();
        try (Connection connection = dataSource.getConnection()) {
            setAutoCommit(connection, true);
            try (Statement statement = connection.createStatement();
                 ResultSet lock = statement.executeQuery("SELECT GET_LOCK('campus_schema_migration', 30)")) {
                if (!lock.next() || lock.getInt(1) != 1) {
                    throw new IllegalStateException("未取得结构迁移锁（可能有另一实例正在迁移），本次启动不继续执行迁移");
                }
            }
            try {
                ensureSchemaTable(connection);
                for (Migration migration : migrations) {
                    String checksum = findChecksum(connection, migration.version());
                    if (checksum != null) {
                        if (!checksum.equals(migration.checksum())) {
                            throw new IllegalStateException("已执行的迁移脚本被修改：V" + migration.version()
                                    + "__" + migration.name() + "；请新增升级脚本，不要改写历史迁移");
                        }
                        continue;
                    }
                    apply(connection, migration);
                    log.info("已执行结构迁移 V{}__{}", migration.version(), migration.name());
                }
            } finally {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SELECT RELEASE_LOCK('campus_schema_migration')");
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("结构迁移失败：" + ex.getMessage(), ex);
        }
    }

    /** 当前已执行的最大迁移版本，供健康检查可定位版本。 */
    public int latestAppliedVersion() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_migrations")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException ex) {
            throw new IllegalStateException("读取迁移版本失败：" + ex.getMessage(), ex);
        }
    }


    private void ensureSchemaTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS schema_migrations (
                        version    INT          NOT NULL,
                        script     VARCHAR(120) NOT NULL,
                        checksum   CHAR(64)     NOT NULL,
                        applied_at DATETIME(3)  NOT NULL,
                        PRIMARY KEY (version)
                    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
                    """);
        }
    }

    private void apply(Connection connection, Migration migration) throws SQLException {
        setAutoCommit(connection, false);
        try {
            for (String sql : splitStatements(migration.sql())) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(sql);
                }
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO schema_migrations (version, script, checksum, applied_at) VALUES (?, ?, ?, ?)")) {
                insert.setInt(1, migration.version());
                insert.setString(2, "V" + migration.version() + "__" + migration.name() + ".sql");
                insert.setString(3, migration.checksum());
                insert.setObject(4, Utc.now());
                insert.executeUpdate();
            }
            connection.commit();
        } catch (SQLException ex) {
            connection.rollback();
            throw new SQLException("执行 V" + migration.version() + "__" + migration.name() + " 失败："
                    + ex.getMessage(), ex);
        } finally {
            setAutoCommit(connection, true);
        }
    }

    private String findChecksum(Connection connection, int version) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT checksum FROM schema_migrations WHERE version = ?")) {
            select.setInt(1, version);
            try (ResultSet rs = select.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private void setAutoCommit(Connection connection, boolean value) throws SQLException {
        if (connection.getAutoCommit() != value) {
            connection.setAutoCommit(value);
        }
    }

    private List<Migration> loadMigrations() {
        List<Migration> migrations = new ArrayList<>();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:db/migration/V*__*.sql");
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename == null) {
                    continue;
                }
                Matcher matcher = FILE_PATTERN.matcher(filename);
                if (!matcher.matches()) {
                    continue;
                }
                String sql = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                migrations.add(new Migration(Integer.parseInt(matcher.group(1)), matcher.group(2),
                        sha256(sql.getBytes(StandardCharsets.UTF_8)), sql));
            }
        } catch (IOException ex) {
            throw new IllegalStateException("读取结构迁移脚本失败：" + ex.getMessage(), ex);
        }
        migrations.sort(Comparator.comparingInt(Migration::version));
        return migrations;
    }

    /** 去掉整行注释与空行后按分号切分；迁移脚本不得包含存储过程或字符串内分号。 */
    public static List<String> splitStatements(String sql) {
        StringBuilder cleaned = new StringBuilder();
        for (String line : sql.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                continue;
            }
            cleaned.append(line).append('\n');
        }
        List<String> statements = new ArrayList<>();
        for (String part : cleaned.toString().split(";")) {
            if (!part.isBlank()) {
                statements.add(part.trim());
            }
        }
        return statements;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private record Migration(int version, String name, String checksum, String sql) {
    }
}
