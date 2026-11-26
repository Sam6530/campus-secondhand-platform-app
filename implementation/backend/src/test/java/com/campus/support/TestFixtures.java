package com.campus.support;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 合成数据与清理：每个用例独立前态（U1/U2/U3、S、G、I1、I2、O1）。 */
@Component
public class TestFixtures {

    /** 防止误把测试指向联调/真实库后清空数据；确需他库时显式设置环境变量。 */
    private static final boolean ALLOW_NON_TEST_DB =
            "true".equalsIgnoreCase(System.getenv("CAMPUS_ALLOW_NON_TEST_DB"));

    private final JdbcTemplate jdbc;

    public TestFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Account(String id, String publicRef) {
    }

    public Account createUser(String username, String nickname, int verified, int restricted) {
        return createAccount(username, nickname, "USER", verified, restricted);
    }

    public Account createAdmin(String username) {
        return createAccount(username, "审核员" + username, "ADMIN", 1, 0);
    }

    public Account createAccount(String username, String nickname, String role, int verified, int restricted) {
        String id = UUID.randomUUID().toString();
        String publicRef = "sp-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        jdbc.update("""
                        INSERT INTO accounts (id, public_ref, username, nickname, role, verified, restricted, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                id, publicRef, username, nickname, role, verified, restricted, LocalDateTime.now(ZoneOffset.UTC));
        return new Account(id, publicRef);
    }

    /** 直接写入资源元数据（自测不校验真实图片字节）。 */
    public String createAsset(String uploaderId, String kind) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                        INSERT INTO assets (id, uploader_id, kind, storage_key, content_type, size_bytes, created_at)
                        VALUES (?, ?, ?, ?, 'image/png', 10, ?)
                        """,
                id, uploaderId, kind, id + ".png", LocalDateTime.now(ZoneOffset.UTC));
        return id;
    }

    /** 已审核通过、可直接交易的物品（跳过发布+审核流程时使用）。 */
    public String createSellableItem(String sellerId, String title, String price) {
        String itemId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
                        INSERT INTO items (id, seller_id, status, current_version, image_id, hidden, relist_allowed,
                                           created_at, updated_at)
                        VALUES (?, ?, '在售', 1, NULL, 0, 1, ?, ?)
                        """,
                itemId, sellerId, now, now);
        jdbc.update("""
                        INSERT INTO item_versions (id, item_id, version, title, category, description, price, image_id,
                                                   review_status, review_reason, reviewed_by, reviewed_at, created_at)
                        VALUES (?, ?, 1, ?, '教材书籍', '合成描述', ?, NULL, '通过', '合成前态', NULL, ?, ?)
                        """,
                UUID.randomUUID().toString(), itemId, title, new BigDecimal(price), now, now);
        return itemId;
    }

    public void reset() {
        String database = jdbc.queryForObject("SELECT DATABASE()", String.class);
        if (!ALLOW_NON_TEST_DB && (database == null || !database.toLowerCase().contains("selftest"))) {
            throw new IllegalStateException("拒绝对非测试库执行清库：" + database
                    + "；请把 DB_URL 指向 campus_resale_selftest，或显式设置 CAMPUS_ALLOW_NON_TEST_DB=true");
        }
        List<String> tables = List.of("c_messages", "c_verifications", "c_credentials", "notifications", "notification_outbox", "deliveries", "reservations",
                "audit_facts", "operation_receipts", "orders", "item_versions", "items", "assets", "accounts");
        for (String table : tables) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    /** 审计事实（按时间升序），供用例断言操作者/对象/前后态/原因/结果。 */
    public List<Map<String, Object>> auditRows(String objectId) {
        return jdbc.queryForList("""
                SELECT actor_id, object_type, object_id, action, from_state, to_state, reason, result
                FROM audit_facts
                WHERE object_id = ?
                ORDER BY created_at ASC, id ASC
                """, objectId);
    }

    public int count(String table, String where, Object... args) {
        String sql = "SELECT COUNT(*) FROM " + table + (where == null ? "" : " WHERE " + where);
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    public String itemStatus(String itemId) {
        return jdbc.queryForObject("SELECT status FROM items WHERE id = ?", String.class, itemId);
    }

    public String orderStage(String orderId) {
        return jdbc.queryForObject("SELECT stage FROM orders WHERE id = ?", String.class, orderId);
    }

    public String outboxStatus(String eventKey) {
        List<String> rows = jdbc.queryForList(
                "SELECT status FROM notification_outbox WHERE event_key = ?", String.class, eventKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int auditCount(String objectId) {
        return count("audit_facts", "object_id = ?", objectId);
    }

    public void markDisputed(String orderId) {
        jdbc.update("UPDATE orders SET disputed = 1 WHERE id = ?", orderId);
    }

    public void restrict(String accountId) {
        jdbc.update("UPDATE accounts SET restricted = 1 WHERE id = ?", accountId);
    }

    public void hideItem(String itemId) {
        jdbc.update("UPDATE items SET hidden = 1 WHERE id = ?", itemId);
    }

    public void setVerified(String accountId, int verified) {
        jdbc.update("UPDATE accounts SET verified = ? WHERE id = ?", verified, accountId);
    }

    public void renameTable(String from, String to) {
        jdbc.execute("RENAME TABLE " + from + " TO " + to);
    }
}
