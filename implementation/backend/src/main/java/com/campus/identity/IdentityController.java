package com.campus.identity;

import com.campus.shared.api.ApiException;
import jakarta.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class IdentityController {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    public IdentityController(JdbcTemplate db, TransactionTemplate tx) { this.db=db; this.tx=tx; }

    static String text(Map<String,Object> input,String key) {
        if (input.get(key) instanceof String value) return value;
        throw ApiException.badRequest("缺少有效字段：" + key);
    }
    static String id() { return UUID.randomUUID().toString(); }
    static String actor(HttpSession session) {
        if (session.getAttribute(SessionActorProvider.ACCOUNT_ID) instanceof String id) return id;
        throw ApiException.unauthorized("请先登录");
    }
    @GetMapping("/me")
    public Map<String,Object> me(HttpSession session) {
        Object id=session.getAttribute(SessionActorProvider.ACCOUNT_ID);
        var response=new HashMap<String,Object>();
        response.put("user",id==null?null:db.queryForMap("select id,username,nickname,role,verified,restricted from accounts where id=?",id));
        response.put("csrf",CsrfFilter.token(session));
        return response;
    }
    @PostMapping("/auth/register")
    public Map<String,Object> register(@RequestBody Map<String,Object> body,HttpServletRequest request) {
        String name=text(body,"username"), pass=text(body,"password"), nick=text(body,"nickname").strip();
        if (!name.matches("[A-Za-z0-9_]{3,40}") || nick.isEmpty() || nick.codePointCount(0,nick.length())>30 ||
            pass.codePointCount(0,pass.length())<8 || pass.isBlank() || pass.getBytes(StandardCharsets.UTF_8).length>72)
            throw ApiException.badRequest("账户、昵称或密码格式不符合要求");
        String account=id(), hash=PasswordHash.create(pass);
        try {
            tx.executeWithoutResult(status->{
                db.update("insert into accounts(id,public_ref,username,nickname,created_at) values(?,?,?,?,CURRENT_TIMESTAMP)",account,id(),name,nick);
                db.update("insert into c_credentials(account_id,password_hash) values(?,?)",account,hash);
            });
        } catch (DuplicateKeyException duplicate) { throw ApiException.conflict("账户名已使用"); }
        return signedIn(request,account);
    }
    @PostMapping("/auth/login")
    public Map<String,Object> login(@RequestBody Map<String,Object> body,HttpServletRequest request) {
        String password=text(body,"password");
        var rows=db.queryForList("select a.id,c.password_hash from accounts a join c_credentials c on c.account_id=a.id where a.username=?",text(body,"username"));
        if (rows.isEmpty() || !PasswordHash.matches(password,(String)rows.get(0).get("password_hash")))
            throw ApiException.unauthorized("账号或密码错误");
        return signedIn(request,(String)rows.get(0).get("id"));
    }
    private Map<String,Object> signedIn(HttpServletRequest request,String id) {
        if (request.getSession(false)!=null) request.getSession().invalidate();
        var session=request.getSession(true);
        session.setAttribute(SessionActorProvider.ACCOUNT_ID,id);
        return me(session);
    }
    @PostMapping("/auth/logout")
    public Map<String,Object> logout(HttpSession session) { session.invalidate(); return Map.of(); }
}
