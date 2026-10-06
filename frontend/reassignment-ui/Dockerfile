# ---- Build ----
FROM node:20-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npx ng build --configuration production

# ---- Serve ----
FROM nginx:1.27-alpine
# The official image renders templates/*.template into conf.d with envsubst at start-up
ENV BACKEND_URL=http://backend:8080
ENV NGINX_ENVSUBST_FILTER=^BACKEND_URL$
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist/reassignment-ui/browser /usr/share/nginx/html
EXPOSE 80
