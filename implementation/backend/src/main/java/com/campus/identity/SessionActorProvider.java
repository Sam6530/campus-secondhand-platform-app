package com.campus.identity;

import com.campus.shared.auth.ActorContext;
import com.campus.shared.auth.ActorProvider;
import jakarta.servlet.http.HttpSession;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Resolves only the account established by server-side authentication. */
@Component
public final class SessionActorProvider implements ActorProvider {
    static final String ACCOUNT_ID = SessionActorProvider.class.getName() + ".account";

    @Override
    public Optional<ActorContext> currentActor() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return Optional.empty();
        }
        HttpSession session = attributes.getRequest().getSession(false);
        if (session == null || !(session.getAttribute(ACCOUNT_ID) instanceof String id) || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new ActorContext(id));
    }
}
