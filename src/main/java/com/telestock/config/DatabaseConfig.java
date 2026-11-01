package com.telestock.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.net.URI;

@Configuration
@Slf4j
public class DatabaseConfig {

    @Value("${DATABASE_URL:}")
    private String databaseUrl;

    @Value("${spring.datasource.url:jdbc:h2:file:./data/telestock}")
    private String defaultJdbcUrl;

    @Value("${spring.datasource.username:sa}")
    private String defaultUsername;

    @Value("${spring.datasource.password:password}")
    private String defaultPassword;

    @Bean
    @Primary
    public DataSource dataSource() {
        HikariConfig config = new HikariConfig();

        if (databaseUrl != null && !databaseUrl.trim().isEmpty() && (databaseUrl.startsWith("postgres://") || databaseUrl.startsWith("postgresql://"))) {
            try {
                log.info("Detected cloud PostgreSQL DATABASE_URL. Configuring persistent PostgreSQL DataSource...");
                URI uri = new URI(databaseUrl.replace("postgresql://", "http://").replace("postgres://", "http://"));
                String host = uri.getHost();
                int port = uri.getPort() > 0 ? uri.getPort() : 5432;
                String path = uri.getPath();
                String userInfo = uri.getUserInfo();
                String username = "";
                String password = "";
                if (userInfo != null && userInfo.contains(":")) {
                    String[] parts = userInfo.split(":", 2);
                    username = parts[0];
                    password = parts[1];
                }

                String jdbcUrl = String.format("jdbc:postgresql://%s:%d%s", host, port, path);
                config.setJdbcUrl(jdbcUrl);
                config.setUsername(username);
                config.setPassword(password);
                config.setDriverClassName("org.postgresql.Driver");
                log.info("Configured cloud PostgreSQL connection to host: {}, db: {}", host, path);
                return new HikariDataSource(config);
            } catch (Exception e) {
                log.error("Failed to parse cloud DATABASE_URL, falling back to default datasource configuration", e);
            }
        }

        config.setJdbcUrl(defaultJdbcUrl);
        config.setUsername(defaultUsername);
        config.setPassword(defaultPassword);
        if (defaultJdbcUrl.contains(":h2:")) {
            config.setDriverClassName("org.h2.Driver");
        } else if (defaultJdbcUrl.contains(":postgresql:")) {
            config.setDriverClassName("org.postgresql.Driver");
        }
        return new HikariDataSource(config);
    }
}
