package com.example.searchengine.Crawler.Entities;

import java.io.Serializable;
import java.util.Objects;

import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
@Embeddable
@NoArgsConstructor
@AllArgsConstructor
public class RelatedLinksID implements Serializable {
    private String parentDocid;
    private String childDocid;
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RelatedLinksID)) return false;
        RelatedLinksID that = (RelatedLinksID) o;
        return parentDocid.equals(that.parentDocid) && childDocid.equals(that.childDocid);
    }
    @Override
    public int hashCode() {
        return Objects.hash(parentDocid, childDocid);
    }

}
