FROM node:22.22.2-alpine3.22

WORKDIR /app

COPY --chown=node:node package.json package-lock.json ./
RUN chown node:node /app
USER node

RUN npm ci

COPY --chown=node:node . .

EXPOSE 5173

ENTRYPOINT ["sh", "/app/entrypoint.sh"]
