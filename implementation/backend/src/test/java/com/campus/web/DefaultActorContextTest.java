package com.campus.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 不安装自测会话替身，验证产品默认行为：C 的会话实现接入前，所有受保护操作返回 401，
 * 而不是把共享契约接口误当数据访问对象（回归 2026-11-25 冒烟发现的 BindingException）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DefaultActorContextTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("无会话实现时写操作 401，公开接口仍可用")
    void protectedEndpointsRejectAnonymous() throws Exception {
        mockMvc.perform(get("/api/health")).andExpect(status().isOk());
        mockMvc.perform(get("/api/items")).andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
        mockMvc.perform(post("/api/items")
                        .header("Idempotency-Key", "key-anon-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").exists());
        mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", "key-anon-0002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/orders")).andExpect(status().isUnauthorized());
    }
}
