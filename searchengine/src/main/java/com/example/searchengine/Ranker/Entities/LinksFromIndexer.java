package com.example.searchengine.Ranker.Entities;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "linksfromindexer")
public class LinksFromIndexer {
    @Id
    int id;
}
