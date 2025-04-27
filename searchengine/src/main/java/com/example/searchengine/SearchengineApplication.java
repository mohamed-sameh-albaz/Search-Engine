package com.example.searchengine;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

@SpringBootApplication
public class SearchengineApplication implements CommandLineRunner {

    @Autowired
    private DataSource dataSource;
    @Autowired
    private Environment environment;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    public static void main(String[] args) {
        SpringApplication.run(SearchengineApplication.class, args);
    }

    @Override
    public void run(String... args) {
        // Test database connection
        try (Connection connection = dataSource.getConnection()) {
            System.out.println("Connected to database successfully...........");
        } catch (SQLException e) {
            System.err.println("Failed to connect to database: " + e.getMessage());
            return; // Exit if database connection fails
        }

        // Print server port
        String port = environment.getProperty("server.port", "8080");
        System.out.println("Listening on port: " + port + ".....");

        // Query database and print result
        try {
            // String sql = "SELECT id, title FROM links WHERE id = ?";
            // FullLinks result = jdbcTemplate.queryForObject(sql, new BeanPropertyRowMapper<>(FullLinks.class), 1);
            // System.out.println("Query result: ID = " + result.getId() + ", Title = " + result.getTitle());
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            System.out.println("No link found for ID = 1");
        } catch (org.springframework.dao.DataAccessException e) {
            System.err.println("Database query failed: " + e.getMessage());
        }
    }
}