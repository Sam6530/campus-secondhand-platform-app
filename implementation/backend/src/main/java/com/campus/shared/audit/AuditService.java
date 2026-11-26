package com.campus.shared.audit;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.campus.shared.db.Utc;

/** 关键事实留痕：审核、发布/修改/撤下、订单与交付动作在同事务追加审计。 */
@Component
public class AuditService {

    private final AuditMapper auditMapper;

    public AuditService(AuditMapper auditMapper) {
        this.auditMapper = auditMapper;
    }

    public void record(String actorId, String objectType, String objectId, String action,
                       String fromState, String toState, String reason, String result) {
        AuditRow row = new AuditRow();
        row.id = UUID.randomUUID().toString();
        row.actorId = actorId;
        row.objectType = objectType;
        row.objectId = objectId;
        row.action = action;
        row.fromState = fromState;
        row.toState = toState;
        row.reason = reason;
        row.result = result;
        row.createdAt = Utc.now();
        auditMapper.insert(row);
    }
}
