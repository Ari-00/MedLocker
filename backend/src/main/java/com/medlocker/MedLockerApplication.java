package com.medlocker;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MedLockerApplication {
    public static void main(String[] args) {
        // PostgreSQL JDBC sends the JVM timezone during connection startup.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(MedLockerApplication.class, args);
    }
}
