package com.olive.commerce.ui;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * 스토어프론트 SPA(React) 정적 서빙 + 클라이언트 라우팅 폴백.
 *
 * <p>Vite 빌드 산출물은 {@code classpath:/static/app/}에 위치하며 {@code /app}에 마운트된다.
 * 실제 에셋(assets/*, favicon.svg)은 그대로 서빙하고, 그 외 {@code /app} 하위 경로는
 * SPA 셸(index.html)로 폴백하여 딥링크 새로고침 시 404가 발생하지 않게 한다.
 */
@Configuration
public class SpaWebConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/app", "/app/");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/app/**")
            .addResourceLocations("classpath:/static/app/")
            .resourceChain(true)
            .addResolver(new PathResourceResolver() {
                @Override
                protected Resource getResource(String resourcePath, Resource location) throws IOException {
                    if (resourcePath.isBlank()) {
                        return location.createRelative("index.html");
                    }
                    Resource requested = location.createRelative(resourcePath);
                    return (requested.exists() && requested.isReadable())
                        ? requested
                        : location.createRelative("index.html");
                }
            });
    }
}
