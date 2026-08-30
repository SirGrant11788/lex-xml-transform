package com.lexisnexis.transform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class LexXmlTransformApplication {
    public static void main(String[] args) {
        SpringApplication.run(LexXmlTransformApplication.class, args);
    }
}
