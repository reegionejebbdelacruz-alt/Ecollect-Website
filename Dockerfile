# Ecollect on Render.com (or any Docker host)
# Uses only a Java runtime — no build step, no dependencies.
FROM eclipse-temurin:21-jdk
WORKDIR /app
# Copy everything in the repo (Main.java + hero.jpg / hero.png when present).
# Banners fall back to a green gradient when no photo is included.
COPY . ./
EXPOSE 8080
# Render injects $PORT; listen on it (default 8080 for local runs).
CMD ["sh", "-c", "java Main.java ${PORT:-8080}"]
