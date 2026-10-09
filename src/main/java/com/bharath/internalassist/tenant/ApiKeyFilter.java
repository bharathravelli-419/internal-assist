package com.bharath.internalassist.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String CALLER_ATTRIBUTE = "caller";
    private static final List<String> USER_PATHS = List.of("/api/chat", "/api/conversations", "/api/**");
    private static final Pattern USER_ID_REGEX = Pattern.compile("[A-Za-z0-9._@-]{1,64}");

    private final TenantRegistry tenants;
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        var owner = tenants.byKey(request.getHeader("X-API-KEY"));
        if(owner.isEmpty()){
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing or invalid X-API-KEY");
            return;
        }
        String path = StringUtils.cleanPath(request.getRequestURI());
//        boolean userPath = USER_PATHS.stream().anyMatch(p -> path.equals(p) || path.startsWith(p + "/"));
        boolean userPath = path.startsWith("/api");
        if(!userPath && owner.get().role() != Role.ADMIN){
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "This endpoint needs the admin key");
            return;
        }
        String userId = request.getHeader("X-USER-ID");
        if(userId == null || userId.isBlank())
            userId = "anonymous";
        if(!USER_ID_REGEX.matcher(userId).matches()){
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid X-User-ID");
            return;
        }
        request.setAttribute(CALLER_ATTRIBUTE, new Caller(owner.get().tenant().id(),userId, owner.get().role()));
        filterChain.doFilter(request, response);
    }
}
