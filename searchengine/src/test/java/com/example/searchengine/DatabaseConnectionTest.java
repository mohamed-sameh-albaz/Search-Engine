package com.example.searchengine;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
public class DatabaseConnectionTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void testDatabaseConnection() throws SQLException {
        // Test DataSource connection
        assertNotNull(dataSource, "DataSource should not be null");

        try (Connection connection = dataSource.getConnection()) {
            assertNotNull(connection, "Connection should not be null");
            assertTrue(connection.isValid(1), "Connection should be valid");
            System.out.println("Database connection test passed successfully.......................");
        }
    }

    @Test
    void testJdbcTemplate() {
        // Test JdbcTemplate query execution
        assertNotNull(jdbcTemplate, "JdbcTemplate should not be null");

        Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        assertNotNull(result, "Query result should not be null");
        assertTrue(result == 1, "Query should return 1");
        System.out.println("SELECT 1 -> Result: " + result);
        System.out.println("JdbcTemplate query test passed successfully................................");
    }
}