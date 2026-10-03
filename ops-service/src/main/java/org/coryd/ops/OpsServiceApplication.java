package org.coryd.ops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;

/**
 * Backend for the Ops Console. Owns no data - every read and every decision goes through the
 * service that owns it, under the ops admin's own token. common brings JPA onto the classpath,
 * so the datasource is switched off explicitly rather than left looking for a database.
 */
@SpringBootApplication(scanBasePackages = {"org.coryd.ops", "org.coryd.common"},
        exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class})
public class OpsServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(OpsServiceApplication.class, args);
    }
}
