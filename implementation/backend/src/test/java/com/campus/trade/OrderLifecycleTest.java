package com.campus.trade;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

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
import com.campus.trade.order.CreateOrderInput;
import com.campus.trade.order.OrderActionInput;
import com.campus.trade.order.OrderDetailsDto;
import com.campus.trade.order.OrderDto;
import com.campus.trade.order.OrderService;

/** 订单主链、确认权、取消与交付竞争条件的自测（TC-RB-10/11/12/13/22/27）。 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestActorConfig.class)
class OrderLifecycleTest {

    @Autowired
    private OrderService orderService;
    @Autowired
    private TestFixtures fixtures;

    private TestFixtures.Account seller;
    private TestFixtures.Account buyer;
    private TestFixtures.Account otherBuyer;
    private TestFixtures.Account admin;
    private String itemId;

    @BeforeEach
    void setUp() {
        fixtures.reset();
        TestActors.logout();
        seller = fixtures.createUser("seller01", "卖方S", 1, 0);
        buyer = fixtures.createUser("buyer01", "买方U1", 1, 0);
        otherBuyer = fixtures.createUser("buyer02", "买方U2", 1, 0);
        admin = fixtures.createAdmin("admin01");
        itemId = fixtures.createSellableItem(seller.id(), "合成物品I1", "100.00");
    }

    @AfterEach
    void tearDown() {
        TestActors.logout();
    }

    private static void assertApiError(ThrowingCallable call, HttpStatus expected) {
        org.assertj.core.api.Assertions.assertThatThrownBy(call)
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(expected));
    }

    private OrderDto createOrder(String key) {
        TestActors.login(buyer.id());
        return orderService.create(new CreateOrderInput(itemId, 1), key);
    }

    @Test
    @DisplayName("TC-RB-10：下单→接单→交付登记→买方确认，占用解除一次且重复确认不多计")
    void fullHappyPath() {
        OrderDto order = createOrder("key-order-0001");
        assertThat(order.stage()).isEqualTo("待卖方确认");
        assertThat(order.buyerId()).isEqualTo(buyer.publicRef());
        assertThat(order.sellerId()).isEqualTo(seller.publicRef());
        assertThat(order.price()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(order.itemVersion()).isEqualTo(1);
        assertThat(fixtures.itemStatus(itemId)).isEqualTo("交易中");
        assertThat(fixtures.count("reservations", "item_id = ?", itemId)).isEqualTo(1);

        // 卖方之外的账户不能接单
        TestActors.login(otherBuyer.id());
        assertApiError(() -> orderService.act(order.id(), "accept", new OrderActionInput(null, null),
                "key-accept-0001"), HttpStatus.FORBIDDEN);

        TestActors.login(seller.id());
        OrderDto accepted = orderService.act(order.id(), "accept", new OrderActionInput(null, null),
                "key-accept-0002");
        assertThat(accepted.stage()).isEqualTo("待交付");

        // 交付必须声明事实并说明原因
        assertApiError(() -> orderService.act(order.id(), "deliver", new OrderActionInput("已交接", false),
                "key-deliver-0001"), HttpStatus.BAD_REQUEST);
        OrderDto delivered = orderService.act(order.id(), "deliver",
                new OrderActionInput("已在宿舍楼下交接", true), "key-deliver-0002");
        assertThat(delivered.stage()).isEqualTo("待买方确认");
        assertThat(fixtures.count("deliveries", "order_id = ?", order.id())).isEqualTo(1);

        TestActors.login(buyer.id());
        OrderDto completed = orderService.act(order.id(), "confirm", new OrderActionInput(null, true),
                "key-confirm-0001");
        assertThat(completed.stage()).isEqualTo("已完成");
        assertThat(completed.completedAt()).isNotNull();
        assertThat(fixtures.itemStatus(itemId)).isEqualTo("已成交");
        assertThat(fixtures.count("reservations", "item_id = ?", itemId)).isZero();
        assertThat(fixtures.count("orders", "stage = '已完成'")).isEqualTo(1);

        OrderDto replay = orderService.act(order.id(), "confirm", new OrderActionInput(null, true),
                "key-confirm-0001");
        assertThat(replay.stage()).isEqualTo("已完成");
        assertThat(fixtures.count("orders", "stage = '已完成'")).isEqualTo(1);

        assertApiError(() -> orderService.act(order.id(), "confirm", new OrderActionInput(null, true),
                "key-confirm-0002"), HttpStatus.BAD_REQUEST);
        assertThat(fixtures.count("orders", "stage = '已完成'")).isEqualTo(1);

        OrderDetailsDto details = orderService.details(order.id());
        assertThat(details.delivery()).isNotNull();
        assertThat(details.delivery().note()).isEqualTo("已在宿舍楼下交接");
        assertThat(details.reviews()).isEmpty();
        assertThat(fixtures.auditCount(order.id())).isGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("TC-RB-13：卖方与管理员都不能代买方确认，只有本人买方可以完成")
    void onlyBuyerConfirms() {
        OrderDto order = createOrder("key-order-0002");
        TestActors.login(seller.id());
        orderService.act(order.id(), "accept", new OrderActionInput(null, null), "key-accept-0010");
        orderService.act(order.id(), "deliver", new OrderActionInput("已交付", true), "key-deliver-0010");

        assertApiError(() -> orderService.act(order.id(), "confirm", new OrderActionInput(null, true),
                "key-confirm-0010"), HttpStatus.FORBIDDEN);
        assertThat(fixtures.orderStage(order.id())).isEqualTo("待买方确认");

        TestActors.login(admin.id());
        assertApiError(() -> orderService.act(order.id(), "confirm", new OrderActionInput(null, true),
                "key-confirm-0011"), HttpStatus.FORBIDDEN);
        assertThat(fixtures.count("orders", "stage = '已完成'")).isZero();
        assertThat(fixtures.itemStatus(itemId)).isEqualTo("交易中");

        TestActors.login(buyer.id());
        orderService.act(order.id(), "confirm", new OrderActionInput(null, true), "key-confirm-0012");
        assertThat(fixtures.orderStage(order.id())).isEqualTo("已完成");
    }

    @Test
    @DisplayName("TC-RB-11：普通取消释放占用并重判可售；声明缺失时拒绝")
    void cancelRules() {
        OrderDto order = createOrder("key-order-0003");
        TestActors.login(seller.id());
        orderService.act(order.id(), "accept", new OrderActionInput(null, null), "key-accept-0020");

        assertApiError(() -> orderService.act(order.id(), "cancel", new OrderActionInput("临时有事", false),
                "key-cancel-0020"), HttpStatus.BAD_REQUEST);

        TestActors.login(buyer.id());
        OrderDto cancelled = orderService.act(order.id(), "cancel",
                new OrderActionInput("双方协商取消，尚未交付", true), "key-cancel-0021");
        assertThat(cancelled.stage()).isEqualTo("已取消");
        assertThat(cancelled.reason()).isEqualTo("双方协商取消，尚未交付");
        assertThat(fixtures.itemStatus(itemId)).isEqualTo("在售");
        assertThat(fixtures.count("reservations", "item_id = ?", itemId)).isZero();

        assertApiError(() -> orderService.act(order.id(), "cancel",
                new OrderActionInput("重复取消", true), "key-cancel-0022"), HttpStatus.BAD_REQUEST);
        assertThat(fixtures.count("orders", "stage = '已取消'")).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-RB-12：已登记交付或存在处理中争议时拒绝冲突动作")
    void guardsOnDeliveredOrDisputed() {
        OrderDto order = createOrder("key-order-0004");
        TestActors.login(seller.id());
        orderService.act(order.id(), "accept", new OrderActionInput(null, null), "key-accept-0030");
        orderService.act(order.id(), "deliver", new OrderActionInput("已交付", true), "key-deliver-0030");

        TestActors.login(buyer.id());
        assertApiError(() -> orderService.act(order.id(), "cancel",
                new OrderActionInput("想取消", true), "key-cancel-0030"), HttpStatus.BAD_REQUEST);
        assertThat(fixtures.orderStage(order.id())).isEqualTo("待买方确认");
        assertThat(fixtures.count("deliveries", "order_id = ?", order.id())).isEqualTo(1);

        // 处理中争议：暂停接单、拒单、交付、普通取消；买方本人确认仍允许
        fixtures.markDisputed(order.id());
        TestActors.login(seller.id());
        assertApiError(() -> orderService.act(order.id(), "cancel",
                new OrderActionInput("取消", true), "key-cancel-0031"), HttpStatus.BAD_REQUEST);
        TestActors.login(buyer.id());
        OrderDto done = orderService.act(order.id(), "confirm", new OrderActionInput(null, true),
                "key-confirm-0030");
        assertThat(done.stage()).isEqualTo("已完成");

        // 另一笔订单在受理争议后不能交付或拒单
        String item2 = fixtures.createSellableItem(seller.id(), "合成物品I2", "80.00");
        TestActors.login(otherBuyer.id());
        OrderDto second = orderService.create(new CreateOrderInput(item2, 1), "key-order-0005");
        TestActors.login(seller.id());
        orderService.act(second.id(), "accept", new OrderActionInput(null, null), "key-accept-0031");
        fixtures.markDisputed(second.id());
        assertApiError(() -> orderService.act(second.id(), "deliver",
                new OrderActionInput("已交付", true), "key-deliver-0031"), HttpStatus.BAD_REQUEST);
        assertThat(fixtures.orderStage(second.id())).isEqualTo("待交付");
    }

    @Test
    @DisplayName("TC-RB-27：回执不因后续账户受限失效；同键异内容与他人偷键均被拒绝")
    void replayAfterRestriction() {
        OrderDto order = createOrder("key-order-0006");
        fixtures.restrict(buyer.id());

        OrderDto replay = orderService.create(new CreateOrderInput(itemId, 1), "key-order-0006");
        assertThat(replay.id()).isEqualTo(order.id());
        assertThat(fixtures.count("orders", "item_id = ?", itemId)).isEqualTo(1);

        assertApiError(() -> orderService.create(new CreateOrderInput(itemId, 2), "key-order-0006"),
                HttpStatus.BAD_REQUEST);

        TestActors.login(otherBuyer.id());
        assertApiError(() -> orderService.create(new CreateOrderInput(itemId, 1), "key-order-0006"),
                HttpStatus.CONFLICT);
        assertThat(fixtures.count("orders", "item_id = ?", itemId)).isEqualTo(1);
    }

    @Test
    @DisplayName("下单前置条件：自买、版本不符、受限卖方均拒绝且不留占用")
    void createPreconditions() {
        TestActors.login(seller.id());
        assertApiError(() -> orderService.create(new CreateOrderInput(itemId, 1), "key-order-0100"),
                HttpStatus.BAD_REQUEST);

        TestActors.login(buyer.id());
        assertApiError(() -> orderService.create(new CreateOrderInput(itemId, 9), "key-order-0101"),
                HttpStatus.BAD_REQUEST);

        fixtures.restrict(seller.id());
        assertApiError(() -> orderService.create(new CreateOrderInput(itemId, 1), "key-order-0102"),
                HttpStatus.BAD_REQUEST);

        fixtures.restrict(seller.id());
        fixtures.setVerified(seller.id(), 0);
        assertApiError(() -> orderService.create(new CreateOrderInput(itemId, 1), "key-order-0103"),
                HttpStatus.BAD_REQUEST);

        assertThat(fixtures.count("orders", null)).isZero();
        assertThat(fixtures.count("reservations", null)).isZero();
        assertThat(fixtures.itemStatus(itemId)).isEqualTo("在售");
    }
}
