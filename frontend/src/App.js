// App.js
import React from 'react';
import styled from 'styled-components';
import { BrowserRouter as Router, Route, Routes } from 'react-router-dom';
import SearchBar from './components/Searchbar';
import Footer from './components/Footer';
import SearchResults from './components/SearchResults';

const AppContainer = styled.div`
  min-height: 100vh;
  background: radial-gradient(circle, #1a0b2e, #0d061a);
  position: relative;
  overflow: hidden;
`;

const MainContent = styled.div`
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  padding: 0 20px;
  position: relative;
  z-index: 1;
`;

const LogoContainer = styled.div`
  margin-bottom: 40px;
  text-align: center;
`;

const Logo = styled.h1`
  font-size: 4rem;
  background: linear-gradient(to right, #9b59b6, #3498db);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  margin: 0;
  letter-spacing: 2px;
  text-shadow: 0 0 10px rgba(155, 89, 182, 0.3);
`;

const Tagline = styled.p`
  color: rgba(255, 255, 255, 0.7);
  font-size: 1.2rem;
  margin-top: 10px;
`;

const SearchTips = styled.div`
  max-width: 600px;
  margin-top: 30px;
  padding: 15px;
  background: rgba(255, 255, 255, 0.05);
  border-radius: 10px;
  color: rgba(255, 255, 255, 0.7);
  font-size: 0.9rem;
  text-align: center;
`;

const OperatorExample = styled.span`
  background: rgba(155, 89, 182, 0.2);
  padding: 3px 6px;
  border-radius: 4px;
  font-family: monospace;
  margin: 0 3px;
`;

const HomePage = () => {
  return (
    <MainContent>
      <LogoContainer>
        <Logo>Search Engine</Logo>
        <Tagline>Find what you're looking for</Tagline>
      </LogoContainer>
      <SearchBar isResults={false} />
      <SearchTips>
        <p>
          Try our new feature! Combine phrases with operators:
          <br />
          <OperatorExample>"football player" AND "championship"</OperatorExample>
          <OperatorExample>"pizza recipe" OR "pasta recipe"</OperatorExample>
          <OperatorExample>"smartphone review" NOT "budget"</OperatorExample>
        </p>
      </SearchTips>
    </MainContent>
  );
};

const App = () => {
  return (
    <Router>
      <AppContainer>
      <Routes>
          <Route path="/" element={<HomePage />} />
        <Route path="/search" element={<SearchResults />} />
      </Routes>
      </AppContainer>
    </Router>
  );
};

export default App;