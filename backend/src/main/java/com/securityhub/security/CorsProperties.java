package com.securityhub.security;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "securityhub.cors")
public class CorsProperties {

    private List<String> allowedOrigins = new ArrayList<>();
}
