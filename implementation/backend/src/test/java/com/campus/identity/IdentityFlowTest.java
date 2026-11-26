package com.campus.identity;

import com.campus.notification.OutboxService;
import com.campus.trade.admin.ItemReviewService;
import com.campus.shared.api.ApiException;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.mock.web.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IdentityFlowTest {
    JdbcTemplate db;
    TransactionTemplate tx;
    IdentityController identity;
    MockHttpServletRequest request;
    VerificationController verification;
    OutboxService notices;
    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        db=new JdbcTemplate(ds);
        tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        db.execute("create table accounts(id char(36) primary key,public_ref varchar(40),username varchar(40) unique,nickname varchar(30),role varchar default 'USER',verified int default 0,restricted int default 0,created_at timestamp)");
        db.execute("create table assets(id char(36) primary key,uploader_id char(36),kind varchar)");
        db.execute("create table orders(id varchar primary key,buyer_id varchar,seller_id varchar,stage varchar)");
        db.execute("create table audit_facts(id varchar,actor_id varchar,object_type varchar,object_id varchar,action varchar,from_state varchar,to_state varchar,reason varchar,result varchar,created_at timestamp)");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V2__identity_chat.sql")).execute(ds);
        identity=new IdentityController(db,tx);
        request=new MockHttpServletRequest();
        notices=mock(OutboxService.class);
        verification=new VerificationController(db,mock(ItemReviewService.class),notices);
    }
    String register(String name){
        identity.register(Map.of("username",name,"nickname",name,"password"," password "),request);
        return IdentityController.actor(request.getSession());
    }
    @Test void loginRotatesSessionAndPreservesPasswordWhitespace(){
        var old=request.getSession(); var token=identity.me(old).get("csrf");
        var buyer=register("buyer"); assertTrue(((MockHttpSession)old).isInvalid());
        assertNotEquals(token,identity.me(request.getSession()).get("csrf"));
        identity.logout(request.getSession());
        assertThrows(ApiException.class,()->identity.login(Map.of("username","buyer","password","password"),request));
        identity.login(Map.of("username","buyer","password"," password "),request);
        assertEquals(buyer,IdentityController.actor(request.getSession()));
    }
    @Test void frontendIdentityUsesPublicReferenceWhileSessionKeepsInternalId(){
        String internal=register("public_identity");
        String external=db.queryForObject("select public_ref from accounts where id=?",String.class,internal);
        var user=(Map<?,?>)identity.me(request.getSession()).get("user");
        assertEquals(external,user.get("id"));
        assertNotEquals(internal,user.get("id"));
        assertEquals(internal,IdentityController.actor(request.getSession()));
    }
    @Test void csrfRequiredEvenForLogin() throws Exception {
        request.setMethod("POST");request.setRequestURI("/api/auth/login");
        var response=new MockHttpServletResponse();
        new CsrfFilter().doFilter(request,response,new MockFilterChain());
        assertEquals(403,response.getStatus());
        request.addHeader("X-CSRF-Token",identity.me(request.getSession()).get("csrf"));
        var chain=new MockFilterChain();
        new CsrfFilter().doFilter(request,new MockHttpServletResponse(),chain);
        assertSame(request,chain.getRequest());
    }
    @Test void verificationChecksOwnershipAndRejectsStaleReview(){
        String buyer=register("buyer"),asset=IdentityController.id();
        var input=Map.<String,Object>of("real_name","测试","student_no","S1","asset_id",asset);
        assertThrows(ApiException.class,()->verification.submit(request.getSession(),input));
        db.update("insert into assets values(?,?,'VERIFY')",asset,buyer);
        tx.executeWithoutResult(s->verification.submit(request.getSession(),input));
        assertThrows(ApiException.class,()->verification.submit(request.getSession(),input));
        String target=db.queryForObject("select id from c_verifications",String.class);
        var decision=Map.<String,Object>of("pass",true,"version",1,"reason","材料核对一致");
        assertThrows(ApiException.class,()->verification.review(request.getSession(),"verification",target,decision));
        String admin=register("admin");db.update("update accounts set role='ADMIN' where id=?",admin);
        tx.executeWithoutResult(s->verification.review(request.getSession(),"verification",target,decision));
        assertEquals(1,db.queryForObject("select verified from accounts where id=?",Integer.class,buyer));
        assertThrows(ApiException.class,()->verification.review(request.getSession(),"verification",target,decision));
        verify(notices).append(anyString(),eq(buyer),anyString(),anyString());
    }
    @Test void httpRegistrationUsesMeTokenAndReturnsFrontendEnvelope() throws Exception {
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders
            .standaloneSetup(identity).addFilters(new CsrfFilter()).build();
        var anonymous=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/me"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn();
        var session=(MockHttpSession)anonymous.getRequest().getSession(false);
        String csrf=new com.fasterxml.jackson.databind.ObjectMapper().readTree(anonymous.getResponse().getContentAsByteArray()).get("csrf").asText();
        var registration=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/register")
            .session(session).contentType("application/json")
            .content("{\"username\":\"http_user\",\"password\":\"password1\",\"nickname\":\"Campus\"}");
        mvc.perform(registration).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mvc.perform(registration.header("X-CSRF-Token",csrf))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.user.role").value("USER"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.csrf").isString());
    }
    @Test void itemHistoryScopesEachBuyerAndOwnerRepliesOnlyToKnownPeer(){
        String seller=register("seller"),buyer=register("buyer");
        db.update("update accounts set verified=1");
        db.execute("create table items(id varchar primary key,seller_id varchar,status varchar,hidden int)");
        db.update("insert into items values('item',?,'在售',0)",seller);
        var chat=new ChatController(db);
        assertThrows(ApiException.class,()->chat.read(request.getSession(),"ITEM","item","spoof"));
        tx.executeWithoutResult(s->chat.send(request.getSession(),"ITEM","item","",Map.of("content","可以面交吗")));
        String publicBuyer=db.queryForObject("select public_ref from accounts where id=?",String.class,buyer);
        request.getSession().setAttribute(SessionActorProvider.ACCOUNT_ID,seller);
        assertThrows(ApiException.class,()->chat.read(request.getSession(),"ITEM","item",""));
        assertEquals(1,((List<?>)chat.inbox(request.getSession())).size());
        tx.executeWithoutResult(s->chat.send(request.getSession(),"ITEM","item",publicBuyer,Map.of("content","可以")));
        db.update("update items set hidden=1");
        assertEquals(2,((List<?>)chat.read(request.getSession(),"ITEM","item",publicBuyer)).size());
        assertThrows(ApiException.class,()->chat.send(request.getSession(),"ITEM","item",publicBuyer,Map.of("content","新咨询")));
    }

    @Test void reviewEndpointForwardsExactItemVersionAfterCheckingAdministrator(){
        var service=mock(ItemReviewService.class);
        var controller=new VerificationController(db,service,notices);
        String admin=register("admin");
        var body=Map.<String,Object>of("version",3,"pass",false,"reason","图片不清晰");
        assertThrows(ApiException.class,()->controller.review(request.getSession(),"item","i1",body));
        verifyNoInteractions(service);
        db.update("update accounts set role='ADMIN' where id=?",admin);
        controller.review(request.getSession(),"item","i1",body);
        verify(service).review("i1",3,false,"图片不清晰");
    }
    @Test void restrictedBuyerCanSendUntilTerminalAndStrangersCannotRead(){
        String buyer=register("buyer"),seller=IdentityController.id();
        db.update("update accounts set restricted=1 where id=?",buyer);
        db.update("insert into orders values('order1',?,?,'待交付')",buyer,seller);
        var chat=new ChatController(db);
        tx.executeWithoutResult(s->chat.send(request.getSession(),"ORDER","order1","ignored",Map.of("content","明天交付")));
        db.update("update orders set stage='已完成'");
        assertThrows(ApiException.class,()->chat.send(request.getSession(),"ORDER","order1","",Map.of("content","新消息")));
        assertEquals(1,((List<?>)chat.read(request.getSession(),"ORDER","order1","")).size());
        register("outsider");assertThrows(ApiException.class,()->chat.read(request.getSession(),"ORDER","order1",""));
    }
}


