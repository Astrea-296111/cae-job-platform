FROM node:22.20.0-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN --mount=type=secret,id=build_proxy --mount=type=secret,id=tls_ca \
    sh -c 'if [ -s /run/secrets/build_proxy ]; then export HTTPS_PROXY="$(cat /run/secrets/build_proxy)" HTTP_PROXY="$(cat /run/secrets/build_proxy)"; fi; if [ -s /run/secrets/tls_ca ]; then export NODE_EXTRA_CA_CERTS=/run/secrets/tls_ca; fi; npm ci --no-audit --no-fund'
COPY . .
RUN npm run build

FROM nginx:1.28.0-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
HEALTHCHECK --interval=15s --timeout=3s --start-period=5s --retries=3 CMD wget -Y off -q -O - http://127.0.0.1/health || exit 1
