@echo off
cd /d %~dp0

if not exist venv (
    echo [1/3] Creating isolated venv (avoids numpy conflict with tensorflow)...
    python -m venv venv
)

call venv\Scripts\activate.bat

echo [2/3] Installing OCR deps (paddlepaddle + paddleocr + fastapi)...
pip install -r requirements.txt

echo [3/3] Starting PaddleOCR service on port 8866...
python ocr_server.py
