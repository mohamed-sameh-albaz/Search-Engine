package com.example.searchengine;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class DatabaseConnectionTest {

	@Autowired
	private DataSource dataSource;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void testDatabaseConnection() throws SQLException {
		try (Connection connection = dataSource.getConnection()) {
			assertNotNull(connection, "Database connection should not be null");
			assertFalse(connection.isClosed(), "Database connection should be open");
		}
	}

	@Test
	void testSimpleQuery() {
		String result = jdbcTemplate.queryForObject("SELECT 1", String.class);
		assertEquals("1", result, "Simple query should return 1");
		System.out.println("111");
	}

	@Test
	void testDatabaseVersion() {
		String version = jdbcTemplate.queryForObject("SELECT version()", String.class);
		assertNotNull(version, "Database version should not be null");
		assertTrue(version.contains("PostgreSQL"), "Version should indicate PostgreSQL");
	}
}