package com.campus.identity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import static org.junit.jupiter.api.Assertions.*;

class SessionActorProviderTest {
    private final SessionActorProvider provider = new SessionActorProvider();

    @AfterEach void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test void anonymousRequestCannotSupplyItsOwnIdentity() {
        var request = new MockHttpServletRequest();
        request.addParameter("userId", "administrator");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        assertTrue(provider.currentActor().isEmpty());
        assertNull(request.getSession(false));
    }

    @Test void invalidatingSessionRemovesActor() {
        var request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        var session = request.getSession();
        session.setAttribute(SessionActorProvider.ACCOUNT_ID, "buyer-1");
        assertEquals("buyer-1", provider.currentActor().orElseThrow().userId());
        session.invalidate();
        assertTrue(provider.currentActor().isEmpty());
    }
}
