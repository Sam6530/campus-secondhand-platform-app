package com.campus.notification;

import static org.assertj.core.api.Assertions.assertThat;

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
 * F-05 回归：投递达到上限后转 DEAD —— 保留记录、停止自动重试、不删除、不伪装成已发送；
 * 业务结果仍然成立（TC-RB-25/26 的失败边界）。
 */
@SpringBootTest(properties = "campus.notification.max-attempts=1")
@ActiveProfiles("test")
@Import(TestActorConfig.class)
class OutboxDeadLetterTest {

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
    @DisplayName("投递达上限转 DEAD：记录保留、不再重试、业务不撤销")
    void deadLetterAfterMaxAttempts() {
        fixtures.renameTable("notifications", "notifications_bak");
        OrderDto order;
        try {
            TestActors.login(buyer.id());
            order = orderService.create(new CreateOrderInput(itemId, 1), "key-dead-0001");
        } finally {
            fixtures.renameTable("notifications_bak", "notifications");
        }

        String eventKey = "ORDER:" + order.id() + ":CREATED";
        assertThat(fixtures.outboxStatus(eventKey)).isEqualTo("DEAD");
        assertThat(fixtures.count("orders", null)).isEqualTo(1);
        assertThat(fixtures.itemStatus(itemId)).isEqualTo("交易中");

        TestActors.login(seller.id());
        List<NotificationDto> notifications = notificationController.list();
        assertThat(notifications).isEmpty();
        assertThat(fixtures.outboxStatus(eventKey)).isEqualTo("DEAD");
        assertThat(fixtures.count("notifications", null)).isZero();
    }

    private void restoreIfNeeded() {
        Integer backup = fixtures.count("information_schema.tables",
                "table_schema = DATABASE() AND table_name = 'notifications_bak'");
        if (backup > 0) {
            fixtures.renameTable("notifications_bak", "notifications");
        }
    }
}
