package com.campus.trade.item;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.campus.asset.AssetMapper;
import com.campus.asset.AssetRow;
import com.campus.shared.account.AccountRow;
import com.campus.shared.account.ActorService;
import com.campus.shared.api.ApiException;
import com.campus.shared.audit.AuditService;
import com.campus.shared.db.Utc;
import com.campus.shared.idempotency.Idempotency;
import com.campus.shared.idempotency.OperationReceiptRow;
import com.campus.shared.text.PublicContentSanitizer;
import com.campus.trade.order.ReservationMapper;

/**
 * 物品发布/修改/撤下与公开检索（FR03/05/06/07，BR02—BR05）。
 * 关键写请求先核身份与归属，再识别同意图回执；新意图才检查资格与当前状态。
 */
@Service
public class ItemService {

    private static final int PAGE_SIZE = 20;
    private static final int MAX_PAGE = 5000;
    private static final List<String> EDITABLE_STATUSES = List.of(
            ItemStatuses.PENDING_REVIEW, ItemStatuses.ON_SALE, ItemStatuses.RETURNED, ItemStatuses.WITHDRAWN);

    private final ItemMapper itemMapper;
    private final AssetMapper assetMapper;
    private final ReservationMapper reservationMapper;
    private final ActorService actorService;
    private final AuditService auditService;
    private final Idempotency idempotency;
    private final TransactionTemplate transactionTemplate;

    public ItemService(ItemMapper itemMapper, AssetMapper assetMapper, ReservationMapper reservationMapper,
                       ActorService actorService, AuditService auditService, Idempotency idempotency,
                       TransactionTemplate transactionTemplate) {
        this.itemMapper = itemMapper;
        this.assetMapper = assetMapper;
        this.reservationMapper = reservationMapper;
        this.actorService = actorService;
        this.auditService = auditService;
        this.idempotency = idempotency;
        this.transactionTemplate = transactionTemplate;
    }

    /** 公开检索：只返回符合 BR03 的物品，空结果为空集合，不泄露未审/隐藏/受限内容。 */
    public List<PublicItemDto> search(String q, String category, int page) {
        if (page < 1 || page > MAX_PAGE) {
            throw ApiException.badRequest("page 超出允许范围（1-" + MAX_PAGE + "）");
        }
        String keyword = normalize(q);
        if (keyword != null && keyword.length() > 120) {
            throw ApiException.badRequest("搜索关键词不能超过 120 字");
        }
        String selectedCategory = normalize(category);
        if (selectedCategory != null && !ItemStatuses.CATEGORIES.contains(selectedCategory)) {
            throw ApiException.badRequest("物品分类不合法");
        }
        long offset = (long) (page - 1) * PAGE_SIZE;
        return itemMapper.searchPublic(keyword, selectedCategory, PAGE_SIZE, offset).stream()
                .map(this::toPublicDto)
                .toList();
    }

    public List<ItemDto> listMine() {
        AccountRow seller = actorService.requireAccount();
        return itemMapper.listMine(seller.id).stream().map(this::toItemDto).toList();
    }

    public ItemDto create(ItemInput input, String rawKey) {
        AccountRow actor = actorService.requireAccount();
        String key = idempotency.requireKey(rawKey);
        Content content = validateContent(input);
        String hash = idempotency.hash("ITEM_CREATE", content.title(), content.category(), content.description(),
                content.price().toPlainString(), content.imageId());

        OperationReceiptRow receipt = idempotency.find(actor.id, "ITEM_CREATE", key);
        if (receipt != null) {
            idempotency.replayResultRef(receipt, hash);
            return loadItem(receipt.resultRef);
        }

        AccountRow seller = actorService.requireNewBusinessAccount();
        requireOwnedItemImage(seller.id, content.imageId());

        String itemId = UUID.randomUUID().toString();
        LocalDateTime now = Utc.now();
        try {
            transactionTemplate.executeWithoutResult(status -> {
                ItemRow item = new ItemRow();
                item.id = itemId;
                item.sellerId = seller.id;
                item.status = ItemStatuses.PENDING_REVIEW;
                item.currentVersion = 1;
                item.imageId = content.imageId();
                item.hidden = 0;
                item.relistAllowed = 1;
                item.createdAt = now;
                item.updatedAt = now;
                itemMapper.insertItem(item);
                itemMapper.insertVersion(newVersion(itemId, 1, content, now));
                auditService.record(seller.id, "ITEM", itemId, "ITEM_PUBLISH", null,
                        ItemStatuses.PENDING_REVIEW, null, "SUCCESS");
                idempotency.record(seller.id, "ITEM_CREATE", key, hash, itemId);
            });
        } catch (DuplicateKeyException ex) {
            OperationReceiptRow raced = idempotency.find(actor.id, "ITEM_CREATE", key);
            if (raced != null) {
                idempotency.replayResultRef(raced, hash);
                return loadItem(raced.resultRef);
            }
            throw ApiException.conflict("同一提交意图正在处理中，请稍后查询结果");
        }
        return loadItem(itemId);
    }


    public ItemDto update(String itemId, ItemUpdateInput input, String rawKey) {
        AccountRow actor = actorService.requireAccount();
        String key = idempotency.requireKey(rawKey);
        if (input == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        if (input.version() == null || input.version() < 1) {
            throw ApiException.badRequest("修改必须携带当前看到的版本号");
        }
        Content content = validateContent(new ItemInput(input.title(), input.category(), input.description(),
                input.price(), input.imageId()));
        String hash = idempotency.hash("ITEM_UPDATE", itemId, String.valueOf(input.version()), content.title(),
                content.category(), content.description(), content.price().toPlainString(), content.imageId());

        OperationReceiptRow receipt = idempotency.find(actor.id, "ITEM_UPDATE", key);
        if (receipt != null) {
            idempotency.replayResultRef(receipt, hash);
            return loadItem(receipt.resultRef);
        }

        AccountRow seller = actorService.requireNewBusinessAccount();
        requireOwnedItemImage(seller.id, content.imageId());

        String[] replayed = new String[1];
        try {
            transactionTemplate.executeWithoutResult(status -> {
                ItemRow item = itemMapper.lockById(itemId);
                if (item == null) {
                    throw ApiException.notFound("物品不存在");
                }
                if (!item.sellerId.equals(seller.id)) {
                    throw ApiException.forbidden("只能修改本人发布的物品");
                }
                // 锁后重读回执：并发同键请求在等锁期间首个已成立，这里直接返回原结果，不重复递增版本
                OperationReceiptRow raced = idempotency.find(seller.id, "ITEM_UPDATE", key);
                if (raced != null) {
                    idempotency.replayResultRef(raced, hash);
                    replayed[0] = raced.resultRef;
                    return;
                }
                if (input.version() != item.currentVersion) {
                    throw ApiException.badRequest("物品版本已变化，请刷新后使用当前版本修改");
                }
                if (reservationMapper.countByItem(itemId) > 0 || ItemStatuses.IN_TRADE.equals(item.status)) {
                    throw ApiException.badRequest("物品已有有效订单占用，不能修改交易标的和约定价格");
                }
                if (ItemStatuses.SOLD.equals(item.status)) {
                    throw ApiException.badRequest("物品已成交，不能修改");
                }
                if (ItemStatuses.ABNORMAL_TERMINATED.equals(item.status)) {
                    throw ApiException.badRequest("物品处于交易异常终止状态，需核实归属并显式重新提交");
                }
                if (!EDITABLE_STATUSES.contains(item.status)) {
                    throw ApiException.badRequest("物品当前状态不允许修改");
                }
                String fromState = item.status;
                int nextVersion = item.currentVersion + 1;
                itemMapper.insertVersion(newVersion(itemId, nextVersion, content, Utc.now()));
                item.currentVersion = nextVersion;
                item.status = ItemStatuses.PENDING_REVIEW;
                item.imageId = content.imageId();
                item.updatedAt = Utc.now();
                itemMapper.updateVersionPointer(item);
                auditService.record(seller.id, "ITEM", itemId, "ITEM_EDIT", fromState,
                        ItemStatuses.PENDING_REVIEW, null, "SUCCESS");
                idempotency.record(seller.id, "ITEM_UPDATE", key, hash, itemId);
            });
        } catch (DuplicateKeyException ex) {
            OperationReceiptRow raced = idempotency.find(actor.id, "ITEM_UPDATE", key);
            if (raced != null) {
                idempotency.replayResultRef(raced, hash);
                return loadItem(raced.resultRef);
            }
            throw ApiException.conflict("同一提交意图正在处理中，请稍后查询结果");
        }
        if (replayed[0] != null) {
            return loadItem(replayed[0]);
        }
        return loadItem(itemId);
    }

    /** 撤下：本人、无有效占用；重复撤下无额外效果，不删除历史。 */
    public void withdraw(String itemId) {
        AccountRow seller = actorService.requireNewBusinessAccount();
        transactionTemplate.executeWithoutResult(status -> {
            ItemRow item = itemMapper.lockById(itemId);
            if (item == null) {
                throw ApiException.notFound("物品不存在");
            }
            if (!item.sellerId.equals(seller.id)) {
                throw ApiException.forbidden("只能撤下本人发布的物品");
            }
            if (ItemStatuses.WITHDRAWN.equals(item.status)) {
                return;
            }
            if (reservationMapper.countByItem(itemId) > 0 || ItemStatuses.IN_TRADE.equals(item.status)) {
                throw ApiException.badRequest("物品已有有效订单占用，不能撤下");
            }
            if (!ItemStatuses.ON_SALE.equals(item.status)) {
                throw ApiException.badRequest("物品当前状态不可撤下");
            }
            item.status = ItemStatuses.WITHDRAWN;
            item.updatedAt = Utc.now();
            itemMapper.updateStatus(item);
            auditService.record(seller.id, "ITEM", itemId, "ITEM_WITHDRAW", ItemStatuses.ON_SALE,
                    ItemStatuses.WITHDRAWN, null, "SUCCESS");
        });
    }


    private ItemVersionRow newVersion(String itemId, int version, Content content, LocalDateTime now) {
        ItemVersionRow row = new ItemVersionRow();
        row.id = UUID.randomUUID().toString();
        row.itemId = itemId;
        row.version = version;
        row.title = content.title();
        row.category = content.category();
        row.description = content.description();
        row.price = content.price();
        row.imageId = content.imageId();
        row.reviewStatus = ItemStatuses.REVIEW_PENDING;
        row.createdAt = now;
        return row;
    }

    /** 供其它后端模块（如 C 的管理审核端点）复用的本人/治理完整投影。 */
    public ItemDto requireById(String itemId) {
        return loadItem(itemId);
    }

    private ItemDto loadItem(String itemId) {
        ItemViewRow view = itemMapper.findViewById(itemId);
        if (view == null) {
            throw ApiException.notFound("物品不存在");
        }
        return toItemDto(view);
    }

    private ItemDto toItemDto(ItemViewRow view) {
        return new ItemDto(view.id, view.sellerRef, view.sellerName, view.title, view.category, view.description,
                view.price, view.imageId, view.version, view.status, view.hidden, view.reviewReason,
                view.relistAllowed, Utc.toInstant(view.createdAt));
    }

    private PublicItemDto toPublicDto(ItemViewRow view) {
        return new PublicItemDto(view.id, view.sellerRef, view.sellerVerified == 1,
                PublicContentSanitizer.sanitize(view.sellerName),
                PublicContentSanitizer.sanitize(view.title), view.category,
                PublicContentSanitizer.sanitize(view.description), view.price, view.imageId, view.version,
                view.status, Utc.toInstant(view.createdAt));
    }

    private Content validateContent(ItemInput input) {
        if (input == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        String title = input.title() == null ? "" : input.title().trim();
        if (title.isEmpty()) {
            throw ApiException.badRequest("标题不能为空");
        }
        if (title.length() > 120) {
            throw ApiException.badRequest("标题不能超过 120 字");
        }
        String category = input.category() == null ? "" : input.category().trim();
        if (!ItemStatuses.CATEGORIES.contains(category)) {
            throw ApiException.badRequest("物品分类不合法");
        }
        String description = input.description() == null ? "" : input.description().trim();
        if (description.isEmpty()) {
            throw ApiException.badRequest("描述不能为空");
        }
        if (description.length() > 5000) {
            throw ApiException.badRequest("描述不能超过 5000 字");
        }
        BigDecimal price = PriceParser.parse(input.price());
        if (input.imageId() == null || input.imageId().isBlank()) {
            throw ApiException.badRequest("物品图片不能为空");
        }
        return new Content(title, category, description, price, input.imageId().trim());
    }

    private void requireOwnedItemImage(String sellerId, String imageId) {
        AssetRow asset = assetMapper.findById(imageId);
        if (asset == null) {
            throw ApiException.badRequest("图片不存在：" + imageId);
        }
        if (!"ITEM".equals(asset.kind)) {
            throw ApiException.badRequest("物品只能引用 ITEM 类型图片");
        }
        if (!asset.uploaderId.equals(sellerId)) {
            throw ApiException.badRequest("只能引用本人上传的图片");
        }
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record Content(String title, String category, String description, BigDecimal price, String imageId) {
    }
}
