package com.campus.trade;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.campus.shared.api.ApiException;
import com.campus.support.TestActorConfig;
import com.campus.support.TestActors;
import com.campus.support.TestFixtures;
import com.campus.trade.order.CreateOrderInput;
import com.campus.trade.order.OrderActionInput;
import com.campus.trade.order.OrderDto;
import com.campus.trade.order.OrderService;

/**
 * TC-RB-08：两个真实数据库连接在屏障后同时下单，至多一个有效订单/占用；
 * 同意图并发只成立一次。自测不使用 sleep 代替并发。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestActorConfig.class)
class OrderConcurrencyTest {

    @Autowired
    private OrderService orderService;
    @Autowired
    private TestFixtures fixtures;

    private TestFixtures.Account seller;
    private TestFixtures.Account buyerA;
    private TestFixtures.Account buyerB;
    private String itemId;

    @BeforeEach
    void setUp() {
        fixtures.reset();
        TestActors.logout();
        seller = fixtures.createUser("seller01", "卖方S", 1, 0);
        buyerA = fixtures.createUser("buyer01", "买方U1", 1, 0);
        buyerB = fixtures.createUser("buyer02", "买方U2", 1, 0);
        itemId = fixtures.createSellableItem(seller.id(), "合成物品I1", "100.00");
    }

    @AfterEach
    void tearDown() {
        TestActors.logout();
    }

    @Test
    @DisplayName("两名买方并发下单：至多一笔订单与一个占用，失败方 409 且无残留")
    void twoBuyersRace() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<String> results = runConcurrently(
                attempt(buyerA.id(), "key-race-0001", barrier),
                attempt(buyerB.id(), "key-race-0002", barrier));

        assertThat(results).filteredOn(r -> r.startsWith("OK:")).hasSize(1);
        assertThat(results).filteredOn(r -> "ERR:409".equals(r)).hasSize(1);
        assertThat(fixtures.count("orders", "item_id = ?", itemId)).isEqualTo(1);
        assertThat(fixtures.count("reservations", "item_id = ?", itemId)).isEqualTo(1);
        assertThat(fixtures.itemStatus(itemId)).isEqualTo("交易中");
    }

    @Test
    @DisplayName("同一买方同键同内容并发重试：只成立一笔订单并都返回同一结果")
    void sameIntentRace() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<String> results = runConcurrently(
                attempt(buyerA.id(), "key-race-0003", barrier),
                attempt(buyerA.id(), "key-race-0003", barrier));

        assertThat(results).allSatisfy(r -> assertThat(r).startsWith("OK:"));
        assertThat(results.get(0)).isEqualTo(results.get(1));
        assertThat(fixtures.count("orders", "item_id = ?", itemId)).isEqualTo(1);
        assertThat(fixtures.count("reservations", "item_id = ?", itemId)).isEqualTo(1);
        assertThat(fixtures.count("operation_receipts", "action = 'ORDER_CREATE'")).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-RB-11：取消与交付登记并发，只成立一个相容结果且状态/占用不分裂")
    void cancelVsDeliverRace() throws Exception {
        TestActors.login(buyerA.id());
        OrderDto order = orderService.create(new CreateOrderInput(itemId, 1), "key-race-0200");
        TestActors.login(seller.id());
        orderService.act(order.id(), "accept", new OrderActionInput(null, null), "key-race-0201");

        CyclicBarrier barrier = new CyclicBarrier(2);
        List<String> results = runConcurrently(
                actAttempt(buyerA.id(), order.id(), "cancel", "双方尚未交接", true, "key-race-0202", barrier),
                actAttempt(seller.id(), order.id(), "deliver", "已在宿舍楼下交接", true, "key-race-0203",
                        barrier));

        assertThat(results).filteredOn(r -> r.startsWith("OK:")).hasSize(1);
        assertThat(results).anySatisfy(r -> assertThat(r).isEqualTo("ERR:400"));

        String stage = fixtures.orderStage(order.id());
        assertThat(stage).isIn("已取消", "待买方确认");
        if ("已取消".equals(stage)) {
            assertThat(fixtures.count("deliveries", "order_id = ?", order.id())).isZero();
            assertThat(fixtures.count("reservations", "item_id = ?", itemId)).isZero();
            assertThat(fixtures.itemStatus(itemId)).isEqualTo("在售");
        } else {
            assertThat(fixtures.count("deliveries", "order_id = ?", order.id())).isEqualTo(1);
            assertThat(fixtures.count("reservations", "item_id = ?", itemId)).isEqualTo(1);
            assertThat(fixtures.itemStatus(itemId)).isEqualTo("交易中");
        }
    }

    private Callable<String> actAttempt(String userId, String orderId, String action, String reason,
                                        boolean declared, String key, CyclicBarrier barrier) {
        return () -> {
            TestActors.login(userId);
            try {
                barrier.await(10, TimeUnit.SECONDS);
                orderService.act(orderId, action, new OrderActionInput(reason, declared), key);
                return "OK:" + action;
            } catch (ApiException ex) {
                return "ERR:" + ex.getStatus().value();
            } finally {
                TestActors.logout();
            }
        };
    }

    private Callable<String> attempt(String userId, String key, CyclicBarrier barrier) {
        return () -> {
            TestActors.login(userId);
            try {
                barrier.await(10, TimeUnit.SECONDS);
                OrderDto order = orderService.create(new CreateOrderInput(itemId, 1), key);
                return "OK:" + order.id();
            } catch (ApiException ex) {
                return "ERR:" + ex.getStatus().value();
            } finally {
                TestActors.logout();
            }
        };
    }

    private List<String> runConcurrently(Callable<String> first, Callable<String> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> a = pool.submit(first);
            Future<String> b = pool.submit(second);
            return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }
}
