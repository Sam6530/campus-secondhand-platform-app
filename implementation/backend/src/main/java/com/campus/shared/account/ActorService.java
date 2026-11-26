package com.campus.shared.account;

import org.springframework.stereotype.Component;

import com.campus.shared.api.ApiException;
import com.campus.shared.auth.ActorContext;
import com.campus.shared.auth.ActorProvider;

/**
 * 从会话取调用者，并重新读取账户当前资格（不信任前端角色字段）。
 * 资格判断区分“新业务”和“旧单必要权利”：受限账户仍可处理旧单，但不允许新交易、新发布、收藏与新咨询。
 */
@Component
public class ActorService {

    private final ActorProvider actorProvider;
    private final AccountMapper accountMapper;

    public ActorService(ActorProvider actorProvider, AccountMapper accountMapper) {
        this.actorProvider = actorProvider;
        this.accountMapper = accountMapper;
    }

    public AccountRow requireAccount() {
        ActorContext context = actorProvider.currentActor()
                .orElseThrow(() -> ApiException.unauthorized("请先登录"));
        AccountRow account = accountMapper.findById(context.userId());
        if (account == null) {
            throw ApiException.unauthorized("会话已失效，请重新登录");
        }
        return account;
    }

    /** 新交易/发布/收藏/物品新咨询的资格：已通过校园认证且未被限制。 */
    public AccountRow requireNewBusinessAccount() {
        AccountRow account = requireAccount();
        if (!account.isVerified()) {
            throw ApiException.badRequest("校园认证通过后才能进行该操作");
        }
        if (account.isRestricted()) {
            throw ApiException.badRequest("账户当前受限，不能发起新交易、新发布或新咨询");
        }
        return account;
    }

    public AccountRow requireAdmin() {
        AccountRow account = requireAccount();
        if (!account.isAdmin()) {
            throw ApiException.forbidden("需要管理员权限");
        }
        return account;
    }
}
