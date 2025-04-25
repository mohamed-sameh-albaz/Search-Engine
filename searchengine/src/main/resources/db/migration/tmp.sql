CREATE TABLE link (
    id BIGINT PRIMARY KEY,
    url VARCHAR(255) NOT NULL UNIQUE,
    title VARCHAR(255),
    last_crawled TIMESTAMP
);