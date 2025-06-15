// components/Searchbar.js
import React, { useState, useEffect, useRef } from 'react';
import styled from 'styled-components';
import { useNavigate, useLocation } from 'react-router-dom';
import axios from 'axios';

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
  color: ${props => props.$isListening ? 'rgba(255, 89, 89, 0.9)' : 'rgba(255, 255, 255, 0.7)'};
  cursor: pointer;
  transition: color 0.2s ease;
  animation: ${props => props.$isListening ? 'pulse 1.5s infinite' : 'none'};
  
  @keyframes pulse {
    0% { transform: scale(1); }
    50% { transform: scale(1.1); }
    100% { transform: scale(1); }
  }
  
  &:hover {
    color: ${props => props.$isListening ? 'rgba(255, 89, 89, 1)' : 'rgba(255, 255, 255, 1)'};
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
  z-index: 100;
  backdrop-filter: blur(5px);
  
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

const PulseDot = styled.div`
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  width: 8px;
  height: 8px;
  background-color: #ff5959;
  border-radius: 50%;
  animation: pulse 1.5s infinite;
  display: ${props => props.$isVisible ? 'block' : 'none'};
`;

const SearchBar = ({ isResults, initialQuery, onSearch }) => {
  const [query, setQuery] = useState(initialQuery || '');
  const [isListening, setIsListening] = useState(false);
  const [transcript, setTranscript] = useState('');
  const recognitionRef = useRef(null);
  const navigate = useNavigate();
  const location = useLocation();
  const isHomePage = location.pathname === '/';
  const API_BASE_URL = 'http://localhost:8080/api';
  
  // Get query from URL if on results page
  useEffect(() => {
    if (!isHomePage && initialQuery) {
      setQuery(initialQuery);
    }
  }, [initialQuery, isHomePage]);
  
  // Clean up recognition on unmount
  useEffect(() => {
    return () => {
      if (recognitionRef.current) {
        try {
          recognitionRef.current.stop();
        } catch (e) {
          // Recognition may already be stopped
        }
      }
    };
  }, []);
  
  const handleSearch = async (e, isVoiceSearch = false) => {
    if (e) e.preventDefault();
    if (query.trim()) {
      try {
        if (isVoiceSearch) {
          // Use the voice search endpoint
          await axios.get(`${API_BASE_URL}/voice-search?query=${encodeURIComponent(query)}`);
        }
        
        if (onSearch) {
          // Use the provided onSearch handler if available (for result page)
          onSearch(query);
        } else {
          // Default navigation behavior for home page
          navigate(`/search?q=${encodeURIComponent(query)}`);
        }
      } catch (error) {
        console.error('Search error:', error);
        // If voice search API fails, still perform regular search
        if (onSearch) {
          onSearch(query);
        } else {
          navigate(`/search?q=${encodeURIComponent(query)}`);
        }
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
      startVoiceRecognition();
    } else {
      stopVoiceRecognition();
    }
  };
  
  const startVoiceRecognition = () => {
    setIsListening(true);
    setTranscript('');
    
    if ('webkitSpeechRecognition' in window || 'SpeechRecognition' in window) {
      // Use the appropriate recognition API
      const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
      recognitionRef.current = new SpeechRecognition();
      
      recognitionRef.current.continuous = false;
      recognitionRef.current.interimResults = true;
      recognitionRef.current.lang = 'en-US';
      
      recognitionRef.current.onstart = () => {
        setIsListening(true);
      };
      
      recognitionRef.current.onresult = (event) => {
        const current = event.resultIndex;
        const result = event.results[current][0].transcript;
        setTranscript(result);
      };
      
      recognitionRef.current.onerror = (event) => {
        console.error('Speech recognition error:', event.error);
        setIsListening(false);
      };
      
      recognitionRef.current.onend = () => {
        if (transcript) {
          setQuery(transcript);
          setIsListening(false);
          
          // Short delay to allow user to see what was transcribed
          setTimeout(() => {
            handleSearch(null, true); // Pass true to indicate voice search
          }, 1000);
        } else {
          setIsListening(false);
        }
      };
      
      recognitionRef.current.start();
    } else {
      alert('Speech recognition is not supported in your browser. Try Chrome or Edge.');
      setIsListening(false);
    }
  };
  
  const stopVoiceRecognition = () => {
    if (recognitionRef.current) {
      try {
        recognitionRef.current.stop();
      } catch (e) {
        // Recognition may already be stopped
      }
    }
    setIsListening(false);
  };

  return (
    <SearchContainer $isHomePage={isHomePage}>
      <SearchForm as="form" onSubmit={handleSearch}>
        <Input 
          type="text"
          placeholder={isHomePage ? 'Search... (Use "quotes" for exact phrases, AND/OR/NOT for operators)' : 'Search...'}
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
          $isListening={isListening}
          aria-label={isListening ? "Stop voice input" : "Start voice input"}
        >
          🎤
          <PulseDot $isVisible={isListening} />
        </VoiceButton>
        <SearchButton type="submit" aria-label="Search">
          🔍
        </SearchButton>
      </SearchForm>
      
      <VoiceRecognition $show={isListening}>
        <p>Listening... speak now</p>
        {transcript && <h3>{transcript}</h3>}
      </VoiceRecognition>
    </SearchContainer>
  );
};

export default SearchBar;