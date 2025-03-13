import React from 'react';
import styled from 'styled-components';

const FooterStyled = styled.footer`
  position: fixed;
  bottom: 20px;
  width: 100%;
  text-align: center;
  color: rgba(255, 255, 255, 0.5);
`;

const Links = styled.div`
  margin-top: 10px;
  a {
    color: rgba(255, 255, 255, 0.5);
    text-decoration: none;
    margin: 0 10px;
  }
`;

const Footer = () => (
  <FooterStyled>
    <p>Search Beyond the Stars</p>
    <Links>
      <a href="#">About</a> | <a href="#">Privacy</a> | <a href="#">Tools</a>
    </Links>
  </FooterStyled>
);

export default Footer;