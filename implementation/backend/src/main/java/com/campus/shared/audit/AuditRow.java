package com.campus.shared.audit;

import java.time.LocalDateTime;

/** audit_facts 行：操作者、对象、动作、前后态、原因、结果，与业务同事务。 */
public class AuditRow {

    public String id;
    public String actorId;
    public String objectType;
    public String objectId;
    public String action;
    public String fromState;
    public String toState;
    public String reason;
    public String result;
    public LocalDateTime createdAt;
}
