package com.campus;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * V1 后端入口。当前实现范围（B）：交易/数据骨架与迁移、物品版本发布修改、
 * 订单快照与唯一占用、交付/确认/取消、基础通知 Outbox。
 * 身份会话与治理入口由 C 提供，B 通过 ActorProvider / ItemReviewService 等契约对接。
 * <p>
 * 说明：Mapper 统一使用接口上的 {@code @Mapper} 注解，由 mybatis-spring-boot-starter 自动扫描；
 * 不使用 {@code @MapperScan("com.campus")}，否则会把共享契约接口（如 ActorProvider）误注册成 Mapper 代理。
 */
@SpringBootApplication
public class CampusApplication {

    public static void main(String[] args) {
        SpringApplication.run(CampusApplication.class, args);
    }
}

