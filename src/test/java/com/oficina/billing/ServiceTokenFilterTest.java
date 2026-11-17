package com.oficina.billing;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ServiceTokenFilterTest {
    @Test void requiresValidCredentialForApiButAllowsHealth() throws Exception {
        String token = "local-test-token-01234567890123456789";
        var filter = new ServiceTokenFilter(token);
        for (String supplied : new String[]{"", "incorrect"}) {
            var req = new MockHttpServletRequest("GET", "/api/quotes");
            if (!supplied.isEmpty()) req.addHeader("X-Service-Token", supplied);
            var res = new MockHttpServletResponse(); filter.doFilter(req, res, new MockFilterChain());
            assertEquals(401, res.getStatus());
        }
        var request = new MockHttpServletRequest("GET", "/api/quotes"); request.addHeader("X-Service-Token", token);
        var chain = new MockFilterChain(); filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertNotNull(chain.getRequest());
        chain = new MockFilterChain(); filter.doFilter(new MockHttpServletRequest("GET", "/actuator/health"), new MockHttpServletResponse(), chain);
        assertNotNull(chain.getRequest());
        assertThrows(IllegalArgumentException.class, () -> new ServiceTokenFilter("short"));
    }
}
