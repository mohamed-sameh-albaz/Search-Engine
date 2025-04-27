package com.example.searchengine.Indexer.Repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.searchengine.Indexer.Entities.Term;

public interface TermsRepository extends JpaRepository<Term, Long> {
    // This interface is responsible for:
    // 1) Stores unique terms (words or tokens) extracted from documents during
    // indexing
    // 2) forming the basis of the inverted index
    // It extends JpaRepository to provide CRUD operations for Term entities.
    // You can add custom query methods here if needed.

}
