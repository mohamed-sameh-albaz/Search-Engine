# Search Engine Project

A full-stack search engine with multi-threaded web crawling, TF-IDF ranking, and real-time search capabilities. Built with Spring Boot, React, and PostgreSQL, this project demonstrates modern search algorithms, efficient document indexing techniques, and a responsive user interface.

![License](https://img.shields.io/badge/license-MIT-blue.svg)
![Java](https://img.shields.io/badge/Java-21-orange.svg)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.2-green.svg)
![React](https://img.shields.io/badge/React-18.2.0-blue.svg)

A comprehensive full-stack search engine application with web crawling, document indexing, and search capabilities. This project demonstrates the implementation of modern search algorithms, efficient indexing techniques, and a responsive user interface.

## Features

### Backend Components
- **Multi-threaded Web Crawler**: Efficiently crawls websites with respect for robots.txt directives
- **Text Processing Engine**: Implements tokenization, stemming, and stop word removal
- **Search API**: Uses TF-IDF algorithms for accurate and fast search results
- **PostgreSQL Database**: Stores documents, indexes, and search metadata

### Frontend Components
- **Modern React UI**: Clean, responsive interface built with React 18
- **Interactive Search**: Real-time suggestions and highlighting
- **Results Navigation**: Pagination with relevance scoring
- **Voice Search**: Speech recognition for hands-free searching

## Architecture

![Architecture Diagram](https://via.placeholder.com/800x400?text=Search+Engine+Architecture)

### Technical Stack
- **Backend**: Java 21, Spring Boot 3.2.x, JPA/Hibernate, PostgreSQL
- **Search Technology**: Custom implementation with TF-IDF, PageRank, and vector space models
- **Frontend**: React 18, Styled Components, Axios
- **DevOps**: Docker, Docker Compose, GitHub Actions

## Getting Started

### Prerequisites
- Docker and Docker Compose
- Git
- Java 17+ (for local development only)
- Node.js 16+ (for local development only)

### Quick Start with Docker

1. Clone the repository
   ```bash
   git clone https://github.com/yourusername/Search-Engine.git
   cd Search-Engine
   ```

2. Create environment variables file
   ```bash
   cp .env.example .env
   # Edit .env with your preferred settings
   ```

3. Build and run with Docker Compose
   ```bash
   docker-compose up -d
   ```

4. Access the application
   - Frontend: [http://localhost:3000](http://localhost:3000)
   - Backend API: [http://localhost:8080](http://localhost:8080)

### Manual Development Setup

#### Backend

1. Configure PostgreSQL database
   ```sql
   CREATE DATABASE searchengine;
   CREATE USER searchuser WITH PASSWORD 'password';
   GRANT ALL PRIVILEGES ON DATABASE searchengine TO searchuser;
   ```

2. Configure application properties
   ```bash
   cd searchengine
   cp src/main/resources/application.properties.example src/main/resources/application.properties
   # Edit application.properties with your database settings
   ```

3. Run the Spring Boot application
   ```bash
   ./mvnw spring-boot:run
   ```

#### Frontend

1. Install dependencies
   ```bash
   cd frontend
   npm install
   ```

2. Configure API endpoint
   ```bash
   cp .env.example .env
   # Edit .env to set REACT_APP_API_URL
   ```

3. Start development server
   ```bash
   npm start
   ```

## Usage

### Starting and Managing Crawls

1. Start a new crawl with specified number of threads:
   ```bash
   curl -X POST "http://localhost:8080/crawler?thread_num=16"
   ```

2. Monitor crawling progress:
   ```bash
   curl -X GET "http://localhost:8080/crawler/status"
   ```
   This will return JSON with crawling statistics, including:
   - Total pages crawled
   - Pages in queue
   - Crawling rate (pages/second)
   - Elapsed time
   - Estimated completion time

3. Stop an active crawl:
   ```bash
   curl -X POST "http://localhost:8080/crawler/stop"
   ```

### Document Indexing

1. Trigger manual indexing of crawled documents:
   ```bash
   curl -X POST "http://localhost:8080/reindex"
   ```

2. Check indexing status:
   ```bash
   curl -X GET "http://localhost:8080/index-status"
   ```
   This endpoint provides information about:
   - Number of documents indexed
   - Current indexing progress
   - Index statistics (unique words, document count)
   - Estimated time remaining

### Searching

Access the web interface at http://localhost:3000 and enter your search query.

The API endpoint is also available for direct integration:
```bash
curl -X GET "http://localhost:8080/search?q=your+search+query&page=0&size=10"
```

### Advanced Search Features
- Use quotes for exact phrases: `"artificial intelligence"`
- Use operators: `machine AND learning`, `python OR java`, `programming NOT javascript`
- Voice search: Click the microphone icon and speak your query
- Filter by domain: `site:github.com python`
- Filter by date: `after:2023-01-01 before:2023-12-31 machine learning`

## Project Structure

```
Search-Engine/
├── searchengine/                  # Spring Boot backend
│   ├── src/main/java/
│   │   └── com/example/searchengine/
│   │       ├── Crawler/           # Web crawler components
│   │       ├── Indexer/           # Document indexing
│   │       └── Search/            # Search functionality
│   └── pom.xml
├── frontend/                      # React frontend
│   ├── src/
│   │   ├── components/            # UI components
│   │   └── App.js                 # Main application
├── docker-compose.yml             # Docker configuration
└── README.md                      # Project documentation
```

## Performance Considerations

- **Database Indexing**: Custom PostgreSQL indices for optimized searches
- **Connection Pooling**: HikariCP for efficient database connections
- **Caching**: In-memory caching of frequent searches
- **Pagination**: All results are paginated to improve performance

## Troubleshooting

### Common Issues

1. **Docker container fails to start**  
   Check logs with `docker-compose logs backend` or `docker-compose logs frontend`

2. **Search results are not appearing**  
   Ensure you've started the crawler to populate the database with documents

3. **Frontend can't connect to backend**  
   Verify the API URL configuration in the frontend's `.env` file

4. **Database vacuum operation fails**  
   This operation requires special permissions. If running locally, ensure your database user has appropriate rights