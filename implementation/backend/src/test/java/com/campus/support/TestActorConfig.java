package com.campus.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.campus.shared.auth.ActorProvider;

/** 测试上下文里用合成会话替代 C 的 Session 实现（@Primary 覆盖默认空实现）。 */
@TestConfiguration
public class TestActorConfig {

    @Bean
    @Primary
    public ActorProvider testActorProvider() {
        return new TestActors();
    }
}
