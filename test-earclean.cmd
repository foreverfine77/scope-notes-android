@echo off
cd /d "%~dp0"
python "%~dp0earclean_probe.py"
echo.
echo Results: earclean-result.txt
pause
