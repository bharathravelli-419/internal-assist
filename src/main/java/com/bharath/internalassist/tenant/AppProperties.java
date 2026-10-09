package com.bharath.internalassist.tenant;


import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.List;

@ConfigurationProperties(prefix = "app")
public record AppProperties(List<TenantConfig> tenants) {
    public AppProperties{
        tenants = tenants == null ? List.of(): tenants;
    }

    public record TenantConfig(String id, String name, String description, String userKey, String adminKey,List<String> webPages, List<String> allowedHosts){
        public TenantConfig{
            webPages = webPages == null ? List.of() : webPages;
            allowedHosts = allowedHosts == null ? List.of() : allowedHosts;
        }
    }
}


