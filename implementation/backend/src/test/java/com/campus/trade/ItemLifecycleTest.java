package com.campus.trade;

import static org.assertj.core.api.Assertions.assertThat;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import com.campus.shared.api.ApiException;
import com.campus.support.TestActorConfig;
import com.campus.support.TestActors;
import com.campus.support.TestFixtures;
import com.campus.trade.admin.ItemReviewService;
import com.campus.trade.item.ItemDto;
import com.campus.trade.item.ItemInput;
import com.campus.trade.item.ItemService;
import com.campus.trade.item.ItemUpdateInput;
import com.campus.trade.order.CreateOrderInput;
import com.campus.trade.order.OrderService;

/** 物品发布/修改/撤下/审核与公开检索的自测（TC-RB-04/05/06/07/22/24 的 B 范围）。 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestActorConfig.class)
class ItemLifecycleTest {

    @Autowired
    private ItemService itemService;
    @Autowired
    private ItemReviewService itemReviewService;
    @Autowired
    private OrderService orderService;
    @Autowired
    private TestFixtures fixtures;

    private TestFixtures.Account seller;
    private TestFixtures.Account admin;
    private String imageId;

    @BeforeEach
    void setUp() {
        fixtures.reset();
        TestActors.logout();
        seller = fixtures.createUser("seller01", "卖方S", 1, 0);
        admin = fixtures.createAdmin("admin01");
        imageId = fixtures.createAsset(seller.id(), "ITEM");
        TestActors.login(seller.id());
    }

    @AfterEach
    void tearDown() {
        TestActors.logout();
    }

    private ItemInput input(String title, String price) {
        return new ItemInput(title, "教材书籍", "九成新，无笔记", price, imageId);
    }

    private static void assertApiError(ThrowingCallable call, HttpStatus expected) {
        org.assertj.core.api.Assertions.assertThatThrownBy(call)
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(expected));
    }

    @Test
    @DisplayName("TC-RB-04：缺描述、零价/负价被拒绝且不产生待审半记录，合规发布产生待审版本")
    void rejectInvalidPublishWithoutHalfRecords() {
        assertApiError(() -> itemService.create(
                new ItemInput("标题", "教材书籍", "   ", "10.00", imageId), "key-item-0001"), HttpStatus.BAD_REQUEST);
        assertApiError(() -> itemService.create(input("标题", "0.00"), "key-item-0002"), HttpStatus.BAD_REQUEST);
        assertApiError(() -> itemService.create(input("标题", "-5.00"), "key-item-0002x"), HttpStatus.BAD_REQUEST);
        assertApiError(() -> itemService.create(
                new ItemInput("标题", "教材书籍", "描述", "10.00", null), "key-item-0003"), HttpStatus.BAD_REQUEST);
        assertThat(fixtures.count("items", null)).isZero();
        assertThat(fixtures.count("item_versions", null)).isZero();

        ItemDto created = itemService.create(input("高等数学教材", "25.50"), "key-item-0004");
        assertThat(created.status()).isEqualTo("待审");
        assertThat(created.version()).isEqualTo(1);
        assertThat(created.reason()).isNull();
        assertThat(fixtures.count("items", null)).isEqualTo(1);
        assertThat(fixtures.count("item_versions", null)).isEqualTo(1);
        assertThat(fixtures.auditCount(created.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-RB-07：同键同内容重放不新增物品/版本，同键异内容 400，新键正常发布")
    void idempotentPublish() {
        ItemDto first = itemService.create(input("考研英语真题", "30.00"), "key-idem-0001");
        ItemDto replay = itemService.create(input("考研英语真题", "30.00"), "key-idem-0001");
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(fixtures.count("items", null)).isEqualTo(1);
        assertThat(fixtures.count("item_versions", null)).isEqualTo(1);

        assertApiError(() -> itemService.create(input("考研英语真题", "31.00"), "key-idem-0001"),
                HttpStatus.BAD_REQUEST);
        assertThat(fixtures.count("items", null)).isEqualTo(1);

        ItemDto another = itemService.create(input("考研英语真题（第二本）", "31.00"), "key-idem-0002");
        assertThat(another.id()).isNotEqualTo(first.id());
        assertThat(fixtures.count("items", null)).isEqualTo(2);
    }

    @Test
    @DisplayName("TC-RB-05：旧版本审核不影响新版本，重复审核不追加事实")
    void reviewOnlyCurrentVersion() {
        ItemDto created = itemService.create(input("线性代数教材", "20.00"), "key-review-0001");

        TestActors.login(admin.id());
        ItemDto approved = itemReviewService.review(created.id(), 1, true, "材料齐全，可公开");
        assertThat(approved.status()).isEqualTo("在售");
        assertThat(approved.reason()).isEqualTo("材料齐全，可公开");

        TestActors.login(seller.id());
        ItemDto edited = itemService.update(created.id(),
                new ItemUpdateInput("线性代数教材", "教材书籍", "改价出售", "18.00", imageId, 1),
                "key-edit-0001");
        assertThat(edited.version()).isEqualTo(2);
        assertThat(edited.status()).isEqualTo("待审");

        TestActors.login(admin.id());
        assertApiError(() -> itemReviewService.review(created.id(), 1, true, "尝试审核旧版本"),
                HttpStatus.BAD_REQUEST);
        ItemDto second = itemReviewService.review(created.id(), 2, true, "新版本通过");
        assertThat(second.version()).isEqualTo(2);
        assertThat(second.status()).isEqualTo("在售");
        assertApiError(() -> itemReviewService.review(created.id(), 2, true, "重复审核"),
                HttpStatus.BAD_REQUEST);
        assertThat(fixtures.count("item_versions", "item_id = ?", created.id())).isEqualTo(2);
        assertThat(fixtures.auditCount(created.id())).isGreaterThanOrEqualTo(4);
        var audits = fixtures.auditRows(created.id());
        assertThat(audits).anySatisfy(row -> {
            assertThat(row.get("action")).isEqualTo("ITEM_PUBLISH");
            assertThat(row.get("actor_id")).isEqualTo(seller.id());
            assertThat(row.get("object_type")).isEqualTo("ITEM");
        });
        assertThat(audits).anySatisfy(row -> {
            assertThat(row.get("action")).isEqualTo("ITEM_REVIEW");
            assertThat(row.get("actor_id")).isEqualTo(admin.id());
            assertThat(row.get("reason")).isEqualTo("材料齐全，可公开");
            assertThat(row.get("to_state")).isEqualTo("在售");
        });
    }

    @Test
    @DisplayName("TC-RB-06：撤下后公开列表不可见、重复撤下无额外效果")
    void withdrawRules() {
        ItemDto item = itemService.create(input("宿舍台灯", "15.00"), "key-withdraw-0001");
        TestActors.login(admin.id());
        itemReviewService.review(item.id(), 1, true, "通过");
        TestActors.login(seller.id());

        itemService.withdraw(item.id());
        assertThat(fixtures.itemStatus(item.id())).isEqualTo("已撤下");
        assertThat(itemService.search(null, null, 1)).isEmpty();
        int auditsAfterFirst = fixtures.auditCount(item.id());
        itemService.withdraw(item.id());
        assertThat(fixtures.auditCount(item.id())).isEqualTo(auditsAfterFirst);

        TestActors.login(admin.id());
        assertApiError(() -> itemService.withdraw(item.id()), HttpStatus.FORBIDDEN);
        assertThat(fixtures.itemStatus(item.id())).isEqualTo("已撤下");
    }

    @Test
    @DisplayName("TC-RB-24：公开列表脱敏联系方式，本人列表保留原文")
    void publicViewMasksContact() {
        ItemDto item = itemService.create(input("高数教材 联系13800138000", "12.00"), "key-mask-0001");
        TestActors.login(admin.id());
        itemReviewService.review(item.id(), 1, true, "通过");

        TestActors.logout();
        var publicItems = itemService.search("高数", null, 1);
        assertThat(publicItems).hasSize(1);
        assertThat(publicItems.get(0).title()).isEqualTo("高数教材 联系[联系方式已隐藏]");

        TestActors.login(seller.id());
        assertThat(itemService.listMine().get(0).title()).isEqualTo("高数教材 联系13800138000");
    }

    @Test
    @DisplayName("公开检索只返回可交易物品：隐藏、受限卖方、未审不出现")
    void publicSearchRespectsVisibility() {
        String visible = fixtures.createSellableItem(seller.id(), "在售物品I1", "50.00");
        String hidden = fixtures.createSellableItem(seller.id(), "隐藏物品I2", "60.00");
        fixtures.hideItem(hidden);
        TestFixtures.Account restrictedSeller = fixtures.createUser("seller02", "受限卖方", 1, 1);
        fixtures.createSellableItem(restrictedSeller.id(), "受限物品I3", "70.00");

        TestActors.logout();
        var result = itemService.search(null, null, 1);
        assertThat(result).extracting(p -> p.id()).containsExactly(visible);
        assertThat(itemService.search(null, "运动户外", 1)).isEmpty();
        assertApiError(() -> itemService.search(null, null, 0), HttpStatus.BAD_REQUEST);
        assertApiError(() -> itemService.search(null, null, Integer.MAX_VALUE), HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("TC-RB-05：有效订单占用期间禁止改标的与撤下")
    void editAndWithdrawBlockedByActiveOrder() {
        ItemDto item = itemService.create(input("有机化学教材", "40.00"), "key-lock-0001");
        TestActors.login(admin.id());
        itemReviewService.review(item.id(), 1, true, "通过");

        TestFixtures.Account buyer = fixtures.createUser("buyer01", "买方U1", 1, 0);
        TestActors.login(buyer.id());
        orderService.create(new CreateOrderInput(item.id(), 1), "key-lock-order1");

        TestActors.login(seller.id());
        assertApiError(() -> itemService.update(item.id(),
                new ItemUpdateInput("有机化学教材", "教材书籍", "改价", "35.00", imageId, 1),
                "key-lock-0002"), HttpStatus.BAD_REQUEST);
        assertApiError(() -> itemService.withdraw(item.id()), HttpStatus.BAD_REQUEST);
        assertThat(fixtures.itemStatus(item.id())).isEqualTo("交易中");
    }

    @Test
    @DisplayName("TC-RB-06：撤下后显式重新提交进入待审（版本递增，不自动复售）")
    void republishAfterWithdraw() {
        ItemDto item = itemService.create(input("宿舍收纳箱", "10.00"), "key-repub-0001");
        TestActors.login(admin.id());
        itemReviewService.review(item.id(), 1, true, "通过");
        TestActors.login(seller.id());
        itemService.withdraw(item.id());

        ItemDto republished = itemService.update(item.id(),
                new ItemUpdateInput("宿舍收纳箱", "宿舍生活", "重新出售", "12.00", imageId, 1),
                "key-repub-0002");
        assertThat(republished.version()).isEqualTo(2);
        assertThat(republished.status()).isEqualTo("待审");
        assertThat(itemService.search(null, null, 1)).isEmpty();
    }

    @Test
    @DisplayName("管理员不能审核本人发布的物品（利益冲突）")
    void adminCannotReviewOwnItem() {
        String adminImage = fixtures.createAsset(admin.id(), "ITEM");
        TestActors.login(admin.id());
        ItemDto own = itemService.create(
                new ItemInput("管理员发布的教材", "教材书籍", "合成描述", "10.00", adminImage),
                "key-selfreview-0001");
        assertApiError(() -> itemReviewService.review(own.id(), 1, true, "自己审自己"), HttpStatus.FORBIDDEN);
        assertThat(fixtures.itemStatus(own.id())).isEqualTo("待审");
    }
}

