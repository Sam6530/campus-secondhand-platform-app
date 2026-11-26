package com.campus.identity;

import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 完整Spring上下文与真实Session/CSRF；仅连接独立H2，不执行MySQL迁移。 */
@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:integrated_session;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "campus.migration.enabled=false"
})
@AutoConfigureMockMvc
class IntegratedSessionTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;

    @Test void registrationReturnsPublicIdentityAndRetainsAuthenticatedInternalSession() throws Exception {
        db.execute("create table accounts(id char(36) primary key,public_ref varchar(40),username varchar(40) unique,nickname varchar(30),role varchar(16) default 'USER',verified int default 0,restricted int default 0,created_at timestamp)");
        db.execute("create table c_credentials(account_id char(36) primary key,password_hash varchar(150))");
        var anonymous=mvc.perform(get("/api/me")).andExpect(status().isOk()).andReturn();
        var session=(MockHttpSession)anonymous.getRequest().getSession(false);
        String csrf=json.readTree(anonymous.getResponse().getContentAsString()).get("csrf").asText();
        var registered=mvc.perform(post("/api/auth/register").session(session).header("X-CSRF-Token",csrf)
            .contentType("application/json").content("{\"username\":\"integrated_user\",\"nickname\":\"Campus\",\"password\":\"Password123!\"}"))
            .andExpect(status().isOk()).andReturn();
        HttpSession signedIn=registered.getRequest().getSession(false);
        String internal=IdentityController.actor(signedIn);
        String external=db.queryForObject("select public_ref from accounts where id=?",String.class,internal);
        var body=json.readTree(registered.getResponse().getContentAsString());
        assertEquals(external,body.get("user").get("id").asText());
        assertNotEquals(internal,external);
        mvc.perform(get("/api/me").session((MockHttpSession)signedIn))
            .andExpect(status().isOk()).andExpect(jsonPath("$.user.id").value(external));
        mvc.perform(post("/api/auth/logout").session((MockHttpSession)signedIn).header("X-CSRF-Token",body.get("csrf").asText()))
            .andExpect(status().isOk());
        mvc.perform(get("/api/me")).andExpect(jsonPath("$.user").isEmpty());
    }
}
