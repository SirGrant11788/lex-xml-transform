package com.lexisnexis.transform.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI metadata for the Swagger UI (springdoc-openapi).
 *
 * <p>Swagger UI is served at {@code /swagger-ui.html} and the raw
 * OpenAPI 3 spec at {@code /v3/api-docs}. The latter is what a client
 * generator or API gateway would consume.</p>
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI lexXmlTransformOpenApi() {
        return new OpenAPI()
                .components(new Components())
                .info(new Info()
                        .title("LexisNexis XML-to-JSON Transformation Service")
                        .description("""
                                Ingests legal XML documents, validates them against the
                                LexisNexis judgment XSD, transforms them with Saxon-HE
                                XSLT 3.0, and publishes normalized JSON artifacts with
                                idempotency, batch processing, and operational metrics.
                                """)
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Grant Verheul")
                                .email("grant@grantverheul.com"))
                        .license(new License()
                                .name("Proprietary — take-home assignment")));
    }
}
