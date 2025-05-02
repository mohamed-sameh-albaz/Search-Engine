package com.example.searchengine.Crawler.Repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.example.searchengine.Crawler.Entities.RelatedLinks;
import com.example.searchengine.Crawler.Entities.RelatedLinksID;

public interface RelatedLinksRepository extends JpaRepository<RelatedLinks, RelatedLinksID> {
    // Custom query methods can be defined here if needed
    // For example, to find all related links for a specific document:
    // List<RelatedLinks> findByParentDocid(Long parentDocid);]
    @Query(value = "SELECT doc1.id, doc2.id FROM related_links JOIN documents AS doc1 ON related_links.parent_Docid = doc1.url JOIN documents AS doc2 ON related_links.child_docid = doc2.url", nativeQuery = true)
    public List<Object[]> getRelatedLinksIDs(); // Custom query to get related links
}
