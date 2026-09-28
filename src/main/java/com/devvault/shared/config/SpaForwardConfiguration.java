package com.devvault.shared.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Forwards known browser routes to the React entry point when the UI is packaged. */
@Configuration
public class SpaForwardConfiguration implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        for (String path : new String[] {
                "/login", "/setup", "/recover", "/projects", "/projects/{id}",
                "/workspaces", "/docker", "/logs", "/automation", "/monitoring", "/settings"
        }) {
            registry.addViewController(path).setViewName("forward:/index.html");
        }
    }
}
