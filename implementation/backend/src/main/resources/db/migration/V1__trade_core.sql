-- V1 交易与数据骨架（B 维护的共享迁移序列；已执行脚本不得就地修改，只能新增 Vn__*.sql 升级）
-- 说明：
-- 1) accounts 是本轮共享的核心账户表，B 只读取 id/public_ref/nickname/role/verified/restricted；
--    凭据、会话、认证材料、治理明细由 C 在后续迁移中扩展，不改本文件。
-- 2) 金额单位为人民币元，DECIMAL(12,2) 与接口最大 9999999999.99 一致。
-- 3) 所有时间列为 UTC DATETIME(3)。

CREATE TABLE IF NOT EXISTS accounts (
    id          CHAR(36)     NOT NULL,
    public_ref  VARCHAR(40)  NOT NULL COMMENT '不透明公开引用，不等于内部主键',
    username    VARCHAR(40)  NOT NULL COMMENT '登录标识；凭据/会话由 C 扩展',
    nickname    VARCHAR(30)  NOT NULL,
    role        VARCHAR(16)  NOT NULL DEFAULT 'USER',
    verified    TINYINT      NOT NULL DEFAULT 0,
    restricted  TINYINT      NOT NULL DEFAULT 0,
    created_at  DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_accounts_public_ref (public_ref),
    UNIQUE KEY uk_accounts_username (username),
    KEY idx_accounts_restricted (restricted)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '共享账户核心（C 扩展身份细节）';

CREATE TABLE IF NOT EXISTS items (
    id              CHAR(36)     NOT NULL,
    seller_id       CHAR(36)     NOT NULL,
    status          VARCHAR(16)  NOT NULL COMMENT '待审/在售/审核退回/已撤下/交易中/已成交/交易异常终止',
    current_version INT          NOT NULL,
    image_id        CHAR(36)     NULL,
    hidden          TINYINT      NOT NULL DEFAULT 0,
    relist_allowed  TINYINT      NOT NULL DEFAULT 1,
    created_at      DATETIME(3)  NOT NULL,
    updated_at      DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_items_seller (seller_id, created_at),
    KEY idx_items_public (status, hidden, created_at),
    CONSTRAINT fk_items_seller FOREIGN KEY (seller_id) REFERENCES accounts (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '物品生命周期与当前版本指针';

CREATE TABLE IF NOT EXISTS item_versions (
    id            CHAR(36)      NOT NULL,
    item_id       CHAR(36)      NOT NULL,
    version       INT           NOT NULL,
    title         VARCHAR(120)  NOT NULL,
    category      VARCHAR(16)   NOT NULL,
    description   VARCHAR(5000) NOT NULL,
    price         DECIMAL(12, 2) NOT NULL,
    image_id      CHAR(36)      NULL,
    review_status VARCHAR(16)   NOT NULL COMMENT '待审/通过/退回',
    review_reason VARCHAR(1000) NULL,
    reviewed_by   CHAR(36)      NULL,
    reviewed_at   DATETIME(3)   NULL,
    created_at    DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_item_versions_version (item_id, version),
    CONSTRAINT fk_item_versions_item FOREIGN KEY (item_id) REFERENCES items (id),
    CONSTRAINT ck_item_versions_price CHECK (price > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '物品版本与审核结论（旧结论不沿用于新版本）';

CREATE TABLE IF NOT EXISTS orders (
    id                CHAR(36)      NOT NULL,
    item_id           CHAR(36)      NOT NULL,
    item_version      INT           NOT NULL,
    buyer_id          CHAR(36)      NOT NULL,
    seller_id         CHAR(36)      NOT NULL,
    buyer_public_ref  VARCHAR(40)   NOT NULL,
    seller_public_ref VARCHAR(40)   NOT NULL,
    stage             VARCHAR(16)   NOT NULL COMMENT '待卖方确认/待交付/待买方确认/已完成/已取消/已拒绝/已异常终止',
    disputed          TINYINT       NOT NULL DEFAULT 0,
    reminded          TINYINT       NOT NULL DEFAULT 0,
    title             VARCHAR(120)  NOT NULL,
    description       VARCHAR(5000) NOT NULL,
    price             DECIMAL(12, 2) NOT NULL,
    image_id          CHAR(36)      NULL,
    buyer_name        VARCHAR(30)   NOT NULL,
    seller_name       VARCHAR(30)   NOT NULL,
    reason            VARCHAR(1000) NULL,
    created_at        DATETIME(3)   NOT NULL,
    completed_at      DATETIME(3)   NULL,
    PRIMARY KEY (id),
    KEY idx_orders_buyer (buyer_id, created_at),
    KEY idx_orders_seller (seller_id, created_at),
    KEY idx_orders_item (item_id),
    CONSTRAINT fk_orders_item FOREIGN KEY (item_id) REFERENCES items (id),
    CONSTRAINT fk_orders_buyer FOREIGN KEY (buyer_id) REFERENCES accounts (id),
    CONSTRAINT fk_orders_seller FOREIGN KEY (seller_id) REFERENCES accounts (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '订单阶段与成立时快照（快照只读）';

CREATE TABLE IF NOT EXISTS reservations (
    item_id    CHAR(36)    NOT NULL,
    order_id   CHAR(36)    NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (item_id),
    UNIQUE KEY uk_reservations_order (order_id),
    CONSTRAINT fk_reservations_item FOREIGN KEY (item_id) REFERENCES items (id),
    CONSTRAINT fk_reservations_order FOREIGN KEY (order_id) REFERENCES orders (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '单件物品至多一个有效占用';

CREATE TABLE IF NOT EXISTS deliveries (
    id         CHAR(36)      NOT NULL,
    order_id   CHAR(36)      NOT NULL,
    actor_id   CHAR(36)      NOT NULL,
    note       VARCHAR(1000) NOT NULL,
    created_at DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_deliveries_order (order_id, created_at),
    CONSTRAINT fk_deliveries_order FOREIGN KEY (order_id) REFERENCES orders (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '交付登记事实；登记不等于完成';

CREATE TABLE IF NOT EXISTS operation_receipts (
    id           CHAR(36)     NOT NULL,
    actor_id     CHAR(36)     NOT NULL,
    action       VARCHAR(40)  NOT NULL,
    idem_key     VARCHAR(100) NOT NULL,
    request_hash CHAR(64)     NOT NULL,
    result_ref   VARCHAR(64)  NULL,
    created_at   DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_receipts_intent (actor_id, action, idem_key),
    KEY idx_receipts_result (action, result_ref)

) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '操作者/意图键回执；同键异内容拒绝';


CREATE TABLE IF NOT EXISTS notification_outbox (
    id              CHAR(36)     NOT NULL,
    event_key       VARCHAR(120) NOT NULL,
    recipient_id    CHAR(36)     NOT NULL,
    event_type      VARCHAR(40)  NOT NULL,
    content         VARCHAR(500) NOT NULL,
    status          VARCHAR(16)  NOT NULL COMMENT 'PENDING/SENT/FAILED',
    attempts        INT          NOT NULL DEFAULT 0,
    last_error      VARCHAR(300) NULL,
    next_attempt_at DATETIME(3)  NOT NULL,
    created_at      DATETIME(3)  NOT NULL,
    delivered_at    DATETIME(3)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_outbox_event (event_key),
    KEY idx_outbox_pending (status, next_attempt_at),
    CONSTRAINT fk_outbox_recipient FOREIGN KEY (recipient_id) REFERENCES accounts (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '业务同事务写入的待发送通知';

CREATE TABLE IF NOT EXISTS notifications (
    id         CHAR(36)     NOT NULL,
    user_id    CHAR(36)     NOT NULL,
    outbox_id  CHAR(36)     NULL,
    content    VARCHAR(500) NOT NULL,
    created_at DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_notifications_outbox (outbox_id),
    KEY idx_notifications_user (user_id, created_at),
    CONSTRAINT fk_notifications_user FOREIGN KEY (user_id) REFERENCES accounts (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '站内通知结果；投递失败不回滚业务';

CREATE TABLE IF NOT EXISTS audit_facts (
    id          CHAR(36)      NOT NULL,
    actor_id    CHAR(36)      NULL,
    object_type VARCHAR(24)   NOT NULL,
    object_id   VARCHAR(64)   NOT NULL,
    action      VARCHAR(48)   NOT NULL,
    from_state  VARCHAR(32)   NULL,
    to_state    VARCHAR(32)   NULL,
    reason      VARCHAR(1000) NULL,
    result      VARCHAR(16)   NOT NULL,
    created_at  DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_audit_object (object_type, object_id, created_at),
    KEY idx_audit_actor (actor_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '必要审计事实，与业务同事务';

CREATE TABLE IF NOT EXISTS assets (
    id           CHAR(36)     NOT NULL,
    uploader_id  CHAR(36)     NOT NULL,
    kind         VARCHAR(16)  NOT NULL COMMENT 'ITEM/VERIFY',
    storage_key  VARCHAR(200) NOT NULL COMMENT '环境内相对存储键，不返回绝对路径',
    content_type VARCHAR(60)  NOT NULL,
    size_bytes   BIGINT       NOT NULL,
    created_at   DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_assets_uploader (uploader_id, created_at),
    CONSTRAINT fk_assets_uploader FOREIGN KEY (uploader_id) REFERENCES accounts (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '共享图片资源元数据；读取按对象授权';
