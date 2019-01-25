package io.choerodon.gateway.helper.api.filter;

import io.choerodon.gateway.helper.domain.CheckRequest;
import io.choerodon.gateway.helper.domain.CheckResponse;
import io.choerodon.gateway.helper.domain.CheckState;
import io.choerodon.gateway.helper.domain.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static io.choerodon.core.variable.RequestVariableHolder.HEADER_JWT;
import static io.choerodon.core.variable.RequestVariableHolder.HEADER_TOKEN;

@Component
public class RootServletFilter implements Filter {

    private static final Logger LOGGER = LoggerFactory.getLogger(RootServletFilter.class);

    private List<HelperFilter> helperFilters;

    private static final String CONFIG_ENDPOINT = "/choerodon/config";

    private static final String ACCESS_TOKEN_PREFIX = "bearer";

    private static final String ACCESS_TOKEN_PARAM = "access_token";

    public RootServletFilter(Optional<List<HelperFilter>> optionalHelperFilters) {
        helperFilters = optionalHelperFilters.orElseGet(Collections::emptyList)
                .stream()
                .sorted(Comparator.comparing(HelperFilter::filterOrder))
                .collect(Collectors.toList());
    }

    public void setHelperFilters(List<HelperFilter> helperFilters) {
        this.helperFilters = helperFilters;
    }

    @Override
    public void init(FilterConfig filterConfig) {
        // do nothing
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;
        if (CONFIG_ENDPOINT.equals(req.getRequestURI())) {
            chain.doFilter(request, res);
            return;
        }
        RequestContext requestContext = new RequestContext(new CheckRequest(parse(req),
                req.getRequestURI(), req.getMethod().toLowerCase()), new CheckResponse());
        CheckResponse checkResponse = requestContext.response;
        try {
            for (HelperFilter t : helperFilters) {
                if (t.shouldFilter(requestContext) && !t.run(requestContext)) {
                    break;
                }
            }
        } catch (Exception e) {
            checkResponse.setStatus(CheckState.EXCEPTION_GATEWAY_HELPER);
            checkResponse.setMessage("gateway helper error happened: " + e.toString());
            LOGGER.info("Check permission error", e);
        }
        request.setCharacterEncoding("utf-8");
        res.setHeader("Content-type", "text/html;charset=UTF-8");
        res.setCharacterEncoding("utf-8");
        if (checkResponse.getStatus().getValue() < 300) {
            res.setStatus(200);
            LOGGER.debug("Request 200, context: {}", requestContext);
        } else if (checkResponse.getStatus().getValue() < 500) {
            res.setStatus(403);
            LOGGER.info("Request 403, context: {}", requestContext);
        } else {
            res.setStatus(500);
            LOGGER.info("Request 500, context: {}", requestContext);
        }
        if (checkResponse.getJwt() != null) {
            res.setHeader(HEADER_JWT, checkResponse.getJwt());
        }
        if (checkResponse.getMessage() != null) {
            res.setHeader("request-message", checkResponse.getMessage());
        }
        res.setHeader("request-status", checkResponse.getStatus().name());
        res.setHeader("request-code", checkResponse.getStatus().getCode());
        try (PrintWriter out = res.getWriter()) {
            out.flush();
        }
    }

    @Override
    public void destroy() {
        // do nothing
    }

    private String parse(final HttpServletRequest req) {
        String token = req.getHeader(HEADER_TOKEN);
        if (token == null && req.getQueryString() != null && req.getQueryString().contains(ACCESS_TOKEN_PARAM)) {
            for (String i : req.getQueryString().split("&")) {
                if (i.startsWith(ACCESS_TOKEN_PARAM)) {
                    token = i.substring(ACCESS_TOKEN_PARAM.length() + 1);
                }
            }
        }
        if (token != null) {
            if (token.startsWith(ACCESS_TOKEN_PREFIX)) {
                token = token.replaceFirst("%20", " ");
            } else {
                token = ACCESS_TOKEN_PREFIX + " " + token;
            }
        }
        return token;
    }

}
