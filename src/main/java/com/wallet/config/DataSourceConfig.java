package com.wallet.config;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.net.URI;

/**
 * Handles Render's DATABASE_URL format (postgres://user:pass@host:port/db)
 * and converts it to JDBC format (jdbc:postgresql://host:port/db) automatically.
 */
@Configuration
public class DataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);

    @Bean
    @Primary
    public DataSource dataSource(DataSourceProperties properties) {
        String url = System.getenv("DATABASE_URL");

        if (url != null && url.startsWith("postgres://")) {
            log.info("Detected Render-style DATABASE_URL, converting to JDBC format");
            return buildFromRenderUrl(url);
        }

        // Fall back to Spring's standard datasource configuration
        return properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    private HikariDataSource buildFromRenderUrl(String renderUrl) {
        try {
            URI uri = new URI(renderUrl);
            String host = uri.getHost();
            int port = uri.getPort() > 0 ? uri.getPort() : 5432;
            String database = uri.getPath().substring(1); // remove leading "/"
            String[] userInfo = uri.getUserInfo().split(":", 2);
            String username = userInfo[0];
            String password = userInfo.length > 1 ? userInfo[1] : "";

            String jdbcUrl = String.format("jdbc:postgresql://%s:%d/%s", host, port, database);
            log.info("Resolved JDBC URL: jdbc:postgresql://{}:{}/{}", host, port, database);

            HikariDataSource ds = new HikariDataSource();
            ds.setJdbcUrl(jdbcUrl);
            ds.setUsername(username);
            ds.setPassword(password);
            ds.setMaximumPoolSize(10);
            ds.setMinimumIdle(2);
            return ds;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse DATABASE_URL: " + e.getMessage(), e);
        }
    }
}
