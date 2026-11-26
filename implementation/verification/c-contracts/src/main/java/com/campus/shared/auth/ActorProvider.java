package com.campus.shared.auth;

import java.util.Optional;

/**
 * 身份契约（INT-RB-AUTH）：由 C 的会话/CSRF 模块提供实现，B 只消费 ActorContext，
 * 不信任请求体或前端传入的角色字段。未登录返回 Optional.empty()。
 */
public interface ActorProvider {

    Optional<ActorContext> currentActor();
}
