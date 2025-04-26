package com.example.searchengine.Indexer.Entities;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "Indexerlinks")
public class IndexerLinks {
    @Id
    private int id;
}
