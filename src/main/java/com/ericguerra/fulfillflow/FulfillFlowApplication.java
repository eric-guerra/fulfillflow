package com.ericguerra.fulfillflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
// Repositories are nested interfaces in domain.Repositories; Spring Data skips nested ones unless told otherwise.
@EnableJpaRepositories(considerNestedRepositories = true)
@EnableScheduling
public class FulfillFlowApplication {
    public static void main(String[] args) {
        SpringApplication.run(FulfillFlowApplication.class, args);
    }
}
