# Web image: builds the Angular app and serves it with nginx, which is also the
# single public origin proxying /api/ to the API service.
# Build context is the repository root.
#
# Base images are pinned by tag and digest (docs/adr/0005-version-pins.md); Dependabot proposes
# updates of both.
#
# The "runtime" stage is the nginx part alone; infra/nginx/check-routing.sh builds and checks it
# (docker build --target runtime) without the web build.

FROM nginx:1.31.0-alpine@sha256:2f07d83bf561b506400dc183b1b2003803e39efbd22451f848adaba14d28c7c7 AS runtime
ENV VITADELTA_TLS=false \
    VITADELTA_REAL_IP_FROM= \
    NGINX_ENVSUBST_FILTER=^VITADELTA_
# nginx runs as the image's unprivileged "nginx" user (uid 101), master included. It listens on
# 8080, keeps its pid and temporary files under /tmp (nginx.conf), and the entrypoint renders the
# templates into /etc/nginx/conf.d, and the real-ip entrypoint script writes
# /etc/nginx/generated/real-ip.conf; both are therefore owned by that user.
RUN rm -f /etc/nginx/conf.d/default.conf \
    && mkdir -p /etc/nginx/generated \
    && : > /etc/nginx/generated/real-ip.conf \
    && chown -R nginx:nginx /etc/nginx/conf.d /etc/nginx/generated
COPY infra/nginx/nginx.conf /etc/nginx/nginx.conf
COPY --chmod=755 infra/nginx/entrypoint.d/ /docker-entrypoint.d/
COPY infra/nginx/snippets/ /etc/nginx/snippets/
COPY infra/nginx/templates/ /etc/nginx/templates/
USER nginx
EXPOSE 8080

FROM node:22.23.5-alpine@sha256:1314fb30ec37592d5845e5a160d522c0e680972aa534549940b8d73bde88481a AS build
WORKDIR /src/apps/web
COPY apps/web/package.json apps/web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY contracts/ /src/contracts/
COPY apps/web/ ./
RUN npm run build

FROM runtime
COPY --from=build /src/apps/web/dist/web/browser/ /usr/share/nginx/html/
