# Analytics pipeline image: Python 3.12 + OpenJDK 17 (Debian bookworm) + PySpark 4.1.1 in local mode.
# Spark 4.1.1 documents "Java 17/21, Scala 2.13, Python 3.10+".
FROM python:3.12.12-slim-bookworm AS runtime

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    TZ=UTC \
    JAVA_HOME=/opt/java \
    ANALYTICS_PARQUET_DIR=/data/parquet

RUN apt-get update \
    && apt-get install -y --no-install-recommends openjdk-17-jre-headless procps \
    && rm -rf /var/lib/apt/lists/* \
    && ln -s "$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")" /opt/java

WORKDIR /app
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

COPY yousong_analytics ./yousong_analytics

# Private Parquet volume, owned by an unprivileged user.
RUN useradd --create-home --uid 10001 analytics \
    && mkdir -p /data/parquet \
    && chown -R analytics:analytics /data /app
USER analytics
VOLUME ["/data/parquet"]

ENTRYPOINT ["python", "-m", "yousong_analytics"]
CMD ["loop"]

# Test image: same runtime plus pytest and the test suite.
FROM runtime AS test
USER root
COPY requirements-dev.txt ./
RUN pip install --no-cache-dir -r requirements-dev.txt
COPY tests ./tests
COPY pytest.ini ./
RUN chown -R analytics:analytics /app
USER analytics
ENTRYPOINT ["python", "-m", "pytest"]
CMD ["-q"]
