package com.campus.trade.order;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.campus.notification.OutboxDispatcher;
import com.campus.notification.OutboxService;
import com.campus.shared.account.AccountMapper;
import com.campus.shared.account.AccountRow;
import com.campus.shared.account.ActorService;
import com.campus.shared.api.ApiException;
import com.campus.shared.audit.AuditService;
import com.campus.shared.db.Utc;
import com.campus.shared.idempotency.Idempotency;
import com.campus.shared.idempotency.OperationReceiptRow;
import com.campus.trade.item.ItemMapper;
import com.campus.trade.item.ItemRow;
import com.campus.trade.item.ItemStatuses;
import com.campus.trade.item.ItemVersionRow;

/**
 * 订单快照与唯一占用、接拒单、交付登记、买方确认、普通取消（FR09—FR15，BR06—BR09）。
 * 锁序：账户 → 物品 → 订单；锁后重读再判断，以先成立的合法操作为准。
 */
@Service
public class OrderService {

    private static final Set<String> ACTIONS = Set.of("accept", "reject", "deliver", "confirm", "cancel", "remind");

    private final OrderMapper orderMapper;
    private final ReservationMapper reservationMapper;
    private final DeliveryMapper deliveryMapper;
    private final ItemMapper itemMapper;
    private final AccountMapper accountMapper;
    private final ActorService actorService;
    private final AuditService auditService;
    private final Idempotency idempotency;
    private final OutboxService outboxService;
    private final OutboxDispatcher outboxDispatcher;
    private final TransactionTemplate transactionTemplate;

    public OrderService(OrderMapper orderMapper, ReservationMapper reservationMapper, DeliveryMapper deliveryMapper,
                        ItemMapper itemMapper, AccountMapper accountMapper, ActorService actorService,
                        AuditService auditService, Idempotency idempotency, OutboxService outboxService,
                        OutboxDispatcher outboxDispatcher, TransactionTemplate transactionTemplate) {
        this.orderMapper = orderMapper;
        this.reservationMapper = reservationMapper;
        this.deliveryMapper = deliveryMapper;
        this.itemMapper = itemMapper;
        this.accountMapper = accountMapper;
        this.actorService = actorService;
        this.auditService = auditService;
        this.idempotency = idempotency;
        this.outboxService = outboxService;
        this.outboxDispatcher = outboxDispatcher;
        this.transactionTemplate = transactionTemplate;
    }

    /** 下单：同次意图先回原结果；新意图检查资格与可交易条件，订单/快照/占用/回执/通知原子成立。 */
    public OrderDto create(CreateOrderInput input, String rawKey) {
        AccountRow actor = actorService.requireAccount();
        if (input == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        if (input.itemId() == null || input.itemId().isBlank()) {
            throw ApiException.badRequest("下单必须指定物品");
        }
        if (input.version() == null || input.version() < 1) {
            throw ApiException.badRequest("下单必须携带看到的物品版本");
        }
        String key = idempotency.requireKey(rawKey);
        String hash = idempotency.hash("ORDER_CREATE", input.itemId().trim(), String.valueOf(input.version()));

        OperationReceiptRow receipt = idempotency.find(actor.id, "ORDER_CREATE", key);
        if (receipt != null) {
            idempotency.replayResultRef(receipt, hash);
            return loadOrder(receipt.resultRef);
        }

        AccountRow buyer = actorService.requireNewBusinessAccount();
        List<String> events = new ArrayList<>();
        String orderId;
        try {
            orderId = transactionTemplate.execute(status -> doCreate(buyer, input.itemId().trim(),
                    input.version(), key, hash, events));
        } catch (DuplicateKeyException ex) {
            OperationReceiptRow raced = idempotency.find(actor.id, "ORDER_CREATE", key);
            if (raced != null) {
                idempotency.replayResultRef(raced, hash);
                return loadOrder(raced.resultRef);
            }
            throw ApiException.conflict("该物品已被其他订单占用，本次下单未成立");
        }
        outboxDispatcher.dispatch(events);
        return loadOrder(orderId);
    }

    /** 订单阶段操作：先核当事人与同意图回执，再核阶段、争议标记与事实声明。 */
    public OrderDto act(String orderId, String action, OrderActionInput input, String rawKey) {
        AccountRow actor = actorService.requireAccount();
        String normalized = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
        if (!ACTIONS.contains(normalized)) {
            throw ApiException.badRequest("不支持的订单操作：" + action);
        }
        String key = idempotency.requireKey(rawKey);
        String reason = input == null || input.reason() == null ? "" : input.reason().trim();
        boolean declared = input != null && Boolean.TRUE.equals(input.declared());
        String actionCode = "ORDER_" + normalized.toUpperCase(Locale.ROOT);
        String hash = idempotency.hash(actionCode, orderId, String.valueOf(declared), reason);

        OperationReceiptRow receipt = idempotency.find(actor.id, actionCode, key);
        if (receipt != null) {
            idempotency.replayResultRef(receipt, hash);
            return loadOrder(receipt.resultRef);
        }

        List<String> events = new ArrayList<>();
        try {
            transactionTemplate.executeWithoutResult(status ->
                    doAct(actor, orderId, normalized, actionCode, declared, reason, key, hash, events));
        } catch (DuplicateKeyException ex) {
            OperationReceiptRow raced = idempotency.find(actor.id, actionCode, key);
            if (raced != null) {
                idempotency.replayResultRef(raced, hash);
                return loadOrder(raced.resultRef);
            }
            throw ApiException.conflict("同一订单操作正在处理中，请稍后查询结果");
        }
        outboxDispatcher.dispatch(events);
        return loadOrder(orderId);
    }

    public OrderDetailsDto details(String orderId) {
        AccountRow actor = actorService.requireAccount();
        OrderRow order = orderMapper.findById(orderId);
        if (order == null) {
            throw ApiException.notFound("订单不存在");
        }
        if (!order.buyerId.equals(actor.id) && !order.sellerId.equals(actor.id)) {
            throw ApiException.forbidden("只能查看本人参与的订单详情");
        }
        List<DeliveryRow> deliveries = deliveryMapper.listByOrder(orderId);
        DeliveryDto delivery = deliveries.isEmpty() ? null
                : toDeliveryDto(order, deliveries.get(deliveries.size() - 1));
        return new OrderDetailsDto(toDto(order), delivery, List.of());
    }

    public List<OrderDto> list() {
        AccountRow actor = actorService.requireAccount();
        return orderMapper.listForUser(actor.id).stream().map(this::toDto).toList();
    }

    // ---------------------------------------------------------------- 下单成立

    private String doCreate(AccountRow buyer, String itemId, int version, String key, String hash,
                            List<String> events) {
        ItemRow item = itemMapper.lockById(itemId);
        if (item == null) {
            throw ApiException.notFound("物品不存在");
        }
        // 锁后重读回执：并发同键请求在等锁期间首个已成立，这里直接返回原订单，不再触发占用冲突
        OperationReceiptRow replayed = idempotency.find(buyer.id, "ORDER_CREATE", key);
        if (replayed != null) {
            idempotency.replayResultRef(replayed, hash);
            return replayed.resultRef;
        }
        if (item.currentVersion != version) {
            throw ApiException.badRequest("物品版本已变化，请刷新后重新下单");
        }
        ItemVersionRow itemVersion = itemMapper.findVersion(itemId, version);
        if (itemVersion == null) {
            throw ApiException.badRequest("物品版本不存在，请刷新后重试");
        }
        if (!ItemStatuses.REVIEW_PASSED.equals(itemVersion.reviewStatus)) {
            throw ApiException.badRequest("物品当前版本未通过审核，不能下单");
        }
        if (buyer.id.equals(item.sellerId)) {
            throw ApiException.badRequest("不能购买本人发布的物品");
        }
        if (reservationMapper.countByItem(itemId) > 0) {
            throw ApiException.conflict("该物品已被其他订单占用");
        }
        if (!ItemStatuses.ON_SALE.equals(item.status)) {
            throw ApiException.badRequest("物品当前不可交易");
        }
        if (item.hidden == 1) {
            throw ApiException.badRequest("物品已隐藏，不能下单");
        }
        AccountRow seller = accountMapper.findById(item.sellerId);
        if (seller == null) {
            throw ApiException.badRequest("卖方账户不可用");
        }
        if (!seller.isVerified()) {
            throw ApiException.badRequest("卖方账户未通过校园认证，不能建立新订单");
        }
        if (seller.isRestricted()) {
            throw ApiException.badRequest("卖方账户当前受限，不能建立新订单");
        }

        LocalDateTime now = Utc.now();
        OrderRow order = new OrderRow();
        order.id = UUID.randomUUID().toString();
        order.itemId = itemId;
        order.itemVersion = version;
        order.buyerId = buyer.id;
        order.sellerId = seller.id;
        order.buyerPublicRef = buyer.publicRef;
        order.sellerPublicRef = seller.publicRef;
        order.stage = OrderStages.WAIT_SELLER;
        order.disputed = 0;
        order.reminded = 0;
        order.title = itemVersion.title;
        order.description = itemVersion.description;
        order.price = itemVersion.price;
        order.imageId = itemVersion.imageId;
        order.buyerName = buyer.nickname;
        order.sellerName = seller.nickname;
        order.createdAt = now;
        orderMapper.insert(order);
        reservationMapper.insert(itemId, order.id, now);

        String fromItemStatus = item.status;
        item.status = ItemStatuses.IN_TRADE;
        item.updatedAt = now;
        itemMapper.updateStatus(item);

        auditService.record(buyer.id, "ORDER", order.id, "ORDER_CREATE", null, OrderStages.WAIT_SELLER,
                null, "SUCCESS");
        auditService.record(buyer.id, "ITEM", itemId, "ITEM_RESERVED", fromItemStatus,
                ItemStatuses.IN_TRADE, null, "SUCCESS");

        String eventKey = "ORDER:" + order.id + ":CREATED";
        outboxService.append(eventKey, seller.id, "ORDER_CREATED", "您有一笔新的订单待确认：" + order.title);
        events.add(eventKey);
        idempotency.record(buyer.id, "ORDER_CREATE", key, hash, order.id);
        return order.id;
    }

    // ---------------------------------------------------------------- 阶段操作

    private void doAct(AccountRow actor, String orderId, String action, String actionCode, boolean declared,
                       String reason, String key, String hash, List<String> events) {
        OrderRow probe = orderMapper.findById(orderId);
        if (probe == null) {
            throw ApiException.notFound("订单不存在");
        }
        if (!probe.buyerId.equals(actor.id) && !probe.sellerId.equals(actor.id)) {
            throw ApiException.forbidden("无权操作该订单");
        }
        ItemRow item = itemMapper.lockById(probe.itemId);
        OrderRow order = orderMapper.lockById(orderId);
        if (order == null) {
            throw ApiException.notFound("订单不存在");
        }

        switch (action) {
            case "accept" -> accept(actor, order, events);
            case "reject" -> reject(actor, order, item, reason, events);
            case "deliver" -> deliver(actor, order, declared, reason, events);
            case "confirm" -> confirm(actor, order, item, declared, events);
            case "cancel" -> cancel(actor, order, item, declared, reason, events);
            case "remind" -> remind(actor, order, events);
            default -> throw ApiException.badRequest("不支持的订单操作：" + action);
        }
        orderMapper.updateStage(order);
        if (order.reminded == 1) {
            orderMapper.markReminded(order.id);
        }
        idempotency.record(actor.id, actionCode, key, hash, order.id);
    }

    private void accept(AccountRow actor, OrderRow order, List<String> events) {
        requireSeller(actor, order);
        requireStage(order, OrderStages.WAIT_SELLER);
        requireNoDispute(order);
        String from = order.stage;
        order.stage = OrderStages.WAIT_DELIVERY;
        auditService.record(actor.id, "ORDER", order.id, "ORDER_ACCEPT", from, order.stage, null, "SUCCESS");
        String eventKey = "ORDER:" + order.id + ":ACCEPTED";
        outboxService.append(eventKey, order.buyerId, "ORDER_ACCEPTED", "卖方已接受您的订单：" + order.title);
        events.add(eventKey);
    }

    private void reject(AccountRow actor, OrderRow order, ItemRow item, String rawReason, List<String> events) {
        requireSeller(actor, order);
        requireStage(order, OrderStages.WAIT_SELLER);
        requireNoDispute(order);
        String note = requireReason(rawReason);
        String from = order.stage;
        order.stage = OrderStages.REJECTED;
        order.reason = note;
        releaseReservation(actor, order, item, false);
        auditService.record(actor.id, "ORDER", order.id, "ORDER_REJECT", from, order.stage, note, "SUCCESS");
        String eventKey = "ORDER:" + order.id + ":REJECTED";
        outboxService.append(eventKey, order.buyerId, "ORDER_REJECTED", "卖方已拒绝您的订单：" + order.title);
        events.add(eventKey);
    }

    private void deliver(AccountRow actor, OrderRow order, boolean declared, String rawReason,
                         List<String> events) {
        requireSeller(actor, order);
        requireStage(order, OrderStages.WAIT_DELIVERY);
        requireNoDispute(order);
        requireDeclared(declared, "登记交付前必须声明已完成线下实际交付");
        String note = requireReason(rawReason);
        DeliveryRow delivery = new DeliveryRow();
        delivery.id = UUID.randomUUID().toString();
        delivery.orderId = order.id;
        delivery.actorId = actor.id;
        delivery.note = note;
        delivery.createdAt = Utc.now();
        deliveryMapper.insert(delivery);
        String from = order.stage;
        order.stage = OrderStages.WAIT_BUYER;
        auditService.record(actor.id, "ORDER", order.id, "ORDER_DELIVER", from, order.stage, note, "SUCCESS");
        String eventKey = "ORDER:" + order.id + ":DELIVERED";
        outboxService.append(eventKey, order.buyerId, "ORDER_DELIVERED", "卖方已登记交付，请确认收到：" + order.title);
        events.add(eventKey);
    }

    private void confirm(AccountRow actor, OrderRow order, ItemRow item, boolean declared, List<String> events) {
        requireBuyer(actor, order);
        requireStage(order, OrderStages.WAIT_BUYER);
        requireDeclared(declared, "确认收到前必须声明本人已收到物品");
        if (deliveryMapper.listByOrder(order.id).isEmpty()) {
            throw ApiException.badRequest("卖方尚未登记交付，不能确认收到");
        }
        String from = order.stage;
        order.stage = OrderStages.COMPLETED;
        order.completedAt = Utc.now();
        releaseReservation(actor, order, item, true);
        auditService.record(actor.id, "ORDER", order.id, "ORDER_CONFIRM", from, order.stage, null, "SUCCESS");
        String eventKey = "ORDER:" + order.id + ":COMPLETED";
        outboxService.append(eventKey, order.sellerId, "ORDER_COMPLETED", "买方已确认收到，订单完成：" + order.title);
        events.add(eventKey);
    }

    private void cancel(AccountRow actor, OrderRow order, ItemRow item, boolean declared, String rawReason,
                        List<String> events) {
        requireStage(order, OrderStages.WAIT_SELLER, OrderStages.WAIT_DELIVERY);
        requireNoDispute(order);
        requireDeclared(declared, "普通取消前必须声明尚未实际交付");
        if (!deliveryMapper.listByOrder(order.id).isEmpty()) {
            throw ApiException.badRequest("卖方已登记交付，不能普通取消；已实际交付应补登记或通过争议处理");
        }
        String note = requireReason(rawReason);
        String from = order.stage;
        order.stage = OrderStages.CANCELLED;
        order.reason = note;
        releaseReservation(actor, order, item, false);
        auditService.record(actor.id, "ORDER", order.id, "ORDER_CANCEL", from, order.stage, note, "SUCCESS");
        String peerId = actor.id.equals(order.buyerId) ? order.sellerId : order.buyerId;
        String eventKey = "ORDER:" + order.id + ":CANCELLED";
        outboxService.append(eventKey, peerId, "ORDER_CANCELLED", "订单已取消：" + order.title);
        events.add(eventKey);
    }

    private void remind(AccountRow actor, OrderRow order, List<String> events) {
        requireSeller(actor, order);
        requireStage(order, OrderStages.WAIT_BUYER);
        if (order.reminded == 1) {
            throw ApiException.badRequest("本阶段已提醒过，不能重复提醒");
        }
        order.reminded = 1;
        auditService.record(actor.id, "ORDER", order.id, "ORDER_REMIND", order.stage, order.stage, null,
                "SUCCESS");
        String eventKey = "ORDER:" + order.id + ":REMIND";
        outboxService.append(eventKey, order.buyerId, "ORDER_REMINDED", "卖方提醒您确认收货：" + order.title);
        events.add(eventKey);
    }

    // ---------------------------------------------------------------- 共用判断与投影

    /** 释放占用并把物品按全部独立条件重判：成交→已成交；终止→在售（隐藏/受限仍阻断公开）。 */
    private void releaseReservation(AccountRow actor, OrderRow order, ItemRow item, boolean sold) {
        reservationMapper.deleteByOrder(order.id);
        if (item == null) {
            return;
        }
        if (sold) {
            String from = item.status;
            item.status = ItemStatuses.SOLD;
            item.updatedAt = Utc.now();
            itemMapper.updateStatus(item);
            auditService.record(actor.id, "ITEM", item.id, "ITEM_SOLD", from, ItemStatuses.SOLD, null,
                    "SUCCESS");
            return;
        }
        if (!ItemStatuses.IN_TRADE.equals(item.status)) {
            return;
        }
        ItemVersionRow current = itemMapper.findCurrentVersion(item.id);
        if (current == null || !ItemStatuses.REVIEW_PASSED.equals(current.reviewStatus)) {
            return;
        }
        String from = item.status;
        item.status = ItemStatuses.ON_SALE;
        item.updatedAt = Utc.now();
        itemMapper.updateStatus(item);
        auditService.record(actor.id, "ITEM", item.id, "ITEM_RELEASED", from, ItemStatuses.ON_SALE, null,
                "SUCCESS");
    }

    private void requireSeller(AccountRow actor, OrderRow order) {
        if (!order.sellerId.equals(actor.id)) {
            throw ApiException.forbidden("只有该订单的卖方可以执行该操作");
        }
    }

    private void requireBuyer(AccountRow actor, OrderRow order) {
        if (!order.buyerId.equals(actor.id)) {
            throw ApiException.forbidden("只有该订单的买方本人可以确认收到，管理员和卖方不能代确认");
        }
    }

    private void requireStage(OrderRow order, String... stages) {
        if (!List.of(stages).contains(order.stage)) {
            throw ApiException.badRequest("订单当前阶段为「" + order.stage + "」，不能执行该操作");
        }
    }

    private void requireNoDispute(OrderRow order) {
        if (order.disputed == 1) {
            throw ApiException.badRequest("订单存在处理中的履约争议，暂停接单、拒单、交付和普通取消");
        }
    }

    private String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("请填写原因说明");
        }
        if (reason.length() > 1000) {
            throw ApiException.badRequest("原因说明不能超过 1000 字");
        }
        return reason;
    }

    private void requireDeclared(boolean declared, String message) {
        if (!declared) {
            throw ApiException.badRequest(message);
        }
    }

    private OrderDto loadOrder(String orderId) {
        OrderRow order = orderMapper.findById(orderId);
        if (order == null) {
            throw ApiException.notFound("订单不存在");
        }
        return toDto(order);
    }

    private OrderDto toDto(OrderRow order) {
        return new OrderDto(order.id, order.itemId, order.buyerPublicRef, order.sellerPublicRef, order.title,
                order.description, order.price, order.imageId, order.buyerName, order.sellerName,
                order.itemVersion, order.stage, order.disputed, order.reminded, order.reason,
                Utc.toInstant(order.createdAt), Utc.toInstant(order.completedAt));
    }

    private DeliveryDto toDeliveryDto(OrderRow order, DeliveryRow delivery) {
        boolean bySeller = delivery.actorId.equals(order.sellerId);
        return new DeliveryDto(order.id, bySeller ? order.sellerPublicRef : order.buyerPublicRef,
                bySeller ? order.sellerName : order.buyerName, delivery.note,
                Utc.toInstant(delivery.createdAt));
    }
}
