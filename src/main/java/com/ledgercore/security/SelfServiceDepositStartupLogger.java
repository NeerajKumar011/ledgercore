package com.ledgercore.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Logs a loud WARN message on application startup when self-service customer deposits are enabled.
 */
@Component
public class SelfServiceDepositStartupLogger implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SelfServiceDepositStartupLogger.class);

    private final boolean selfServiceDeposits;

    public SelfServiceDepositStartupLogger(
            @Value("${ledgercore.demo.self-service-deposits:false}") boolean selfServiceDeposits) {
        this.selfServiceDeposits = selfServiceDeposits;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (selfServiceDeposits) {
            log.warn("********************************************************************************");
            log.warn("WARNING: SELF-SERVICE DEPOSITS ARE ENABLED (ledgercore.demo.self-service-deposits=true)!");
            log.warn("Customers are permitted to fund their own accounts directly.");
            log.warn("THIS CONFIGURATION IS INTENDED FOR DEMOS ONLY. NEVER ENABLE IN PRODUCTION!");
            log.warn("********************************************************************************");
        }
    }
}
