// components/Searchbar.js
import React, { useState } from 'react';
import styled from 'styled-components';
import { useNavigate } from 'react-router-dom';

const SearchContainer = styled.div`
  position: absolute;
  top: 25%;
  left: 50%;
  transform: translate(-50%, -50%);
  text-align: center;
`;

const Input = styled.input`
  width: 400px;
  padding: 15px;
  border: none;
  border-radius: 25px;
  background: rgba(255, 255, 255, 0.1);
  color: #fff;
  font-size: 18px;
  outline: none;
  transition: width 0.3s ease, box-shadow 0.3s ease;

  &:focus {
    width: 500px;
    box-shadow: 0 0 15px rgba(155, 89, 182, 0.8);
  }
`;

const Button = styled.button`
  padding: 10px 20px;
  margin-left: 10px;
  background: #9b59b6;
  border: none;
  border-radius: 25px;
  color: #fff;
  cursor: pointer;
  transition: transform 0.2s ease;

  &:hover {
    transform: scale(1.1);
  }
`;

const Autocomplete = styled.div`
  position: absolute;
  width: 100%;
  background: rgba(255, 255, 255, 0.2);
  border-radius: 0 0 10px 10px;
  color: #fff;
  display: ${props => (props.visible ? 'block' : 'none')};
`;

const SearchBar = () => {
  const [query, setQuery] = useState('');
  const [isFocused, setIsFocused] = useState(false);
  const navigate = useNavigate();

  const handleSearch = () => {
    if (query.trim()) {
      navigate(`/search?q=${encodeURIComponent(query)}`);
    }
  };

  const handleKeyPress = (e) => {
    if (e.key === 'Enter' && query.trim()) {
      navigate(`/search?q=${encodeURIComponent(query)}`);
    }
  };

  return (
    <SearchContainer>
      <Input
        type="text"
        placeholder="Search..."
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        onFocus={() => setIsFocused(true)}
        onBlur={() => setIsFocused(false)}
        onKeyPress={handleKeyPress}
      />
      <Button onClick={handleSearch}>Go</Button>
      <Autocomplete visible={isFocused && query.length > 0}>
        <p>Suggestion 1</p>
        <p>Suggestion 2</p>
      </Autocomplete>
    </SearchContainer>
  );
};

export default SearchBar; // Changed from SearchResults to SearchBar