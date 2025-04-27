package com.example.searchengine.Indexer.Entities;

import java.io.Serializable;
import java.util.Objects;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter

public class TermDocumentMappingId implements Serializable {
    private Long term;
    private Long document;

    public TermDocumentMappingId() {
    }

    public TermDocumentMappingId(Long term, Long document) {
        this.term = term;
        this.document = document;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        TermDocumentMappingId that = (TermDocumentMappingId) o;
        return Objects.equals(term, that.term) && Objects.equals(document, that.document);
    }

    @Override
    public int hashCode() {
        return Objects.hash(term, document);
    }
}