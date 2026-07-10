@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo ========================================
echo   NLS 原版指纹批量生成器
echo   扫描 pcap_data\ 下所有PCAP文件
echo ========================================
echo.
python pcap_to_algo.py
echo.
pause
