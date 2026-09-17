FROM node:18-alpine AS build
WORKDIR /build

COPY package.json package-lock.json ./
# Esta etapa só compila o bundle do Angular: `npm run build` é `ng build`, e mais nada.
#
# Os dois navegadores que o `npm ci` baixaria aqui somam mais de 400 MB numa camada que é
# descartada em seguida, e nenhum dos dois é usado nesta imagem:
#  - o Chromium do puppeteer existe para dar um CHROME_BIN ao karma (ver karma.conf.js), e a
#    suíte unitária roda na CI, não dentro do contêiner;
#  - o binário do Cypress nem rodaria aqui — a imagem é Alpine (musl) e o binário é glibc —,
#    e a suíte end-to-end roda fora do contêiner, contra a pilha já de pé.
#
# Isso passava despercebido enquanto esta camada ficava em cache; a primeira mudança em
# package.json a invalida e os dois downloads reaparecem inteiros.
ENV CYPRESS_INSTALL_BINARY=0
ENV PUPPETEER_SKIP_DOWNLOAD=1
RUN npm ci --no-audit --no-fund

COPY . .
RUN npm run build

FROM nginx:alpine
RUN apk add --no-cache wget
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist/securityhub-frontend /usr/share/nginx/html

RUN chown -R nginx:nginx /usr/share/nginx/html /var/cache/nginx /var/log/nginx \
 && touch /var/run/nginx.pid \
 && chown nginx:nginx /var/run/nginx.pid

USER nginx
EXPOSE 8080
CMD ["nginx", "-g", "daemon off;"]
