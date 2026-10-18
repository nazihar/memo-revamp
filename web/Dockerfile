# syntax=docker/dockerfile:1
# Web image (ADR-0012): nginx-unprivileged serving the SPA with the strict security headers from
# web/security-headers.json, and proxying /api/ to the backend (BACKEND_URL).
# Build context: repository root. CI builds web/dist first (pnpm --filter web build).
#   docker build -f web/Dockerfile -t memo-web .

ARG NODE_IMAGE=node:24.21.0-alpine3.24@sha256:ebfe2f90462722a7a4de65e91990e97fe0d401c70e0e762c5b53302f905ec1c1
ARG RUNTIME_IMAGE=nginxinc/nginx-unprivileged:1.31.6-alpine3.24@sha256:884e0b7f42f303641b0f4e78755aea647521dca0b89ca3be7992ad78b5c07434

FROM ${NODE_IMAGE} AS headers
WORKDIR /work
COPY web/security-headers.json web/nginx/render-headers.mjs ./
RUN node render-headers.mjs security-headers.json > security-headers.conf

FROM ${RUNTIME_IMAGE}
ARG VERSION=dev
ARG REVISION=unknown
LABEL org.opencontainers.image.title="memo-web" \
      org.opencontainers.image.source="https://github.com/nazihar/memo-revamp" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}" \
      org.opencontainers.image.licenses="LicenseRef-Proprietary"
# Security fix published after the pinned base was built (libexpat CVE-2026-77214, fixed in 2.9.0-r0). A no-op once
# the base image ships it; remove then (Renovate's container-image update will show it).
USER root
RUN apk upgrade --no-cache libexpat
COPY --chown=101:101 web/dist/ /usr/share/nginx/html/
COPY web/nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=headers /work/security-headers.conf /etc/nginx/snippets/security-headers.conf
# Only BACKEND_URL is substituted into the template; nginx's own $variables are left alone.
ENV BACKEND_URL=http://backend:8080 \
    NGINX_ENVSUBST_FILTER=^BACKEND_URL$
USER 101
EXPOSE 8080
