package dev.waypoint.gateway.listener;

import dev.waypoint.gateway.config.GatewayProperties;
import org.apache.catalina.connector.Connector;
import org.apache.tomcat.util.buf.EncodedSolidusHandling;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods = false)
public class ListenerConfig {

    @Bean
    ListenerPorts listenerPorts() {
        return new ListenerPorts();
    }

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> listenerConnectors(GatewayProperties properties, ListenerPorts ports) {
        return factory -> {
            Connector admin = connector(properties.listeners().adminPort(), properties.listeners().adminAddress());
            Connector management = connector(properties.listeners().managementPort(), properties.listeners().managementAddress());
            ports.register(admin, management);
            factory.addAdditionalConnectors(admin, management);
            // Let encoded '/' reach the normalizer so it is rejected with an audited 400 rather than by Tomcat.
            factory.addConnectorCustomizers(c -> c.setEncodedSolidusHandling(EncodedSolidusHandling.PASS_THROUGH.getValue()));
        };
    }

    @Bean
    FilterRegistrationBean<ListenerFilter> listenerFilterRegistration(ListenerFilter filter) {
        FilterRegistrationBean<ListenerFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }

    private static Connector connector(int port, String address) {
        Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
        connector.setPort(port);
        if (address != null && !address.isBlank()) {
            connector.setProperty("address", address);
        }
        return connector;
    }
}
