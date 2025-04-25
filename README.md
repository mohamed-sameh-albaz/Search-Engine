# Search-Engine

## Java based search engine

## How to start

```
cd ./searchengine
./mvnw spring-boot:run
```

## clean resources

```
cd ./searchengine
./mvnw clean
```

### Project Structure

```
search-engine/
├── searchengine/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/
│   │   │   │   └── com/
│   │   │   │       └── searchengine/
│   │   │   │           ├── config/
│   │   │   │           │   └── DatabaseConfig.java
│   │   │   │           ├── controller/
│   │   │   │           │   └── SearchController.java
│   │   │   │           ├── model/
│   │   │   │           │   ├── Document.java
│   │   │   │           │   ├── Term.java
│   │   │   │           │   └── IndexEntry.java
│   │   │   │           ├── crawler/
│   │   │   │           │   ├── CrawlerService.java
│   │   │   │           │   └── CrawlerConfig.java
│   │   │   │           ├── indexer/
│   │   │   │           │   ├── IndexerService.java
│   │   │   │           │   └── TextProcessor.java
│   │   │   │           ├── query/
│   │   │   │           │   └── QueryService.java
│   │   │   │           ├── ranker/
│   │   │   │           │   └── RankerService.java
│   │   │   │           └── database/
│   │   │   │               ├── DatabaseService.java
│   │   │   │               ├── DocumentRepository.java
│   │   │   │               ├── TermRepository.java
│   │   │   │               └── IndexEntryRepository.java
│   │   │   ├── resources/
│   │   │   │   ├── static/
│   │   │   │   │   └── [built React app files]
│   │   │   │   └── application.properties
│   │   ├── test/
│   │   │   └── java/
│   │   │       └── com/
│   │   │           └── searchengine/
│   │   │               ├── crawler/
│   │   │               │   └── CrawlerServiceTest.java
│   │   │               ├── indexer/
│   │   │               │   └── IndexerServiceTest.java
│   │   │               ├── query/
│   │   │               │   └── QueryServiceTest.java
│   │   │               └── ranker/
│   │   │                   └── RankerServiceTest.java
│   ├── scripts/
│   │   └── init-db.sql
│   ├── .env
│   ├── .gitignore
│   ├── pom.xml
│   └── README.md
├── frontend/
│   ├── public/
│   │   ├── index.html
│   │   └── favicon.ico
│   ├── src/
│   │   ├── components/
│   │   │   ├── SearchBar.js
│   │   │   └── SearchResults.js
│   │   ├── App.js
│   │   ├── App.css
│   │   └── index.js
│   ├── package.json
│   └── tailwind.config.js
└── README.md
```
