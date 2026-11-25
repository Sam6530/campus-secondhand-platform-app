package com.campus.shared.account;

import java.time.LocalDateTime;

/**
 * 账户核心字段（共享迁移 V1）。凭据、认证材料与会话由 C 维护，B 只读这些列做对象/资格校验。
 */
public class AccountRow {

    public String id;
    public String publicRef;
    public String username;
    public String nickname;
    public String role;
    public int verified;
    public int restricted;
    public LocalDateTime createdAt;

    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }

    public boolean isVerified() {
        return verified == 1;
    }

    public boolean isRestricted() {
        return restricted == 1;
    }
}
