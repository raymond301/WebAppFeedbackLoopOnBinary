FROM node:20-alpine as build
WORKDIR /app
ARG VITE_API_BASE
ENV VITE_API_BASE=$VITE_API_BASE
COPY package.json vite.config.js index.html ./
COPY src ./src
RUN npm install && npm run build
FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
