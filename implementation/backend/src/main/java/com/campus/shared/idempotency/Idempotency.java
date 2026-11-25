package com.campus.shared.idempotency;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.campus.shared.api.ApiException;

/**
 * 意图键与请求摘要：同一操作者、同一动作、同一意图标识只成立一次业务结果；
 * 同键不同内容 400 拒绝，不覆盖旧回执。
 */
@Component
public class Idempotency {

    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{8,100}$");

    private final OperationReceiptMapper receiptMapper;

    public Idempotency(OperationReceiptMapper receiptMapper) {
        this.receiptMapper = receiptMapper;
    }

    public String requireKey(String rawKey) {
        if (rawKey == null || !KEY_PATTERN.matcher(rawKey).matches()) {
            throw ApiException.badRequest("Idempotency-Key 需为 8-100 位字母、数字、下划线或连字符");
        }
        return rawKey;
    }

    public String hash(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(String.join("\u0001", parts)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    public OperationReceiptRow find(String actorId, String action, String idemKey) {
        return receiptMapper.find(actorId, action, idemKey);
    }

    /** 与业务同事务写入回执；result_ref 指向本次成立的业务对象。 */
    public void record(String actorId, String action, String idemKey, String requestHash, String resultRef) {
        OperationReceiptRow row = new OperationReceiptRow();
        row.id = java.util.UUID.randomUUID().toString();
        row.actorId = actorId;
        row.action = action;
        row.idemKey = idemKey;
        row.requestHash = requestHash;
        row.resultRef = resultRef;
        row.createdAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
        receiptMapper.insert(row);
    }

    /** 重放时先核归属与内容一致，再返回既有结果引用。 */
    public String replayResultRef(OperationReceiptRow receipt, String requestHash) {
        if (receipt == null) {
            return null;
        }
        if (!receipt.requestHash.equals(requestHash)) {
            throw ApiException.badRequest("同一 Idempotency-Key 的请求内容与已成立结果不一致，已拒绝且未新增事实");
        }
        return receipt.resultRef;
    }
}
