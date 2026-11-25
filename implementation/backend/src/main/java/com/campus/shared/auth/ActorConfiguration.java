package com.campus.shared.auth;

import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 在没有会话实现时提供“无登录态”的默认实现：所有受保护操作返回 401。
 * C 提交 ActorProvider（基于服务端 Session）后，此 Bean 自动退让，不需要修改 B 的代码。
 */
@Configuration
public class ActorConfiguration {

    @Bean
    @ConditionalOnMissingBean(ActorProvider.class)
    public ActorProvider noSessionActorProvider() {
        return () -> Optional.empty();
    }
}
