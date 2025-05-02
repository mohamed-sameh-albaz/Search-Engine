package com.example.searchengine.Indexer.Service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class DatabaseMaintenanceService {
    @Autowired
    private JdbcTemplate jdbcTemplate;

    public void vacuumDatabase() {
        jdbcTemplate.execute("VACUUM ANALYZE word_document_tags");
        jdbcTemplate.execute("VACUUM ANALYZE inverted_index");
    }
}