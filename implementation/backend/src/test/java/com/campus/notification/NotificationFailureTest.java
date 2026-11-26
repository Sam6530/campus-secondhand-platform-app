package com.campus.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.campus.support.TestActorConfig;
import com.campus.support.TestActors;
import com.campus.support.TestFixtures;
import com.campus.trade.order.CreateOrderInput;
import com.campus.trade.order.OrderDto;
import com.campus.trade.order.OrderService;

/**
 * TC-RB-25/26：区分“业务未提交”和“提交后投递失败”。
 * 故障通过临时重命名合成库中的表注入，只在自测环境使用，产品不暴露任何故障开关。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestActorConfig.class)
class NotificationFailureTest {

    @Autowired
    private OrderService orderService;
    @Autowired
    private NotificationController notificationController;
    @Autowired
    private TestFixtures fixtures;

    private TestFixtures.Account seller;
    private TestFixtures.Account buyer;
    private String itemId;

    @BeforeEach
    void setUp() {
        fixtures.reset();
        TestActors.logout();
        seller = fixtures.createUser("seller01", "卖方S", 1, 0);
        buyer = fixtures.createUser("buyer01", "买方U1", 1, 0);
        itemId = fixtures.createSellableItem(seller.id(), "合成物品I1", "100.00");
    }

    @AfterEach
    void tearDown() {
        TestActors.logout();
        restoreIfNeeded();
    }

    @Test
    @DisplayName("TC-RB-26：Outbox 写失败属于业务未提交，订单/占用/回执全部回滚")
    void outboxWriteFailureRollsBackWholeOrder() {
        fixtures.renameTable("notification_outbox", "notification_outbox_bak");
        try {
            TestActors.login(buyer.id());
            assertThatThrownBy(() -> orderService.create(new CreateOrderInput(itemId, 1), "key-fail-0001"))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            fixtures.renameTable("notification_outbox_bak", "notification_outbox");
        }

        assertThat(fixtures.count("orders", null)).isZero();
        assertThat(fixtures.count("reservations", null)).isZero();
        assertThat(fixtures.count("operation_receipts", null)).isZero();
        assertThat(fixtures.itemStatus(itemId)).isEqualTo("在售");
    }

    @Test
    @DisplayName("TC-RB-25：提交后投递失败不撤销交易，恢复后补投不重跑业务")
    void deliveryFailureKeepsBusinessAndRetries() {
        fixtures.renameTable("notifications", "notifications_bak");
        OrderDto order;
        try {
            TestActors.login(buyer.id());
            order = orderService.create(new CreateOrderInput(itemId, 1), "key-fail-0002");
        } finally {
            fixtures.renameTable("notifications_bak", "notifications");
        }

        String eventKey = "ORDER:" + order.id() + ":CREATED";
        assertThat(fixtures.orderStage(order.id())).isEqualTo("待卖方确认");
        assertThat(fixtures.itemStatus(itemId)).isEqualTo("交易中");
        assertThat(fixtures.count("reservations", "item_id = ?", itemId)).isEqualTo(1);
        assertThat(fixtures.outboxStatus(eventKey)).isEqualTo("FAILED");

        TestActors.login(seller.id());
        List<NotificationDto> notifications = notificationController.list();
        assertThat(notifications).hasSize(1);
        assertThat(notifications.get(0).content()).contains("新的订单");
        assertThat(fixtures.outboxStatus(eventKey)).isEqualTo("SENT");
        assertThat(fixtures.count("notifications", null)).isEqualTo(1);

        // 补投只补通知，不重新下单
        notificationController.list();
        assertThat(fixtures.count("orders", null)).isEqualTo(1);
        assertThat(fixtures.count("notifications", null)).isEqualTo(1);
    }

    private void restoreIfNeeded() {
        Integer backup = fixtures.count("information_schema.tables",
                "table_schema = DATABASE() AND table_name = 'notification_outbox_bak'");
        if (backup > 0) {
            fixtures.renameTable("notification_outbox_bak", "notification_outbox");
        }
        backup = fixtures.count("information_schema.tables",
                "table_schema = DATABASE() AND table_name = 'notifications_bak'");
        if (backup > 0) {
            fixtures.renameTable("notifications_bak", "notifications");
        }
    }
}
