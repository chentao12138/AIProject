package com.aistudy.server.config.forwarded;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.ForwardedHeaderFilter;

/**
 * Ensures the application honours {@code X-Forwarded-*} headers
 * set by an upstream reverse proxy / load balancer.
 *
 * <p>This filter must only be enabled when the service is deployed
 * behind a trusted proxy that terminates TLS and sanitises
 * {@code X-Forwarded-*} input. In production it should be paired
 * with network-policy / firewall rules that prevent direct access
 * to the backend port.
 */
@Configuration
public class ForwardedHeaderConfig {

    @Bean
    public FilterRegistrationBean<ForwardedHeaderFilter> forwardedHeaderFilter() {
        FilterRegistrationBean<ForwardedHeaderFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new ForwardedHeaderFilter());
        registration.setOrder(0);
        return registration;
    }
}
