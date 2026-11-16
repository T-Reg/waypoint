package dev.waypoint.visualizer.config;

import dev.waypoint.visualizer.scenario.Pacer;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class VisualizerConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** Never follows redirects, so the visualizer shows exactly what the gateway returned. */
    @Bean
    public HttpClient httpClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(3))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }

    @Bean
    public Pacer pacer() {
        return Pacer.sleeping();
    }

    @Bean
    public FilterRegistrationBean<AccessFilter> accessFilter(VisualizerProperties properties) {
        FilterRegistrationBean<AccessFilter> registration = new FilterRegistrationBean<>(new AccessFilter(properties.access()));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
