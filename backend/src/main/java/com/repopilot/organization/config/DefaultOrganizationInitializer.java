package com.repopilot.organization.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

@Component
public class DefaultOrganizationInitializer implements ApplicationRunner {

    private final DataSource dataSource;

    public DefaultOrganizationInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        DefaultOrganizationHolder.setDataSource(dataSource);
    }
}
