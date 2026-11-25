# Web image: builds the Angular app and serves it with nginx, which is also the
# single public origin proxying /api/ to the API service.
# Build context is the repository root.

FROM node:22.23.5-alpine AS build
WORKDIR /src/apps/web
COPY apps/web/package.json apps/web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY contracts/ /src/contracts/
COPY apps/web/ ./
RUN npm run build

FROM nginx:1.30.8-alpine
ENV VITADELTA_TLS=false \
    VITADELTA_REAL_IP_FROM= \
    NGINX_ENVSUBST_FILTER=^VITADELTA_
RUN rm -f /etc/nginx/conf.d/default.conf \
    && mkdir -p /etc/nginx/generated \
    && : > /etc/nginx/generated/real-ip.conf
COPY infra/nginx/nginx.conf /etc/nginx/nginx.conf
COPY --chmod=755 infra/nginx/entrypoint.d/ /docker-entrypoint.d/
COPY infra/nginx/snippets/ /etc/nginx/snippets/
COPY infra/nginx/templates/ /etc/nginx/templates/
COPY --from=build /src/apps/web/dist/web/browser/ /usr/share/nginx/html/
EXPOSE 8080
