// App.js
import React, { useEffect } from 'react';
import styled from 'styled-components';
import { BrowserRouter as Router, Route, Routes, useLocation } from 'react-router-dom';
import SearchBar from './components/Searchbar';
import Footer from './components/Footer';
import SearchResults from './components/SearchResults';

const AppContainer = styled.div`
  min-height: 100vh;
  background: radial-gradient(circle, #1a0b2e, #0d061a);
  position: relative;
  overflow: hidden;
`;

const Title = styled.h1`
  color: #ffffff;
  text-align: center;
  font-size: 2.5rem;
  text-shadow: 0 0 10px rgba(155, 89, 182, 0.7);
  z-index: 1;
  position: relative;
`;

const MainPage = () => {
  const location = useLocation();

  useEffect(() => {
    // Clear existing particles canvas if it exists
    const existingCanvas = document.getElementById('particles-js').querySelector('canvas');
    if (existingCanvas) {
      existingCanvas.remove();
    }

    // Initialize particles
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
  }, [location.pathname]); // Re-run effect when pathname changes

  return (
    <AppContainer id="particles-js">
      <Title>Search Engine</Title>
      <SearchBar />
      <Footer />
    </AppContainer>
  );
};

const App = () => {
  return (
    <Router>
      <Routes>
        <Route path="/" element={<MainPage />} />
        <Route path="/search" element={<SearchResults />} />
      </Routes>
    </Router>
  );
};

export default App;