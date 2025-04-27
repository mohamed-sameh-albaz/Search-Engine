// SearchResults.js
import React, { useEffect, useState } from 'react';
import styled from 'styled-components';
import { useLocation, Link, useSearchParams } from 'react-router-dom';

const ResultsContainer = styled.div`
  min-height: 100vh;
  background: radial-gradient(circle, #1a0b2e, #0d061a);
  position: relative;
  overflow: hidden;
  color: #ffffff;
  padding: 40px 20px;
  display: flex;
  justify-content: center;
`;

const ContentWrapper = styled.div`
  width: 100%;
  max-width: 652px;
  margin-left: 20px;
`;

const Header = styled.div`
  margin-bottom: 20px;
`;

const HomeLink = styled(Link)`
  color: #ffffff;
  font-size: 2rem;
  font-weight: bold;
  text-decoration: none;
  text-shadow: 0 0 10px rgba(155, 89, 182, 0.7);
  display: inline-block;

  &:hover {
    color: #9b59b6;
  }
`;

const ResultsTitle = styled.h1`
  font-size: 1.5rem;
  text-shadow: 0 0 10px rgba(155, 89, 182, 0.7);
  margin-bottom: 20px;
  color: #ffffff;
`;

const QueryAnalysis = styled.div`
  background: rgba(155, 89, 182, 0.1);
  padding: 15px;
  border-radius: 8px;
  margin-bottom: 20px;
`;

const AnalysisTitle = styled.h2`
  font-size: 1.2rem;
  color: #9b59b6;
  margin-bottom: 10px;
`;

const AnalysisList = styled.div`
  color: rgba(255, 255, 255, 0.9);
  margin-bottom: 10px;
`;

const AnalysisItem = styled.span`
  background: rgba(155, 89, 182, 0.2);
  padding: 5px 10px;
  border-radius: 15px;
  margin-right: 10px;
  margin-bottom: 10px;
  display: inline-block;
`;

const ResultsList = styled.div`
  display: flex;
  flex-direction: column;
  gap: 25px;
`;

const ResultItem = styled.div`
  background: rgba(155, 89, 182, 0.1);
  padding: 10px;
  border-radius: 8px;
  transition: box-shadow 0.2s ease;

  &:hover {
    box-shadow: 0 2px 8px rgba(155, 89, 182, 0.5);
  }
`;

const ResultLink = styled.a`
  color: #9b59b6;
  font-size: 1.25rem;
  text-decoration: none;
  margin-bottom: 5px;
  display: block;

  &:hover {
    text-decoration: underline;
  }
`;

const ResultUrl = styled.div`
  color: rgba(255, 255, 255, 0.7);
  font-size: 0.875rem;
  margin-bottom: 5px;
`;

const ResultDescription = styled.p`
  color: rgba(255, 255, 255, 0.9);
  font-size: 1rem;
  line-height: 1.5;
  margin: 0;
`;

const PaginationBar = styled.div`
  margin-top: 40px;
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 10px;
  padding: 20px 0;
`;

const PageNumber = styled(Link)`
  color: #ffffff;
  text-decoration: none;
  font-size: 1.2rem;
  padding: 5px 10px;
  border-radius: 5px;
  background: ${props => (props.$active ? '#9b59b6' : 'transparent')};

  &:hover {
    background: #9b59b6;
  }
`;

const NavButton = styled(Link)`
  color: #ffffff;
  text-decoration: none;
  font-size: 1.2rem;
  padding: 5px 15px;

  &:hover {
    text-decoration: underline;
  }
`;

const SearchResults = () => {
  const location = useLocation();
  const [searchParams] = useSearchParams();
  const query = searchParams.get('q') || '';
  const page = parseInt(searchParams.get('page')) || 1;
  const resultsPerPage = 5;
  const [processedQuery, setProcessedQuery] = useState({ phrases: [], stemmed: [] });

  useEffect(() => {
    // Fetch processed query results from backend
    const fetchProcessedQuery = async () => {
      try {
        const response = await fetch(`http://localhost:8080/api/process-query?query=${encodeURIComponent(query)}`);
        if (response.ok) {
          const text = await response.text();
          // Parse the response text
          const phrasesMatch = text.match(/Phrase: \[(.*?)\]/);
          const stemmedMatch = text.match(/Stemmed: \[(.*?)\]/);
          
          const phrases = phrasesMatch ? phrasesMatch[1].split(', ').filter(p => p) : [];
          const stemmed = stemmedMatch ? stemmedMatch[1].split(', ').filter(s => s) : [];
          
          setProcessedQuery({ phrases, stemmed });
        }
      } catch (error) {
        console.error('Error fetching processed query:', error);
      }
    };

    if (query) {
      fetchProcessedQuery();
    }

    // Clear existing particles canvas if it exists
    const existingCanvas = document.getElementById('particles-js').querySelector('canvas');
    if (existingCanvas) {
      existingCanvas.remove();
    }

    window.particlesJS('particles-js', {
      particles: {
        number: { value: 200 },
        color: { value: ['#ffffff', '#9b59b6', '#6a0dad'] },
        shape: { type: 'star' },
        opacity: { value: 0.7, random: true },
        size: { value: 2, random: true },
        line_linked: {
          enable: true,
          distance: 100,
          color: '#9b59b6',
          opacity: 0.3,
          width: 1
        },
        move: {
          speed: 2,
          direction: 'none',
          attract: { enable: true, rotateX: 600, rotateY: 1200 }
        }
      },
      interactivity: {
        events: {
          onhover: { enable: true, mode: 'grab' },
          onclick: { enable: true, mode: 'push' }
        }
      }
    });
  }, [query, location.pathname]);

  // Expanded dummy results data (10 items)
  const dummyResults = [
    {
      title: `${query} Nebula Discovery`,
      url: `www.cosmicexploration.com/${query.toLowerCase()}-nebula`,
      description: `Scientists recently found a ${query}-shaped nebula in the Andromeda galaxy, emitting unique purple radiation.`,
    },
    {
      title: `${query} Star System`,
      url: `www.starcharts.org/${query.toLowerCase()}-system`,
      description: `A newly charted star system named ${query} contains 3 planets with potential for cosmic exploration.`,
    },
    {
      title: `${query} Cosmic Event`,
      url: `www.skynights.com/${query.toLowerCase()}-event`,
      description: `The ${query} meteor shower will be visible next month, promising a spectacular display of violet streaks.`,
    },
    {
      title: `${query} Research Paper`,
      url: `www.astrostudies.edu/${query.toLowerCase()}-research`,
      description: `A study on ${query} reveals connections between dark matter and purple-hued cosmic phenomena.`,
    },
    {
      title: `${query} Galactic Survey`,
      url: `www.galacticsurvey.org/${query.toLowerCase()}`,
      description: `The ${query} survey maps uncharted regions of the galaxy with advanced telescopic technology.`,
    },
    {
      title: `${query} Alien Signals`,
      url: `www.seti.org/${query.toLowerCase()}-signals`,
      description: `Possible alien signals detected near ${query} suggest extraterrestrial communication attempts.`,
    },
    {
      title: `${query} Black Hole Study`,
      url: `www.blackholescience.com/${query.toLowerCase()}`,
      description: `Research on ${query} indicates a massive black hole at the galaxy's edge.`,
    },
    {
      title: `${query} Exoplanet Findings`,
      url: `www.exoplanets.org/${query.toLowerCase()}`,
      description: `New exoplanets discovered near ${query} could support microbial life forms.`,
    },
    {
      title: `${query} Cosmic Radiation`,
      url: `www.cosmicrays.edu/${query.toLowerCase()}`,
      description: `High levels of cosmic radiation detected around ${query} intrigue scientists.`,
    },
    {
      title: `${query} Stellar Map`,
      url: `www.stellarmaps.com/${query.toLowerCase()}`,
      description: `An updated stellar map featuring ${query} helps astronomers navigate the cosmos.`,
    },
  ];

  // Pagination logic
  const totalResults = dummyResults.length; // 10
  const totalPages = Math.ceil(totalResults / resultsPerPage); // 2 pages (10 / 5)
  const startIndex = (page - 1) * resultsPerPage;
  const endIndex = startIndex + resultsPerPage;
  const currentResults = dummyResults.slice(startIndex, endIndex);

  // Generate page numbers
  const pageNumbers = Array.from({ length: totalPages }, (_, i) => i + 1);

  return (
    <ResultsContainer id="particles-js">
      <ContentWrapper>
        <Header>
          <HomeLink to="/">Search Engine</HomeLink>
        </Header>
        <ResultsTitle>Search Results for: "{query}"</ResultsTitle>
        
        <QueryAnalysis>
          <AnalysisTitle>Query Analysis</AnalysisTitle>
          <AnalysisList>
            <div>Phrases: {processedQuery.phrases.length > 0 ? processedQuery.phrases.map((phrase, index) => (
              <AnalysisItem key={index}>{phrase}</AnalysisItem>
            )) : <AnalysisItem>No phrases found</AnalysisItem>}</div>
          </AnalysisList>
          <AnalysisList>
            <div>Stemmed Words: {processedQuery.stemmed.length > 0 ? processedQuery.stemmed.map((word, index) => (
              <AnalysisItem key={index}>{word}</AnalysisItem>
            )) : <AnalysisItem>No stemmed words found</AnalysisItem>}</div>
          </AnalysisList>
        </QueryAnalysis>

        <ResultsList>
          {currentResults.map((result, index) => (
            <ResultItem key={index}>
              <ResultLink href="#">{result.title}</ResultLink>
              <ResultUrl>{result.url}</ResultUrl>
              <ResultDescription>{result.description}</ResultDescription>
            </ResultItem>
          ))}
        </ResultsList>
        <PaginationBar>
          {page > 1 && (
            <NavButton to={`/search?q=${encodeURIComponent(query)}&page=${page - 1}`}>
              Previous
            </NavButton>
          )}
          {pageNumbers.map((pageNum) => (
            <PageNumber
              key={pageNum}
              to={`/search?q=${encodeURIComponent(query)}&page=${pageNum}`}
              $active={pageNum === page}
            >
              {pageNum}
            </PageNumber>
          ))}
          {page < totalPages && (
            <NavButton to={`/search?q=${encodeURIComponent(query)}&page=${page + 1}`}>
              Next
            </NavButton>
          )}
        </PaginationBar>
      </ContentWrapper>
    </ResultsContainer>
  );
};

export default SearchResults;