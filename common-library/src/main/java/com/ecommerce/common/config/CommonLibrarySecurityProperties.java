package com.ecommerce.common.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

@Getter
@Setter
@ConfigurationProperties(prefix = "security")
public class CommonLibrarySecurityProperties {

    private GatewayAssertion gatewayAssertion = new GatewayAssertion();
    private Map<String, String> internalServiceKeys = new HashMap<>();

    @Getter
    @Setter
    public static class GatewayAssertion {
        private String publicKey;
    }
}
