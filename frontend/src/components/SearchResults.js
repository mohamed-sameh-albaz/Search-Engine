// SearchResults.js
import React, { useEffect, useState } from 'react';
import styled from 'styled-components';
import { useLocation, useNavigate, Link } from 'react-router-dom';
import axios from 'axios';
import SearchBar from './Searchbar';

const ResultsContainer = styled.div`
  min-height: 100vh;
  background: radial-gradient(circle, #1a0b2e, #0d061a);
  position: relative;
  overflow: hidden;
  color: #ffffff;
  padding: 20px;
`;

const ContentWrapper = styled.div`
  width: 100%;
  max-width: 800px;
  margin: 20px auto;
  position: relative;
  z-index: 10;
`;

const SearchHeader = styled.div`
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 30px;
`;

const Logo = styled(Link)`
  font-size: 28px;
  font-weight: bold;
  color: #9b59b6;
  text-decoration: none;
  display: flex;
  align-items: center;
  margin-right: 20px;
  text-shadow: 0 0 15px rgba(155, 89, 182, 0.7);

  &:hover {
    text-shadow: 0 0 20px rgba(155, 89, 182, 0.9);
  }
`;

const SearchInfo = styled.div`
  font-size: 14px;
  color: rgba(255, 255, 255, 0.7);
  display: flex;
  align-items: center;
  margin-bottom: 20px;
  padding-bottom: 10px;
  border-bottom: 1px solid rgba(155, 89, 182, 0.3);
`;

const OperatorBadge = styled.span`
  background: rgba(155, 89, 182, 0.4);
  color: white;
  padding: 2px 8px;
  border-radius: 4px;
  margin: 0 6px;
  font-weight: bold;
`;

const ResultsList = styled.div`
  display: flex;
  flex-direction: column;
  gap: 25px;
`;

const ResultItem = styled.div`
  background: rgba(255, 255, 255, 0.05);
  border-radius: 10px;
  padding: 20px;
  transition: all 0.2s ease;
  border-left: 3px solid rgba(155, 89, 182, 0.7);
  overflow: hidden;

  &:hover {
    background: rgba(255, 255, 255, 0.08);
    box-shadow: 0 5px 15px rgba(0, 0, 0, 0.2);
    transform: translateY(-2px);
  }
`;

const ResultLink = styled.a`
  color: #9b59b6;
  font-size: 20px;
  font-weight: bold;
  text-decoration: none;
  margin-bottom: 5px;
  display: block;

  &:hover {
    text-decoration: underline;
  }
`;

const ResultUrl = styled.div`
  color: rgba(255, 255, 255, 0.5);
  font-size: 14px;
  margin-bottom: 10px;
  display: flex;
  align-items: center;
  word-break: break-all;
  overflow-wrap: break-word;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;

  @media (max-width: 600px) {
    flex-direction: column;
    align-items: flex-start;
    white-space: normal;
  }
`;

const ResultSnippet = styled.p`
  color: rgba(255, 255, 255, 0.8);
  font-size: 16px;
  line-height: 1.5;
  margin: 0;
  
  span.highlight {
    background-color: rgba(155, 89, 182, 0.3);
    padding: 2px 0;
  }
`;

const LoadingContainer = styled.div`
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 300px;
  
  .spinner {
    width: 40px;
    height: 40px;
    border: 4px solid rgba(155, 89, 182, 0.3);
    border-top: 4px solid #9b59b6;
    border-radius: 50%;
    animation: spin 1s infinite linear;
    margin-bottom: 20px;
  }
  
  @keyframes spin {
    0% { transform: rotate(0deg); }
    100% { transform: rotate(360deg); }
  }
`;

const NoResults = styled.div`
  text-align: center;
  padding: 50px 20px;
  
  h2 {
    color: #9b59b6;
    margin-bottom: 15px;
  }
  
  p {
    color: rgba(255, 255, 255, 0.7);
    max-width: 500px;
    margin: 0 auto;
  }
`;

const PaginationContainer = styled.div`
  display: flex;
  justify-content: center;
  margin-top: 40px;
  gap: 10px;
`;

const PageButton = styled.button`
  background: ${props => props.$active ? 'rgba(155, 89, 182, 0.7)' : 'rgba(255, 255, 255, 0.1)'};
  color: #ffffff;
  border: none;
  border-radius: 5px;
  padding: 8px 15px;
  font-size: 16px;
  cursor: pointer;
  transition: all 0.2s ease;

  &:hover {
    background: rgba(155, 89, 182, 0.6);
  }
  
  &:disabled {
    opacity: 0.5;
    cursor: not-allowed;

  &:hover {
      background: rgba(255, 255, 255, 0.1);
    }
  }
`;

const SuggestionsContainer = styled.div`
  margin: 10px 0 20px;
  padding: 10px;
  background-color: #f5f8fa;
  border-radius: 8px;
`;

const SuggestionTitle = styled.div`
  font-weight: 500;
  margin-bottom: 8px;
  color: #5f6368;
`;

const SuggestionsWrapper = styled.div`
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
`;

const SuggestionButton = styled.button`
  background-color: #fff;
  border: 1px solid #dadce0;
  border-radius: 16px;
  color: #1a0dab;
  cursor: pointer;
  font-size: 14px;
  padding: 6px 12px;
  transition: background-color 0.2s;
  
  &:hover {
    background-color: #f1f3f4;
    text-decoration: underline;
  }
`;

const RankingInfoIcon = styled.span`
  background-color: #1a73e8;
  color: white;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  margin-left: 6px;
  cursor: help;
`;

const RankingInfoTooltip = styled.div`
  position: relative;
  display: inline-block;
  
  &:hover::after {
    content: attr(title);
    position: absolute;
    background: rgba(0, 0, 0, 0.8);
    color: white;
    padding: 10px;
    border-radius: 4px;
    width: 300px;
    top: 100%;
    left: 50%;
    transform: translateX(-50%);
    z-index: 100;
    font-size: 12px;
    line-height: 1.4;
  }
`;

const highlightText = (text, keywords) => {
  if (!keywords || !keywords.length) return text;
  
  let highlightedText = text;
  
  keywords.forEach(keyword => {
    const regex = new RegExp(`(${keyword})`, 'gi');
    highlightedText = highlightedText.replace(regex, '<span class="highlight">$1</span>');
  });
  
  return <span dangerouslySetInnerHTML={{ __html: highlightedText }} />;
};

const SearchResults = () => {
  const [results, setResults] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [processedQuery, setProcessedQuery] = useState({ phrases: [], stemmed: [] });
  const [totalResults, setTotalResults] = useState(0);
  const [currentPage, setCurrentPage] = useState(1);
  const [loadTime, setLoadTime] = useState(null);
  const [operator, setOperator] = useState(null);
  const [suggestedQueries, setSuggestedQueries] = useState([]);
  const [rankingFactors, setRankingFactors] = useState({});
  const [sessionId, setSessionId] = useState(null);
  const [paginationInfo, setPaginationInfo] = useState({
    totalPages: 0,
    hasNextPage: false,
    hasPreviousPage: false,
    startItem: 0,
    endItem: 0
  });
  const resultsPerPage = 10;
  
  const location = useLocation();
  const navigate = useNavigate();
  const queryParams = new URLSearchParams(location.search);
  const query = queryParams.get('q') || '';
  const page = parseInt(queryParams.get('page')) || 1;
  const searchSessionId = queryParams.get('sid') || null;

  useEffect(() => {
    if (!query) {
      navigate('/');
      return;
    }
    
    // Reset session ID when query changes to force new results
    if (query && sessionId) {
      const lastQuery = localStorage.getItem('lastSearchQuery');
      if (lastQuery && lastQuery !== query) {
        setSessionId(null);
        console.log('Query changed, resetting session ID');
      }
    }
    
    // Store current query for future comparison
    localStorage.setItem('lastSearchQuery', query);
    
    setCurrentPage(page);
    // Use the session ID from URL if available, otherwise use the stored one
    const effectiveSessionId = searchSessionId || sessionId;
    fetchResults(effectiveSessionId);
  }, [query, page, searchSessionId]);
  
  const fetchResults = async (currentSessionId) => {
    setLoading(true);
    setError(null);
    
    try {
      const startTime = performance.now();
      
      // API base URL with fallback
      const API_BASE_URL = 'http://localhost:8080/api';
      
      // Configure axios with credentials and headers
      axios.defaults.withCredentials = true;
      axios.defaults.headers.common['Accept'] = 'application/json';
      axios.defaults.headers.common['Content-Type'] = 'application/json';
      
      // Fetch processed query information
      console.log('Fetching processed query for:', query);
      let processedQueryData = { phrases: [], stemmedWords: [], operator: null };
      
      try {
        const processQueryResponse = await axios.get(`${API_BASE_URL}/process-query?query=${encodeURIComponent(query)}`);
        processedQueryData = processQueryResponse.data;
        console.log('Processed query data:', processedQueryData);
      } catch (err) {
        console.error('Error processing query:', err);
        // Continue with empty data
      }
      
      setProcessedQuery({
        phrases: processedQueryData.phrases || [],
        stemmed: processedQueryData.stemmedWords || []
      });
      setOperator(processedQueryData.operator || null);
      
      // Fetch search results with session ID for consistent pagination
      console.log('Fetching search results for:', query);
      const searchUrl = `${API_BASE_URL}/query-search?query=${encodeURIComponent(query)}&page=${page}&pageSize=${resultsPerPage}`;
      const urlWithSession = currentSessionId ? `${searchUrl}&sessionId=${currentSessionId}` : searchUrl;
      
      const searchResponse = await axios.get(urlWithSession);
      
      const endTime = performance.now();
      setLoadTime((endTime - startTime).toFixed(2));
      
      console.log('Search results:', searchResponse.data);
      
      // Set standard results
      setResults(searchResponse.data.results || []);
      setTotalResults(searchResponse.data.totalResults || 0);
      
      // Store the session ID for future pagination
      const newSessionId = searchResponse.data.sessionId;
      if (newSessionId) {
        setSessionId(newSessionId);
        // Update URL if needed without triggering a reload
        if (!searchSessionId && window.history.replaceState) {
          const newParams = new URLSearchParams(location.search);
          newParams.set('sid', newSessionId);
          const newUrl = `${location.pathname}?${newParams.toString()}`;
          window.history.replaceState({ path: newUrl }, '', newUrl);
        }
      }
      
      // Calculate correct pagination information
      const total = searchResponse.data.totalResults || 0;
      const totalPages = Math.ceil(total / resultsPerPage);
      const startItem = total > 0 ? (page - 1) * resultsPerPage + 1 : 0;
      const endItem = Math.min(page * resultsPerPage, total);
      
      // Set enhanced pagination information
      setPaginationInfo({
        totalPages,
        hasNextPage: page < totalPages,
        hasPreviousPage: page > 1,
        startItem,
        endItem
      });
      
      // Set suggested queries
      setSuggestedQueries(searchResponse.data.suggestedQueries || []);
      
      // Set ranking factors
      setRankingFactors(searchResponse.data.rankingFactors || {});
      
      setLoading(false);
    } catch (err) {
      console.error('Error fetching search results:', err);
      let errorMessage = 'An error occurred while fetching search results. Please try again.';
      
      if (err.response) {
        console.error('Response error:', err.response.status, err.response.data);
        if (err.response.status === 0 || err.response.status === 404) {
          errorMessage = 'Could not connect to the search service. Please make sure the backend is running.';
        } else if (err.response.data && err.response.data.message) {
          errorMessage = err.response.data.message;
        }
      } else if (err.request) {
        console.error('Request error:', err.request);
        errorMessage = 'No response received from the server. Please check your connection.';
      }
      
      setError(errorMessage);
      setLoading(false);
    }
  };
  
  const handlePageChange = (newPage) => {
    if (newPage < 1 || newPage > paginationInfo.totalPages) return;
    
    // Always include session ID in pagination URLs for consistency
    const url = `/search?q=${encodeURIComponent(query)}&page=${newPage}${sessionId ? `&sid=${sessionId}` : ''}`;
    navigate(url);
  };
  
  const handleSuggestedQueryClick = (suggestedQuery) => {
    // Start a new search session for a new query, explicitly clearing session ID
    setSessionId(null); // Reset session ID for new query
    navigate(`/search?q=${encodeURIComponent(suggestedQuery)}&page=1`);
  };
  
  const handleNewSearch = (newQuery) => {
    // Always clear session ID for new searches to ensure fresh results
    setSessionId(null);
    navigate(`/search?q=${encodeURIComponent(newQuery)}&page=1`);
  };
  
  const renderPagination = () => {
    const { totalPages } = paginationInfo;
    
    if (totalPages <= 1) return null;
    
    return (
      <PaginationContainer>
        <PageButton 
          onClick={() => handlePageChange(currentPage - 1)}
          disabled={!paginationInfo.hasPreviousPage}
        >
          Previous
        </PageButton>
        
        {Array.from({ length: Math.min(5, totalPages) }, (_, i) => {
          // Show current page and surrounding pages
          let pageNum;
          
          if (totalPages <= 5) {
            // If we have 5 or fewer pages, show all
            pageNum = i + 1;
          } else if (currentPage <= 3) {
            // If we're near the start
            pageNum = i + 1;
          } else if (currentPage >= totalPages - 2) {
            // If we're near the end
            pageNum = totalPages - 4 + i;
          } else {
            // We're in the middle
            pageNum = currentPage - 2 + i;
          }
          
          return (
            <PageButton
              key={pageNum}
              onClick={() => handlePageChange(pageNum)}
              $active={currentPage === pageNum}
            >
              {pageNum}
            </PageButton>
          );
        })}
        
        <PageButton 
          onClick={() => handlePageChange(currentPage + 1)}
          disabled={!paginationInfo.hasNextPage}
        >
          Next
        </PageButton>
      </PaginationContainer>
    );
  };
  
  const renderSuggestedQueries = () => {
    if (!suggestedQueries || suggestedQueries.length === 0) return null;
    
    return (
      <SuggestionsContainer>
        <SuggestionTitle>Related searches:</SuggestionTitle>
        <SuggestionsWrapper>
          {suggestedQueries.map((suggestion, index) => (
            <SuggestionButton 
              key={index}
              onClick={() => handleSuggestedQueryClick(suggestion)}
            >
              {suggestion}
            </SuggestionButton>
          ))}
        </SuggestionsWrapper>
      </SuggestionsContainer>
    );
  };
  
  const renderResultRankingInfo = () => {
    if (!rankingFactors || Object.keys(rankingFactors).length === 0) return null;
    
    return (
      <RankingInfoTooltip title={
        <div>
          <h4>Ranking factors:</h4>
          <ul>
            {Object.entries(rankingFactors).map(([key, description]) => (
              <li key={key}><strong>{key}</strong>: {description}</li>
            ))}
          </ul>
        </div>
      }>
        <RankingInfoIcon>ⓘ</RankingInfoIcon>
      </RankingInfoTooltip>
    );
  };
  
  const keywords = [...processedQuery.phrases, ...processedQuery.stemmed];

  // Format the operator for display
  const getOperatorDisplay = () => {
    if (!operator) return null;
    
    return (
      <OperatorBadge>{operator}</OperatorBadge>
    );
  };

  return (
    <ResultsContainer>
      <ContentWrapper>
        <SearchHeader>
          <Logo to="/">Search Engine</Logo>
          <SearchBar initialQuery={query} onSearch={handleNewSearch} />
        </SearchHeader>
        
        {!loading && !error && (
          <SearchInfo>
            ⏱️ Results found in {loadTime} ms - Showing {paginationInfo.startItem}-{paginationInfo.endItem} of {totalResults} results 
            {operator && (
              <> using {getOperatorDisplay()} operation</>
            )} 
            for "{query}" {renderResultRankingInfo()}
          </SearchInfo>
        )}
        
        {loading ? (
          <LoadingContainer>
            <div className="spinner"></div>
            <p>Searching for "{query}"...</p>
          </LoadingContainer>
        ) : error ? (
          <NoResults>
            <h2>An error occurred</h2>
            <p>{error}</p>
          </NoResults>
        ) : results.length === 0 ? (
          <NoResults>
            <h2>No results found</h2>
            <p>We couldn't find any results for "{query}". Please try different keywords or check your spelling.</p>
          </NoResults>
        ) : (
          <>
            {renderSuggestedQueries()}
            
            <ResultsList>
              {results.map((result, index) => (
                <ResultItem key={index}>
                  <ResultLink href={result.url} target="_blank" rel="noopener noreferrer">
                    {result.title || 'No Title'}
                  </ResultLink>
                  <ResultUrl>
                    🔗 {result.url}
                  </ResultUrl>
                  <ResultSnippet>
                    {highlightText(result.snippet || 'No description available', keywords)}
                  </ResultSnippet>
                </ResultItem>
              ))}
            </ResultsList>
            
            {renderPagination()}
          </>
        )}
      </ContentWrapper>
    </ResultsContainer>
  );
};

export default SearchResults;