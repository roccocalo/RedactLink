"""
Custom Italian PII recognizers for Presidio.
Each class handles one entity type; checksum-validated ones subclass PatternRecognizer
and override validate_result() so invalid matches are quietly discarded.
"""
import re
from typing import Optional
from presidio_analyzer import PatternRecognizer, Pattern


# ── Codice Fiscale ─────────────────────────────────────────────────────────────
# 16-char alphanumeric with a specific structure and a check-character algorithm.

_CF_ODD = {
    '0': 1,  '1': 0,  '2': 5,  '3': 7,  '4': 9,  '5': 13, '6': 15, '7': 17,
    '8': 19, '9': 21, 'A': 1,  'B': 0,  'C': 5,  'D': 7,  'E': 9,  'F': 13,
    'G': 15, 'H': 17, 'I': 19, 'J': 21, 'K': 2,  'L': 4,  'M': 18, 'N': 20,
    'O': 11, 'P': 3,  'Q': 6,  'R': 8,  'S': 12, 'T': 14, 'U': 16, 'V': 10,
    'W': 22, 'X': 25, 'Y': 24, 'Z': 23,
}
_CF_EVEN = {**{str(i): i for i in range(10)},
            **{chr(ord('A') + i): i for i in range(26)}}


def _validate_codice_fiscale(cf: str) -> bool:
    cf = cf.upper()
    if len(cf) != 16:
        return False
    total = sum(
        _CF_ODD[c] if i % 2 == 0 else _CF_EVEN.get(c, 0)
        for i, c in enumerate(cf[:15])
    )
    return cf[15] == chr(ord('A') + total % 26)


class CodiceFiscaleRecognizer(PatternRecognizer):
    def __init__(self):
        super().__init__(
            supported_entity="IT_CODICE_FISCALE",
            patterns=[Pattern(
                "codice_fiscale",
                r"\b[A-Za-z]{6}\d{2}[A-Za-z]\d{2}[A-Za-z]\d{3}[A-Za-z]\b",
                score=0.5,
            )],
            context=["codice fiscale", "c.f.", "codice", "fiscale", "cf"],
        )

    def validate_result(self, pattern_text: str) -> Optional[bool]:
        return _validate_codice_fiscale(pattern_text)


# ── Partita IVA ────────────────────────────────────────────────────────────────
# 11 digits; last digit is a Luhn-like check digit.

def _validate_partita_iva(raw: str) -> bool:
    digits = re.sub(r'\D', '', raw)
    if len(digits) != 11:
        return False
    total = 0
    for i, d in enumerate(digits[:10]):
        v = int(d)
        if i % 2 == 1:      # even 1-indexed position → double
            v *= 2
            if v > 9:
                v -= 9
        total += v
    return (10 - total % 10) % 10 == int(digits[10])


class PartitaIvaRecognizer(PatternRecognizer):
    def __init__(self):
        super().__init__(
            supported_entity="IT_PARTITA_IVA",
            patterns=[
                Pattern("piva_prefixed", r"\bIT\d{11}\b", score=0.6),
                Pattern("piva_bare",     r"\b\d{11}\b",   score=0.3),
            ],
            context=["partita iva", "p.iva", "piva", "iva", "partita"],
        )

    def validate_result(self, pattern_text: str) -> Optional[bool]:
        return _validate_partita_iva(pattern_text)


# ── Italian ID card ────────────────────────────────────────────────────────────
# Old paper: AA1234567  |  New electronic (CIE): CA00000AA

class ItalianIdCardRecognizer(PatternRecognizer):
    def __init__(self):
        super().__init__(
            supported_entity="IT_ID_CARD",
            patterns=[
                Pattern("id_paper",       r"\b[A-Z]{2}\d{7}\b",      score=0.4),
                Pattern("id_electronic",  r"\b[A-Z]{2}\d{5}[A-Z]{2}\b", score=0.4),
            ],
            context=["carta d'identità", "carta identità", "documento", "c.i.", "cie",
                     "carta di identità"],
        )


# ── Italian Passport ───────────────────────────────────────────────────────────
# Same format as old ID card — context words are what differentiate them.

class ItalianPassportRecognizer(PatternRecognizer):
    def __init__(self):
        super().__init__(
            supported_entity="IT_PASSPORT",
            patterns=[Pattern("passport", r"\b[A-Z]{2}\d{7}\b", score=0.4)],
            context=["passaporto", "passport", "numero passaporto"],
        )


# ── Driver's licence (Patente di Guida) ───────────────────────────────────────
# Format: U1A123456B — 10 chars, alphanumeric.

class ItalianDriversLicenceRecognizer(PatternRecognizer):
    def __init__(self):
        super().__init__(
            supported_entity="IT_DRIVERS_LICENCE",
            patterns=[Pattern("patente", r"\b[A-Z]\d[A-Z]\d{6}[A-Z]\b", score=0.5)],
            context=["patente", "patente di guida", "licenza di guida"],
        )


# ── Italian IBAN ───────────────────────────────────────────────────────────────
# IT + 2 check digits + 1 CIN letter + 10 digits + 12 alphanumeric = 27 chars.

class ItalianIbanRecognizer(PatternRecognizer):
    def __init__(self):
        super().__init__(
            supported_entity="IT_IBAN",
            patterns=[Pattern(
                "it_iban",
                r"\bIT\d{2}[A-Z]\d{10}[0-9A-Z]{12}\b",
                score=0.85,
            )],
            context=["iban", "conto corrente", "banca", "bonifico", "coordinate bancarie"],
        )


# ── Tessera Sanitaria ──────────────────────────────────────────────────────────
# 20-digit number on the back of the national health card.

class TesseraSanitariaRecognizer(PatternRecognizer):
    def __init__(self):
        super().__init__(
            supported_entity="IT_TESSERA_SANITARIA",
            patterns=[Pattern("tessera_sanitaria", r"\b\d{20}\b", score=0.4)],
            context=["tessera sanitaria", "team", "numero tessera",
                     "servizio sanitario nazionale", "ssn"],
        )


# ── Targa automobilistica ──────────────────────────────────────────────────────
# Current: AA123BB  |  Old provincial: MI1234567

class TargaRecognizer(PatternRecognizer):
    def __init__(self):
        super().__init__(
            supported_entity="IT_TARGA",
            patterns=[
                Pattern("targa_current", r"\b[A-Z]{2}\d{3}[A-Z]{2}\b", score=0.5),
                Pattern("targa_old",     r"\b[A-Z]{2}\d{4,7}\b",       score=0.3),
            ],
            context=["targa", "veicolo", "auto", "automobile", "moto", "motociclo"],
        )


# ── Italian phone numbers ──────────────────────────────────────────────────────
# Mobile: +39 3XX XXXXXXX  |  Landline: +39 0X XXXXXXXX

class ItalianPhoneRecognizer(PatternRecognizer):
    def __init__(self):
        super().__init__(
            supported_entity="IT_PHONE_NUMBER",
            patterns=[
                Pattern("it_mobile_intl",   r"\+39\s?3\d{2}\s?\d{6,7}",    score=0.85),
                Pattern("it_mobile",        r"\b3\d{2}\s?\d{6,7}\b",        score=0.6),
                Pattern("it_landline_intl", r"\+39\s?0\d{1,3}\s?\d{5,8}",  score=0.85),
                Pattern("it_landline",      r"\b0\d{1,3}\s?\d{5,8}\b",      score=0.5),
            ],
            context=["telefono", "cellulare", "numero", "tel", "cell", "mobile", "recapito"],
        )


# ── Italian addresses ──────────────────────────────────────────────────────────

class ItalianAddressRecognizer(PatternRecognizer):
    def __init__(self):
        super().__init__(
            supported_entity="IT_ADDRESS",
            patterns=[Pattern(
                "it_address",
                r"\b(Via|Viale|Piazza|Corso|Largo|Vicolo|Contrada|Strada|"
                r"Lungotevere|Lungarno|Borgo)\s+[A-Z][a-zA-Z\s'\.]{2,40},?\s*\d+[/\w]*",
                score=0.6,
            )],
            context=["indirizzo", "residente", "domicilio", "sede", "abitazione"],
        )


# ── PEC (Posta Elettronica Certificata) ───────────────────────────────────────
# Legally binding certified email — *@pec.* or *@*.pec.it

class PecRecognizer(PatternRecognizer):
    def __init__(self):
        super().__init__(
            supported_entity="IT_PEC",
            patterns=[
                Pattern("pec_domain",    r"\b[\w.+-]+@pec\.[\w.-]+\b",       score=0.85),
                Pattern("pec_subdomain", r"\b[\w.+-]+@[\w.-]+\.pec\.it\b",   score=0.85),
            ],
            context=["pec", "posta certificata", "posta elettronica certificata"],
        )


# ── CAP (Italian postal code) ──────────────────────────────────────────────────
# 5 digits — very generic, only reports with strong context words nearby.

class ItalianCapRecognizer(PatternRecognizer):
    def __init__(self):
        super().__init__(
            supported_entity="IT_CAP",
            patterns=[Pattern("cap", r"\b\d{5}\b", score=0.2)],
            context=["cap", "codice postale", "c.a.p.", "c.p."],
        )


# ── public API ─────────────────────────────────────────────────────────────────

def build_italian_recognizers() -> list:
    return [
        CodiceFiscaleRecognizer(),
        PartitaIvaRecognizer(),
        ItalianIdCardRecognizer(),
        ItalianPassportRecognizer(),
        ItalianDriversLicenceRecognizer(),
        ItalianIbanRecognizer(),
        TesseraSanitariaRecognizer(),
        TargaRecognizer(),
        ItalianPhoneRecognizer(),
        ItalianAddressRecognizer(),
        PecRecognizer(),
        ItalianCapRecognizer(),
    ]
