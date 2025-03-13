import React, { useState } from 'react';
import styled from 'styled-components';

const HeaderStyled = styled.header`
  display: flex;
  justify-content: space-between;
  padding: 20px;
`;

const Logo = styled.div`
  font-size: 36px;
  font-weight: bold;
  color: #9b59b6;
  text-shadow: 0 0 10px rgba(155, 89, 182, 0.8);
`;

const ThemeToggle = styled.div`
  cursor: pointer;
  font-size: 24px;
`;

const Header = () => {
  const [isDark, setIsDark] = useState(true);

  const toggleTheme = () => {
    setIsDark(!isDark);
    document.body.style.background = isDark
      ? 'linear-gradient(135deg, #fff, #e0e0e0)'
      : 'linear-gradient(135deg, #1a0b2e, #2e1a47)';
  };

  return (
    <HeaderStyled>
      <ThemeToggle onClick={toggleTheme}>{isDark ? '☾' : '☀'}</ThemeToggle>
    </HeaderStyled>
  );
};

export default Header;