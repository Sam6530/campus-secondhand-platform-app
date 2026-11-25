package com.campus.trade.admin;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.campus.notification.OutboxDispatcher;
import com.campus.notification.OutboxService;
import com.campus.shared.account.AccountRow;
import com.campus.shared.account.ActorService;
import com.campus.shared.api.ApiException;
import com.campus.shared.audit.AuditService;
import com.campus.shared.db.Utc;
import com.campus.trade.item.ItemDto;
import com.campus.trade.item.ItemMapper;
import com.campus.trade.item.ItemRow;
import com.campus.trade.item.ItemService;
import com.campus.trade.item.ItemStatuses;
import com.campus.trade.item.ItemVersionRow;

/**
 * 物品版本审核的受控动作（INT-RB-TRADE）。C 的管理端点负责管理权限与请求参数绑定，
 * 在同一应用事务中调用本服务；这里只允许审核当前待审版本，旧版本结论不沿用于新版本。
 */
@Service
public class ItemReviewService {

    private final ItemMapper itemMapper;
    private final ItemService itemService;
    private final ActorService actorService;
    private final AuditService auditService;
    private final OutboxService outboxService;
    private final OutboxDispatcher outboxDispatcher;
    private final TransactionTemplate transactionTemplate;

    public ItemReviewService(ItemMapper itemMapper, ItemService itemService, ActorService actorService,
                             AuditService auditService, OutboxService outboxService,
                             OutboxDispatcher outboxDispatcher, TransactionTemplate transactionTemplate) {
        this.itemMapper = itemMapper;
        this.itemService = itemService;
        this.actorService = actorService;
        this.auditService = auditService;
        this.outboxService = outboxService;
        this.outboxDispatcher = outboxDispatcher;
        this.transactionTemplate = transactionTemplate;
    }

    public ItemDto review(String itemId, Integer version, Boolean pass, String rawReason) {
        AccountRow admin = actorService.requireAdmin();
        if (version == null || version < 1) {
            throw ApiException.badRequest("审核必须携带所见版本号");
        }
        if (pass == null) {
            throw ApiException.badRequest("审核必须明确通过或退回");
        }
        String reason = rawReason == null ? "" : rawReason.trim();
        if (reason.isEmpty()) {
            throw ApiException.badRequest("审核必须说明原因");
        }
        if (reason.length() > 1000) {
            throw ApiException.badRequest("审核原因不能超过 1000 字");
        }

        List<String> events = new ArrayList<>();
        transactionTemplate.executeWithoutResult(status -> {
            ItemRow item = itemMapper.lockById(itemId);
            if (item == null) {
                throw ApiException.notFound("物品不存在");
            }
            if (!ItemStatuses.PENDING_REVIEW.equals(item.status)) {
                throw ApiException.badRequest("只有待审物品可以审核，当前状态为「" + item.status + "」");
            }
            if (version != item.currentVersion) {
                throw ApiException.badRequest("审核版本已过时：物品当前版本为 V" + item.currentVersion);
            }
            ItemVersionRow versionRow = itemMapper.findVersion(itemId, version);
            if (versionRow == null) {
                throw ApiException.badRequest("目标版本不存在");
            }
            if (!ItemStatuses.REVIEW_PENDING.equals(versionRow.reviewStatus)) {
                throw ApiException.badRequest("该版本已处理，不能重复审核");
            }

            versionRow.reviewStatus = pass ? ItemStatuses.REVIEW_PASSED : ItemStatuses.REVIEW_RETURNED;
            versionRow.reviewReason = reason;
            versionRow.reviewedBy = admin.id;
            versionRow.reviewedAt = Utc.now();
            itemMapper.updateVersionReview(versionRow);

            String from = item.status;
            item.status = pass ? ItemStatuses.ON_SALE : ItemStatuses.RETURNED;
            item.updatedAt = Utc.now();
            itemMapper.updateItem(item);

            auditService.record(admin.id, "ITEM", itemId, "ITEM_REVIEW", from, item.status, reason,
                    pass ? "PASS" : "RETURN");

            String eventKey = "ITEM:" + itemId + ":V" + version + ":REVIEW";
            outboxService.append(eventKey, item.sellerId, "ITEM_REVIEWED",
                    (pass ? "您的物品审核已通过：" : "您的物品审核被退回：") + versionRow.title);
            events.add(eventKey);
        });
        outboxDispatcher.dispatch(events);
        return itemService.requireById(itemId);
    }
}
