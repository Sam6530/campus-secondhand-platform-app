package com.campus.shared.idempotency;

import java.time.LocalDateTime;

/** operation_receipts 行：操作者 + 动作 + 意图键唯一。 */
public class OperationReceiptRow {

    public String id;
    public String actorId;
    public String action;
    public String idemKey;
    public String requestHash;
    public String resultRef;
    public LocalDateTime createdAt;
}
