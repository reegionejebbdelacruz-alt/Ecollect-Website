# Ecollect on Render.com (or any Docker host)
# Uses only a Java runtime — no build step, no dependencies.
FROM eclipse-temurin:21-jdk
WORKDIR /app
# NOTE: add hero.png / hero.jpg here too if the file exists in your repo
# (banners fall back to a green gradient when no photo is present).
COPY Main.java ./
EXPOSE 8080
# Render injects $PORT; listen on it (default 8080 for local runs).
CMD ["sh", "-c", "java Main.java ${PORT:-8080}"]
