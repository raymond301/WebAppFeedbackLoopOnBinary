FROM node:20-alpine as build
WORKDIR /app
COPY package.json vite.config.js index.html ./
COPY src ./src
RUN npm install && npm run build
FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
