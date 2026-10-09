package com.bharath.internalassist.tenant;

import org.springframework.stereotype.Component;
import java.util.*;
import java.util.regex.Pattern;

@Component
public class TenantRegistry {

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");
    public record KeyOwner(AppProperties.TenantConfig tenant, Role role){}
    private final Map<String, AppProperties.TenantConfig> byId = new LinkedHashMap<>();
    private final Map<String, KeyOwner> byKey = new HashMap<>();

    public TenantRegistry(AppProperties properties){
        if(properties.tenants() == null || properties.tenants().isEmpty())
            throw new IllegalStateException("No tenants configured. Add at least one under app.");

        for(AppProperties.TenantConfig tenant : properties.tenants()){
            if(tenant.id() == null || !ID.matcher(tenant.id()).matches()){
                throw new IllegalStateException("Invalid tenant id: "+ tenant.id() + ". Use 1-40 chars of lowercase letters, digits and hyphens.");
            }
            if(byId.putIfAbsent(tenant.id(), tenant) != null){
                throw new IllegalStateException("Duplicate tenant id: "+ tenant.id());
            }
            register(tenant, tenant.userKey(), Role.USER);
            register(tenant, tenant.adminKey(), Role.ADMIN);
        }
    }

    private void register(AppProperties.TenantConfig tenant, String key, Role role){
        if(key == null || key.length() < 8)
            throw new IllegalStateException("Tenant with id: "+ tenant.id()+ " needs a "+ role + " key of >=8 chars");
        if(byKey.putIfAbsent(key, new KeyOwner(tenant, role)) != null)
            throw new IllegalStateException("Tenant with id: "+ tenant.id() + " has a "+ role+ " key that is already being used. Every key must be unique.");
    }

    public Optional<KeyOwner> byKey(String key){
        return key == null ? Optional.empty() : Optional.ofNullable(byKey.get(key));
    }

    public AppProperties.TenantConfig require(String tenantId){
        AppProperties.TenantConfig tenant = byId.get(tenantId);
        if(tenant == null){
            throw new IllegalStateException("Unknown tenant: "+tenantId);
        }
        return tenant;
    }

    public List<AppProperties.TenantConfig> all(){
        return List.copyOf(byId.values());
    }
}
