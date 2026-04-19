FROM python:3.11-slim

# gcc needed by some presidio/spacy C extensions
RUN apt-get update && apt-get install -y --no-install-recommends gcc && \
    rm -rf /var/lib/apt/lists/*

RUN pip install --no-cache-dir presidio-analyzer flask

# en_core_web_sm = 12 MB, fast build, good for dev
# swap to en_core_web_lg = ~800 MB for production accuracy
RUN python -m spacy download en_core_web_lg

WORKDIR /app
COPY italian_recognizers.py server.py ./

EXPOSE 3000
CMD ["python", "server.py"]
