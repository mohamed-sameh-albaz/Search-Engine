// components/Searchbar.js
import React, { useState, useEffect } from 'react';
import styled from 'styled-components';
import { useNavigate, useLocation } from 'react-router-dom';

const SearchContainer = styled.div`
  display: flex;
  flex-direction: column;
  align-items: center;
  width: 100%;
  max-width: 600px;
  margin: ${props => props.$isHomePage ? '0 auto' : '10px 0'};
  position: relative;
`;

const SearchForm = styled.div`
  display: flex;
  width: 100%;
  position: relative;
  border-radius: 24px;
  background: rgba(255, 255, 255, 0.15);
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.1);
  transition: all 0.3s ease;
  overflow: hidden;
  
  &:hover, &:focus-within {
    background: rgba(255, 255, 255, 0.25);
    box-shadow: 0 4px 25px rgba(155, 89, 182, 0.3);
  }
`;

const Input = styled.input`
  flex: 1;
  padding: ${props => props.$isHomePage ? '18px 24px' : '14px 20px'};
  border: none;
  background: transparent;
  color: #fff;
  font-size: ${props => props.$isHomePage ? '18px' : '16px'};
  outline: none;
  width: 100%;
  font-family: 'Roboto', sans-serif;
  
  &::placeholder {
    color: rgba(255, 255, 255, 0.8);
  }
`;

const ClearButton = styled.button`
  background: transparent;
  border: none;
  color: rgba(255, 255, 255, 0.7);
  cursor: pointer;
  padding: 0 15px;
  display: ${props => props.$show ? 'flex' : 'none'};
  align-items: center;
  justify-content: center;
  transition: color 0.2s ease;
  
  &:hover {
    color: rgba(255, 255, 255, 1);
  }
`;

const SearchButton = styled.button`
  display: flex;
  align-items: center;
  justify-content: center;
  width: 60px;
  background: rgba(155, 89, 182, 0.7);
  border: none;
  border-radius: 0 24px 24px 0;
  color: white;
  cursor: pointer;
  transition: background 0.2s ease;
  
  &:hover {
    background: rgba(155, 89, 182, 0.9);
  }
`;

const VoiceButton = styled.button`
  display: flex;
  align-items: center;
  justify-content: center;
  width: 50px;
  background: transparent;
  border: none;
  color: rgba(255, 255, 255, 0.7);
  cursor: pointer;
  transition: color 0.2s ease;
  
  &:hover {
    color: rgba(255, 255, 255, 1);
  }
`;

const VoiceRecognition = styled.div`
  position: absolute;
  top: 100%;
  left: 0;
  right: 0;
  margin-top: 15px;
  background: rgba(155, 89, 182, 0.3);
  padding: 15px;
  border-radius: 12px;
  text-align: center;
  display: ${props => props.$show ? 'block' : 'none'};
  box-shadow: 0 4px 15px rgba(0, 0, 0, 0.2);
  
  p {
    margin: 0;
    font-size: 14px;
    color: white;
  }
  
  h3 {
    margin: 5px 0;
    color: white;
  }
`;

const SearchBar = ({ isResults, initialQuery, onSearch }) => {
  const [query, setQuery] = useState(initialQuery || '');
  const [isListening, setIsListening] = useState(false);
  const [transcript, setTranscript] = useState('');
  const navigate = useNavigate();
  const location = useLocation();
  const isHomePage = location.pathname === '/';
  
  // Get query from URL if on results page
  useEffect(() => {
    if (!isHomePage && initialQuery) {
      setQuery(initialQuery);
    }
  }, [initialQuery, isHomePage]);
  
  const handleSearch = (e) => {
    if (e) e.preventDefault();
    if (query.trim()) {
      if (onSearch) {
        // Use the provided onSearch handler if available (for fresh results)
        onSearch(query);
      } else {
        // Default navigation behavior
        navigate(`/search?q=${encodeURIComponent(query)}`);
      }
    }
  };
  
  const handleClear = () => {
    setQuery('');
    if (!isHomePage) {
      navigate('/');
    }
  };
  
  const toggleVoiceRecognition = () => {
    if (!isListening) {
      setIsListening(true);
      startVoiceRecognition();
    } else {
      setIsListening(false);
      stopVoiceRecognition();
    }
  };
  
  const startVoiceRecognition = () => {
    if ('webkitSpeechRecognition' in window) {
      const recognition = new window.webkitSpeechRecognition();
      recognition.continuous = false;
      recognition.interimResults = true;
      
      recognition.onresult = (event) => {
        const current = event.resultIndex;
        const result = event.results[current][0].transcript;
        setTranscript(result);
      };
      
      recognition.onend = () => {
        if (transcript) {
          setQuery(transcript);
          setIsListening(false);
          setTimeout(() => {
            handleSearch();
          }, 1000);
        } else {
          setIsListening(false);
        }
      };
      
      recognition.start();
      window.recognition = recognition;
    } else {
      alert('Speech recognition is not supported in your browser');
      setIsListening(false);
    }
  };
  
  const stopVoiceRecognition = () => {
    if (window.recognition) {
      window.recognition.stop();
    }
  };

  return (
    <SearchContainer $isHomePage={isHomePage}>
      <SearchForm as="form" onSubmit={handleSearch}>
        <Input 
          type="text"
          placeholder={isHomePage ? 'Search... (Try "term1" OR "term2")' : 'Search...'}
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          $isHomePage={isHomePage}
        />
        {query && (
          <ClearButton 
            type="button" 
            onClick={handleClear}
            $show={query.length > 0}
          >
            ✕
          </ClearButton>
        )}
        <VoiceButton 
          type="button" 
          onClick={toggleVoiceRecognition}
        >
          🎤
        </VoiceButton>
        <SearchButton type="submit">
          🔍
        </SearchButton>
      </SearchForm>
      
      <VoiceRecognition $show={isListening}>
        <p>Say something...</p>
        {transcript && <h3>{transcript}</h3>}
      </VoiceRecognition>
    </SearchContainer>
  );
};

export default SearchBar;