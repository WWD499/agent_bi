@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0" || (echo [FATAL] Cannot cd to script dir & pause & exit /b 1)

set "LOG=%~dp0install_ocr.log"
set "PY="
echo ===== install_ocr.bat started %date% %time% ===== > "%LOG%"
echo [INFO] Working dir: %CD% >> "%LOG%"

REM ===== 1) Detect a PaddleOCR-compatible Python (3.11/3.12; 3.13 is NOT supported) =====
for %%P in (
    "D:\Program Files\anaconda3\python.exe"
    "C:\ProgramData\Anaconda3\python.exe"
    "C:\Users\%USERNAME%\anaconda3\python.exe"
    "C:\Python312\python.exe"
    "C:\Python311\python.exe"
) do (
    if exist %%P (
        set "PY=%%P"
        goto :check_py
    )
)
for /f "tokens=2" %%V in ('python --version 2^>^&1') do set "PV=%%V"
for /f "tokens=1,2 delims=." %%A in ("!PV!") do set "MAJ=%%A" & set "MIN=%%B"
if defined MAJ if "!MAJ!"=="3" if !MIN! GEQ 11 if !MIN! LEQ 12 (
    set "PY=python"
    goto :check_py
)
echo [ERROR] No Python 3.11/3.12 found. PaddleOCR 2.7.3 does NOT support 3.13. >> "%LOG%"
echo [ERROR] No Python 3.11/3.12 found. PaddleOCR 2.7.3 does NOT support 3.13.
echo         Install Python 3.11 from https://www.python.org/downloads/ and add to PATH, then re-run.
echo         Current "python" on PATH:
python --version 2>&1
echo         See %LOG% for details.
pause
exit /b 1

:check_py
echo [0/3] Using Python: %PY% >> "%LOG%"
echo [0/3] Using Python: %PY%
%PY% --version >> "%LOG%" 2>&1

REM ===== 2) Create / fix venv (rebuild if existing venv is the wrong Python version) =====
set "REBUILD=0"
if not exist venv set "REBUILD=1"
if exist venv (
    for /f "tokens=2" %%V in ('venv\Scripts\python.exe --version 2^>^&1') do set "VPV=%%V"
    for /f "tokens=1,2 delims=." %%A in ("!VPV!") do set "VMAJ=%%A" & set "VMIN=%%B"
    if not "!VMAJ!"=="3" set "REBUILD=1"
    if !VMIN! LSS 11 set "REBUILD=1"
    if !VMIN! GTR 12 set "REBUILD=1"
)
if "!REBUILD!"=="1" (
    if exist venv (
        echo [1/3] Removing incompatible venv, was !VMAJ!.!VMIN!, rebuilding with 3.11/3.12... >> "%LOG%"
        echo [1/3] Removing incompatible venv, was !VMAJ!.!VMIN!, rebuilding with 3.11/3.12...
        rmdir /s /q venv >> "%LOG%" 2>&1
    ) else (
        echo [1/3] Creating venv... >> "%LOG%"
        echo [1/3] Creating venv...
    )
    %PY% -m venv venv >> "%LOG%" 2>&1 || (echo [ERROR] venv creation failed. See %LOG% & pause & exit /b 1)
)
call "%~dp0venv\Scripts\activate.bat" || (echo [ERROR] venv activate failed & pause & exit /b 1)
echo [1/3] venv ready: >> "%LOG%"
venv\Scripts\python.exe --version >> "%LOG%" 2>&1

REM ===== 3) Install dependencies =====
echo [2/3] Installing OCR deps (paddlepaddle + paddleocr + fastapi). This may take a few minutes... >> "%LOG%"
echo [2/3] Installing OCR deps (paddlepaddle + paddleocr + fastapi). This may take a few minutes...
python -m pip install --upgrade pip >> "%LOG%" 2>&1
pip install -r "%~dp0requirements.txt" >> "%LOG%" 2>&1 || (echo [ERROR] dependency install failed. See %LOG% & pause & exit /b 1)
echo [2/3] Dependencies installed. >> "%LOG%"

REM ===== 4) Launch server in a dedicated, persistent window (port 8866) =====
echo [3/3] Launching PaddleOCR service in a NEW window (port 8866)... >> "%LOG%"
echo [3/3] Launching PaddleOCR service in a NEW window (port 8866)...
echo         If it fails to start, that window stays open showing the error.
echo         (Model dir D:/paddleocr_home must exist; if missing, run copy_models.py once.)
start "PaddleOCR-8866" /D "%~dp0" cmd /k "call venv\Scripts\activate.bat && python ocr_server.py"
echo.
echo Setup complete. The server is running in the "PaddleOCR-8866" window.
echo Press any key to close THIS setup window (the server keeps running).
pause
exit /b 0
