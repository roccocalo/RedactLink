"""
Custom Presidio analyzer server.
Identical HTTP contract to the official image (/analyze, /health),
but the AnalyzerEngine is initialized with Italian PII recognizers added to the registry.
"""
import logging
from flask import Flask, request, jsonify
from presidio_analyzer import AnalyzerEngine, RecognizerRegistry
from italian_recognizers import build_italian_recognizers

logging.basicConfig(level=logging.INFO)
log = logging.getLogger(__name__)

log.info("Loading NLP model and building recognizer registry...")
registry = RecognizerRegistry()
registry.load_predefined_recognizers()
for recognizer in build_italian_recognizers():
    registry.add_recognizer(recognizer)
    log.info("Registered recognizer: %s", recognizer.supported_entities)

engine = AnalyzerEngine(registry=registry)
log.info("Presidio analyzer ready.")

app = Flask(__name__)


@app.route("/analyze", methods=["POST"])
def analyze():
    body = request.get_json(force=True)
    text = body.get("text", "")
    language = body.get("language", "en")
    results = engine.analyze(text=text, language=language)
    return jsonify([{
        "entity_type": r.entity_type,
        "start":       r.start,
        "end":         r.end,
        "score":       r.score,
    } for r in results])


@app.route("/health")
def health():
    return jsonify({"status": "ok"})


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=3000)
