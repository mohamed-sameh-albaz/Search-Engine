package com.example.searchengine;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class Link {
  @Id
  private Long id;
  private String url;
  private String title;

  // Getters and setters
  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getUrl() {
    return url;
  }

  public void setUrl(String url) {
    this.url = url;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }
}