package com.campus.support;

import java.util.Optional;

import com.campus.shared.auth.ActorContext;
import com.campus.shared.auth.ActorProvider;

/**
 * 自测用会话替身：真实身份由 C 的 Session 实现提供，这里只在测试线程内指定调用者，
 * 不进入产品代码，也不通过 HTTP 暴露任何测试开关。
 */
public class TestActors implements ActorProvider {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    public static void login(String userId) {
        CURRENT.set(userId);
    }

    public static void logout() {
        CURRENT.remove();
    }

    @Override
    public Optional<ActorContext> currentActor() {
        return Optional.ofNullable(CURRENT.get()).map(ActorContext::new);
    }
}
