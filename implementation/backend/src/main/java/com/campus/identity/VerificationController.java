package com.campus.identity;

import com.campus.shared.api.ApiException;
import com.campus.trade.admin.ItemReviewService;
import com.campus.notification.OutboxService;
import jakarta.servlet.http.HttpSession;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import static com.campus.identity.IdentityController.*;

@RestController
@RequestMapping("/api")
public class VerificationController {
    private final JdbcTemplate db;
    private final ItemReviewService items;
    private final OutboxService notices;
    public VerificationController(JdbcTemplate db,ItemReviewService items,OutboxService notices) {
        this.db=db; this.items=items; this.notices=notices;
    }
    @GetMapping("/verifications")
    public Object mine(HttpSession session) {
        return db.queryForList("select id,version,status,reason,created_at from c_verifications where account_id=? order by version desc",actor(session));
    }
    @Transactional @PostMapping("/verifications")
    public Object submit(HttpSession session,@RequestBody Map<String,Object> input) {
        String user=actor(session), real=text(input,"real_name").strip(), number=text(input,"student_no").strip(), asset=text(input,"asset_id");
        if (real.isBlank()||number.isBlank()||real.length()>50||number.length()>50) throw ApiException.badRequest("姓名或学号无效");
        var account=db.queryForMap("select verified,restricted from accounts where id=? for update",user);
        if(flag(account.get("verified")) || flag(account.get("restricted"))) throw ApiException.forbidden("当前账户不能提交认证");
        if(db.queryForList("select id from assets where id=? and uploader_id=? and kind='VERIFY'",asset,user).isEmpty())
            throw ApiException.forbidden("材料必须为本人上传的认证图片");
        var history=db.queryForList("select version,status from c_verifications where account_id=? order by version desc limit 1",user);
        if(!history.isEmpty() && "待审".equals(history.get(0).get("status"))) throw ApiException.conflict("已有待审材料");
        int version=history.isEmpty()?1:((Number)history.get(0).get("version")).intValue()+1;
        String verification=id();
        db.update("insert into c_verifications(id,account_id,version,real_name,student_no,asset_id,status,created_at) values(?,?,?,?,?,?,'待审',CURRENT_TIMESTAMP)",verification,user,version,real,number,asset);
        return Map.of("id",verification,"version",version);
    }
    static boolean flag(Object value) { return Boolean.TRUE.equals(value) || value instanceof Number n && n.intValue()!=0; }
    private String admin(HttpSession session) {
        String user=actor(session);
        if(db.queryForList("select id from accounts where id=? and role='ADMIN' and restricted=0",user).isEmpty())
            throw ApiException.forbidden("需要管理员权限");
        return user;
    }
    @GetMapping("/admin/dashboard")
    public Object dashboard(HttpSession session) {
        admin(session);
        return Map.of("verifications",db.queryForList("select id,version,real_name,student_no,asset_id,status from c_verifications where status='待审' order by created_at"),
            "items",db.queryForList("select i.id,i.current_version as version,v.title,v.description,i.image_id from items i join item_versions v on i.id=v.item_id and i.current_version=v.version where i.status='待审'"));
    }
    @Transactional @PostMapping("/admin/{kind:item|verification}/{target}")
    public Object review(HttpSession session,@PathVariable String kind,@PathVariable String target,@RequestBody Map<String,Object> input) {
        String reviewer=admin(session),reason=text(input,"reason").strip();
        if(reason.isBlank() || reason.length()>1000 || !(input.get("pass") instanceof Boolean pass) || !(input.get("version") instanceof Integer version))
            throw ApiException.badRequest("需提供审核版本、结论和理由");
        if(kind.equals("item")) return items.review(target,version,pass,reason);
        var rows=db.queryForList("select account_id from c_verifications where id=?",target);
        if(rows.isEmpty()) throw ApiException.notFound("认证记录不存在");
        String owner=(String)rows.get(0).get("account_id");
        db.queryForMap("select id from accounts where id=? for update",owner);
        String state=pass?"通过":"退回";
        if(db.update("update c_verifications set status=?,reason=? where id=? and version=? and status='待审'",state,reason,target,version)!=1)
            throw ApiException.badRequest("材料版本或状态已经变化");
        if(pass) db.update("update accounts set verified=1 where id=?",owner);
        db.update("insert into audit_facts(id,actor_id,object_type,object_id,action,from_state,to_state,reason,result,created_at) values(?,?,'verification',?,'review','待审',?,?, 'OK',CURRENT_TIMESTAMP)",id(),reviewer,target,state,reason);
        notices.append("verification:"+target,owner,"VERIFICATION","认证审核："+state);
        return Map.of("ok",true);
    }
}
