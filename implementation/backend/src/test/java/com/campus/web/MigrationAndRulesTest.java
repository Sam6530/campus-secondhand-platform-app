package com.campus.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.campus.shared.api.ApiException;
import com.campus.shared.db.DatabaseMigrationRunner;
import com.campus.shared.db.HealthController;
import com.campus.shared.text.PublicContentSanitizer;
import com.campus.support.TestActorConfig;
import com.campus.trade.item.PriceParser;

/** 迁移可重复、健康检查可定位版本，以及价格/脱敏规则的单点自测（TC-RB-28 及 BR02/BR14）。 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestActorConfig.class)
class MigrationAndRulesTest {

    @Autowired
    private DatabaseMigrationRunner migrationRunner;
    @Autowired
    private HealthController healthController;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("重复执行结构迁移不清数据、不改版本记录")
    void migrationIsRepeatable() {
        int version = migrationRunner.latestAppliedVersion();
        assertThat(version).isEqualTo(1);
        Integer before = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM schema_migrations", Integer.class);
        migrationRunner.migrate();
        migrationRunner.migrate();
        assertThat(migrationRunner.latestAppliedVersion()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM schema_migrations", Integer.class))
                .isEqualTo(before);
    }

    @Test
    @DisplayName("健康检查返回可用与结构版本")
    void healthReportsSchema() {
        ResponseEntity<java.util.Map<String, Object>> response = healthController.health();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP").containsEntry("schema", "V1");
    }

    @Test
    @DisplayName("迁移脚本按分号切分并忽略整行注释")
    void splitStatementsIgnoresComments() {
        assertThat(DatabaseMigrationRunner.splitStatements("-- 注释\nSELECT 1;\n\nSELECT 2;"))
                .containsExactly("SELECT 1", "SELECT 2");
    }

    @Test
    @DisplayName("价格必须为正数且最多两位小数")
    void priceRules() {
        assertThat(PriceParser.parse("25.5")).isEqualByComparingTo(new BigDecimal("25.50"));
        assertThat(PriceParser.parse("9999999999.99")).isEqualByComparingTo(new BigDecimal("9999999999.99"));
        assertThat(catchApi(() -> PriceParser.parse("0"))).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(catchApi(() -> PriceParser.parse("-1"))).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(catchApi(() -> PriceParser.parse("1.234"))).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(catchApi(() -> PriceParser.parse(" "))).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("公开脱敏只替换联系方式，不影响正常型号")
    void sanitizerKeepsModelNumbers() {
        assertThat(PublicContentSanitizer.sanitize("iPhone13 九五新，联系13800138000"))
                .isEqualTo("iPhone13 九五新，联系[联系方式已隐藏]");
        assertThat(PublicContentSanitizer.sanitize("邮箱 abc@example.com 或QQ:123456789"))
                .isEqualTo("邮箱 [联系方式已隐藏] 或[联系方式已隐藏]");
        assertThat(PublicContentSanitizer.sanitize(null)).isNull();
    }

    private HttpStatus catchApi(Runnable action) {
        try {
            action.run();
        } catch (ApiException ex) {
            return HttpStatus.valueOf(ex.getStatus().value());
        }
        throw new AssertionError("预期业务异常未抛出");
    }
}
