package com.campus.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.campus.support.TestActorConfig;
import com.campus.support.TestActors;
import com.campus.support.TestFixtures;

/** HTTP 契约自测：健康检查、公开列表、错误结构与 snake_case 字段（TC-RB-24/28 的 B 范围）。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestActorConfig.class)
class ApiContractTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TestFixtures fixtures;

    private TestFixtures.Account seller;
    private String imageId;

    @BeforeEach
    void setUp() {
        fixtures.reset();
        TestActors.logout();
        seller = fixtures.createUser("seller01", "卖方S", 1, 0);
        imageId = fixtures.createAsset(seller.id(), "ITEM");
    }

    @AfterEach
    void tearDown() {
        TestActors.logout();
    }

    @Test
    @DisplayName("GET /api/health：应用与数据库可用，返回可定位的结构版本")
    void health() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.database").value("MySQL"))
                .andExpect(jsonPath("$.schema").value("V1"))
                .andExpect(jsonPath("$.checkedAt").exists());
    }

    @Test
    @DisplayName("GET /api/items 匿名可读且无结果为空数组")
    void anonymousItemList() throws Exception {
        mockMvc.perform(get("/api/items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("未登录写操作 401 且返回 message，不产生物品")
    void publishRequiresSession() throws Exception {
        mockMvc.perform(post("/api/items")
                        .header("Idempotency-Key", "key-http-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(itemBody("接口物品", "19.90")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").exists());
        org.assertj.core.api.Assertions.assertThat(fixtures.count("items", null)).isZero();
    }

    @Test
    @DisplayName("发布缺 Idempotency-Key 返回 400，不产生待审记录")
    void publishRequiresIdempotencyKey() throws Exception {
        TestActors.login(seller.id());
        mockMvc.perform(post("/api/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(itemBody("接口物品", "19.90")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
        org.assertj.core.api.Assertions.assertThat(fixtures.count("items", null)).isZero();
    }

    @Test
    @DisplayName("GET /api/notifications 未登录 401")
    void notificationsRequireSession() throws Exception {
        mockMvc.perform(get("/api/notifications"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("未实现或不存在的接口返回 404 与统一错误体，不用 500 冒充")
    void unknownEndpointReturns404() throws Exception {
        mockMvc.perform(get("/api/__not_exists__"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("发布成功按 snake_case 返回完整物品，公开卖方引用不是内部主键")
    void publishUsesSnakeCase() throws Exception {
        TestActors.login(seller.id());
        mockMvc.perform(post("/api/items")
                        .header("Idempotency-Key", "key-http-0002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(itemBody("接口物品", "19.90")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.seller_id").value(seller.publicRef()))
                .andExpect(jsonPath("$.image_id").value(imageId))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("待审"))
                .andExpect(jsonPath("$.price").value(19.90))
                .andExpect(jsonPath("$.hidden").value(0));
    }

    @Test
    @DisplayName("修改携带过时版本 400，且不覆盖当前内容")
    void staleEditRejected() throws Exception {
        TestActors.login(seller.id());
        String created = mockMvc.perform(post("/api/items")
                        .header("Idempotency-Key", "key-http-0003")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(itemBody("接口物品", "19.90")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.read(created, "$.id");

        mockMvc.perform(put("/api/items/" + id)
                        .header("Idempotency-Key", "key-http-0004")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"接口物品","category":"教材书籍","description":"描述",
                                 "price":"19.90","image_id":"%s","version":2}
                                """.formatted(imageId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("发布缺请求体返回 400（契约要求 body 必填）")
    void publishRequiresBody() throws Exception {
        TestActors.login(seller.id());
        mockMvc.perform(post("/api/items")
                        .header("Idempotency-Key", "key-http-0006")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
        org.assertj.core.api.Assertions.assertThat(fixtures.count("items", null)).isZero();
    }

    @Test
    @DisplayName("图片上传缺表单字段返回 400（不是 500）")
    void uploadMissingPart() throws Exception {
        TestActors.login(seller.id());
        mockMvc.perform(multipart("/api/assets").param("kind", "ITEM"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    private String itemBody(String title, String price) {
        return """
                {"title":"%s","category":"教材书籍","description":"合成描述","price":"%s","image_id":"%s"}
                """.formatted(title, price, imageId);
    }
}
