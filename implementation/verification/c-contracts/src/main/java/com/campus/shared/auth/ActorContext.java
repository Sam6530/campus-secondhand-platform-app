package com.campus.shared.auth;

/**
 * 当前会话的调用者。userId 是内部账户主键，只用于服务端对象/状态校验；
 * 对外响应使用 accounts.public_ref（不透明公开引用，见 INT-RB-AUTH）。
 */
public record ActorContext(String userId) {
}
