package cn.campus;

import static cn.campus.Store.p;
import static cn.campus.MarketService.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/**
 * V3 Issue #15/#16/#17 的后端回归（独立上下文与独立内存库，test scope）。
 * #10/#18/#19 为前端行为问题，由 frontend/test/behavior.test.mjs 覆盖。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:v3fix;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "app.upload-dir=./target/test-uploads", "app.seed=true" })
@AutoConfigureMockMvc
class V3IssueFixTest {
    @Autowired
    MarketService service;
    @Autowired
    Store db;
    @Autowired
    MockMvc mvc;

    String uid(String name) {
        return s(db.one("select id from accounts where username=#{p.u}", p("u", name)), "id");
    }

    String seller() {
        return uid("seller");
    }

    String buyer() {
        return uid("buyer");
    }

    String admin() {
        return uid("admin");
    }

    MockHttpSession session(String name) {
        var s = new MockHttpSession();
        s.setAttribute("uid", uid(name));
        s.setAttribute("csrf", "csrf-" + name);
        return s;
    }

    String asset() {
        return s(db.one("select id from assets where owner_id=#{p.u} and kind='ITEM' limit 1", p("u", seller())), "id");
    }

    Map<String, Object> createItem(String title) {
        return service.saveItem(seller(), null, p("title", title + " " + id(), "category", "教材书籍",
                "description", "V3 回归测试用品，非真实交易。", "price", "12.00", "image_id", asset()));
    }

    Map<String, Object> onSale() {
        var i = createItem("在售回归物品");
        service.manage(admin(), "item", s(i, "id"), p("reason", "回归审核通过", "pass", true, "version", i.get("version")));
        return service.item(s(i, "id"), false);
    }

    Map<String, Object> buy(Map<String, Object> item) {
        return service.createOrder(buyer(), p("item_id", item.get("id"), "version", item.get("version")), id());
    }

    Map<String, Object> act(String who, String oid, String action) {
        return service.action(who, oid, action, p("reason", "回归测试动作", "declared", true), id());
    }

    long mineTotal(String user) {
        return ((Number) db.one("select count(*) as c from items where seller_id=#{p.u}", p("u", user)).get("c")).longValue();
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> items(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("items");
    }

    // ---------------- #15 我的发布分页 ----------------

    @Test
    void minePaginationReturnsEnvelopeAndClampsOutOfRangePages() {
        String me = seller();
        // 造出超过一页（20 条）的数据，验证翻页、越界与上限。
        for (int i = 0; i < 23; i++)
            createItem("分页物品 " + i);
        long total = mineTotal(me);
        assertTrue(total >= 20, "测试数据应超过一页：" + total);
        int totalPages = (int) Math.max(1, (total + 19) / 20);

        var first = service.mine(me, 1, 20);
        assertEquals(1, n(first, "page"));
        assertEquals(20, n(first, "size"));
        assertEquals(total, ((Number) first.get("total")).longValue());
        assertEquals(totalPages, n(first, "totalPages"));
        assertEquals(20, items(first).size());
        assertTrue(items(first).stream().allMatch(r -> me.equals(s(r, "seller_id"))), "只返回本人发布");

        var second = service.mine(me, 2, 20);
        assertEquals(2, n(second, "page"));
        assertEquals(total - 20, items(second).size());

        Set<String> ids = new HashSet<>();
        items(first).forEach(r -> ids.add(s(r, "id")));
        items(second).forEach(r -> ids.add(s(r, "id")));
        assertEquals(total, ids.size(), "两页之间不重复、不遗漏");

        var beyond = service.mine(me, 999, 20);
        assertEquals(totalPages, n(beyond, "page"), "页码越界回到最后有效页");
        assertEquals(total - (totalPages - 1) * 20L, items(beyond).size());

        assertEquals(1, n(service.mine(me, 0, 20), "page"), "页码下限为 1");
        assertEquals(1, n(service.mine(me, -3, 20), "page"));

        assertEquals(20, n(service.mine(me, 1, 0), "size"), "每页默认 20 条");
        assertEquals(20, n(service.mine(me, 1, -5), "size"));
        assertEquals(MINE_MAX_SIZE, n(service.mine(me, 1, 999), "size"), "每页上限 50 条");
        assertTrue(items(service.mine(me, 1, 999)).size() <= MINE_MAX_SIZE);
    }

    @Test
    void mineHttpEnvelopeIsPagedAndRequiresLogin() throws Exception {
        String me = seller();
        for (int i = 0; i < 6; i++)
            createItem("HTTP 分页物品 " + i);
        long total = mineTotal(me);
        long totalPages = Math.max(1, (total + 4) / 5);

        var body = om(mvc.perform(get("/api/items/mine").param("page", "2").param("size", "5").session(session("seller")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.items").isArray())
                .andReturn().getResponse().getContentAsString());
        assertEquals(total, body.get("total").asLong());
        assertEquals(totalPages, body.get("totalPages").asLong());
        assertEquals(Math.min(5, Math.max(0, total - 5)), body.get("items").size());

        var clamped = om(mvc.perform(get("/api/items/mine").param("page", "0").param("size", "999")
                .session(session("seller"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(1, clamped.get("page").asInt());
        assertEquals(MINE_MAX_SIZE, clamped.get("size").asInt());
        assertEquals(Math.min(total, MINE_MAX_SIZE), clamped.get("items").size());

        mvc.perform(get("/api/items/mine")).andExpect(status().isUnauthorized());
    }

    @Test
    void mineOfUserWithoutItemsIsAnEmptyFirstPage() {
        var none = service.mine(uid("buyer2"), 1, 20);
        assertEquals(0, items(none).size());
        assertEquals(0L, ((Number) none.get("total")).longValue());
        assertEquals(1, n(none, "totalPages"));
        assertEquals(1, n(none, "page"));
    }

    /** “我的发布”改为分页不影响“发现物品”既有的分页契约：仍返回数组、每页 20 条。 */
    @Test
    void marketPaginationStaysArrayBased() throws Exception {
        var page1 = om(mvc.perform(get("/api/items").param("page", "1")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertTrue(page1.isArray(), "公开列表仍为数组");
        assertTrue(page1.size() <= 20);
        var page2 = om(mvc.perform(get("/api/items").param("page", "2")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertTrue(page2.isArray(), "公开列表第 2 页仍为数组");
    }

    // ---------------- #16 争议订单的买方确认 ----------------

    @Test
    void buyerCanConfirmDeliveredDisputedOrderAndDisputeCloses() {
        var item = onSale();
        String oid = s(buy(item), "id");
        act(seller(), oid, "accept");
        act(seller(), oid, "deliver");
        service.dispute(buyer(), oid, p("reason", "交付情况有分歧", "evidence", "现场照片"));
        assertEquals(1, n(service.order(buyer(), oid, false), "disputed"), "争议受理保留订单阶段");

        // 争议期间其他出口仍被拒绝：卖方不得代确认或再次登记，第三人不得操作。
        assertThrows(ApiError.class, () -> act(seller(), oid, "confirm"));
        assertThrows(ApiError.class, () -> act(admin(), oid, "confirm"));
        assertThrows(ApiError.class, () -> act(uid("buyer2"), oid, "confirm"));
        assertThrows(ApiError.class, () -> act(seller(), oid, "deliver"));
        assertThrows(ApiError.class, () -> act(seller(), oid, "cancel"));

        // 买方本人确认收到：完成订单、释放占用、争议结案。
        var done = act(buyer(), oid, "confirm");
        assertEquals("已完成", s(done, "stage"));
        assertEquals(0, n(service.order(buyer(), oid, false), "disputed"));
        var dispute = db.one("select status,outcome from disputes where order_id=#{p.o}", p("o", oid));
        assertEquals("已结案", s(dispute, "status"));
        assertEquals("买方确认完成", s(dispute, "outcome"));
        assertEquals("已成交", s(service.item(s(item, "id"), false), "status"));
        assertEquals(0, db.list("select * from reservations where item_id=#{p.i}", p("i", item.get("id"))).size());
        assertThrows(ApiError.class, () -> act(buyer(), oid, "confirm"), "重复确认仍被拒绝");
    }

    // ---------------- #17 重复评价 ----------------

    @Test
    void reviewIsRejectedForActiveOrderAndForDuplicateSubmission() throws Exception {
        var item = onSale();
        String oid = s(buy(item), "id");
        assertThrows(ApiError.class, () -> service.review(buyer(), oid, p("score", 5, "content", "提前评价")),
                "未完成订单不能评价");

        act(seller(), oid, "accept");
        act(seller(), oid, "deliver");
        act(buyer(), oid, "confirm");

        service.review(buyer(), oid, p("score", 5, "content", "收到，状态与描述一致"));
        ApiError duplicate = assertThrows(ApiError.class,
                () -> service.review(buyer(), oid, p("score", 1, "content", "重复评价")));
        assertEquals(409, duplicate.status);
        assertTrue(duplicate.getMessage().contains("已评价"), duplicate.getMessage());
        assertEquals(1, db.list("select id from reviews where order_id=#{p.o} and author_id=#{p.u}",
                p("o", oid, "u", buyer())).size(), "重复评价不产生第二条记录");

        // 另一方仍可各评价一次，重复提交同样拒绝。
        service.review(seller(), oid, p("score", 5, "content", "买方按约完成"));
        assertEquals(2, db.list("select id from reviews where order_id=#{p.o}", p("o", oid)).size());
        assertThrows(ApiError.class, () -> service.review(seller(), oid, p("score", 5, "content", "再次评价")));

        // 刷新详情时能读到本人评价：前端据此把入口改为“本人已评价”。
        var details = om(mvc.perform(get("/api/orders/" + oid + "/details").session(session("buyer")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertTrue(StreamSupport.stream(details.get("reviews").spliterator(), false)
                .anyMatch(r -> buyer().equals(r.get("author_id").asText())), "详情返回本人评价");

        // HTTP 层重复评价返回 409 与明确原因，而不是数据库约束导致的 500。
        mvc.perform(post("/api/orders/" + oid + "/reviews").session(session("buyer"))
                .header("X-CSRF-Token", "csrf-buyer").contentType(MediaType.APPLICATION_JSON)
                .content("{\"score\":5,\"content\":\"再次提交\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("本单已评价，不能重复评价。"));
    }

    static com.fasterxml.jackson.databind.JsonNode om(String json) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
    }
}
