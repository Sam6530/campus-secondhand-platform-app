package com.campus.shared.db;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.campus.shared.api.ApiException;

/** GET /api/health：应用与数据库可用、可定位结构迁移版本；数据库不可用时 503。 */
@RestController
public class HealthController {

    private final JdbcTemplate jdbcTemplate;
    private final DatabaseMigrationRunner migrationRunner;

    public HealthController(JdbcTemplate jdbcTemplate, DatabaseMigrationRunner migrationRunner) {
        this.jdbcTemplate = jdbcTemplate;
        this.migrationRunner = migrationRunner;
    }

    @GetMapping("/api/health")
    public ResponseEntity<Map<String, Object>> health() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            int version = migrationRunner.latestAppliedVersion();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "UP");
            body.put("database", "MySQL");
            body.put("schema", "V" + version);
            body.put("checkedAt", Utc.toInstant(Utc.now()));
            return ResponseEntity.ok(body);
        } catch (DataAccessException ex) {
            throw ApiException.serviceUnavailable("数据库暂不可用");
        } catch (IllegalStateException ex) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "结构迁移版本不可读");
        }
    }
}
