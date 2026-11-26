package com.campus.identity;

import com.campus.shared.api.ApiException;
import jakarta.servlet.http.HttpSession;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import static com.campus.identity.IdentityController.*;

@RestController
@RequestMapping("/api")
public class ChatController {
    private final JdbcTemplate db;
    public ChatController(JdbcTemplate db) { this.db=db; }
    record Parties(String buyer,String seller) {}
    private boolean history(String object,String buyer) {
        return !db.queryForList("select id from c_messages where context_type='ITEM' and context_id=? and buyer_id=? limit 1",object,buyer).isEmpty();
    }
    private Parties parties(String user,String type,String object,String peer,boolean write) {
        String lock=write?" for update":"";
        if("ORDER".equals(type)) {
            var rows=db.queryForList("select buyer_id,seller_id,stage from orders where id=?"+lock,object);
            if(rows.isEmpty()) throw ApiException.notFound("订单不存在");
            var order=rows.get(0);
            var pair=new Parties((String)order.get("buyer_id"),(String)order.get("seller_id"));
            if(!user.equals(pair.buyer())&&!user.equals(pair.seller())) throw ApiException.forbidden("仅限订单双方");
            if(write&&!Set.of("待卖方确认","待交付","待买方确认").contains(order.get("stage"))) throw ApiException.forbidden("订单会话只读");
            return pair;
        }
        if(!"ITEM".equals(type)) throw ApiException.badRequest("无效会话类型");
        var rows=db.queryForList("select seller_id,status,hidden from items where id=?"+lock,object);
        if(rows.isEmpty()) throw ApiException.notFound("物品不存在");
        var item=rows.get(0);
        String seller=(String)item.get("seller_id"),buyer=user;
        if(user.equals(seller)) {
            var peers=db.queryForList("select id from accounts where public_ref=?",peer);
            if(peers.isEmpty() || !history(object,(String)peers.get(0).get("id"))) throw ApiException.badRequest("请选择已有咨询人");
            buyer=(String)peers.get(0).get("id");
        } else if(!peer.isEmpty() && !peer.equals(db.queryForObject("select public_ref from accounts where id=?",String.class,user)))
            throw ApiException.badRequest("不能指定其他咨询人");
        if(write || !history(object,buyer)) {
            var eligible=db.queryForList("select id from accounts where id in (?,?) and restricted=0 and verified=1"+lock,buyer,seller);
            if(eligible.size()!=2 || !"在售".equals(item.get("status")) || VerificationController.flag(item.get("hidden")))
                throw ApiException.forbidden("当前不能发起物品咨询");
        }
        return new Parties(buyer,seller);
    }
    @GetMapping("/messages")
    public Object read(HttpSession session,@RequestParam String type,@RequestParam String object,@RequestParam(defaultValue="") String peer) {
        var pair=parties(actor(session),type,object,peer,false);
        return db.queryForList("select m.id,m.content,m.created_at,a.nickname as sender_name from c_messages m join accounts a on a.id=m.sender_id where m.context_type=? and m.context_id=? and m.buyer_id=? order by m.created_at,m.id",type,object,pair.buyer());
    }
    @Transactional @PostMapping("/messages")
    public Object send(HttpSession session,@RequestParam String type,@RequestParam String object,@RequestParam(defaultValue="") String peer,@RequestBody Map<String,Object> body) {
        String sender=actor(session), content=text(body,"content").strip();
        if(content.isBlank()||content.codePointCount(0,content.length())>1500) throw ApiException.badRequest("消息需为1至1500字");
        var pair=parties(sender,type,object,peer,true);
        db.update("insert into c_messages(id,context_type,context_id,buyer_id,seller_id,sender_id,content,created_at) values(?,?,?,?,?,?,?,CURRENT_TIMESTAMP(3))",id(),type,object,pair.buyer(),pair.seller(),sender,content);
        return Map.of("ok",true);
    }
    @GetMapping("/inbox")
    public Object inbox(HttpSession session) {
        String user=actor(session);
        return db.queryForList("select m.context_type,m.context_id,a.public_ref as peer_id from c_messages m join accounts a on a.id=m.buyer_id where m.buyer_id=? or m.seller_id=? group by m.context_type,m.context_id,a.public_ref order by max(m.created_at) desc",user,user);
    }
}
