# Stage 1: Build the React application
FROM node:18-alpine as build
WORKDIR /app
# Copy package.json and package-lock.json
COPY package*.json ./
# Install dependencies - using regular install instead of ci
# to handle mismatches between package.json and package-lock.json
RUN npm install
# Copy the rest of the application code
COPY . .
# Set the API URL environment variable
ENV REACT_APP_API_URL=http://backend:8080/api
# Build the application for production
RUN npm run build

# Stage 2: Serve the application with Nginx
FROM nginx:alpine
# Copy the build output to replace the default nginx content
COPY --from=build /app/build /usr/share/nginx/html
# Copy the nginx configuration
COPY nginx.conf /etc/nginx/conf.d/default.conf
# Expose port 80
EXPOSE 80
# Run nginx
CMD ["nginx", "-g", "daemon off;"]
