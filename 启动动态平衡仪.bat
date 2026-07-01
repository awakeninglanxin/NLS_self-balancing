@echo off
chcp 65001 >nul
cd /d "D:\AAA我的文件
ls_dynamic_balancer"
echo ========================================
echo   NLS Self-Balancing — REALTIME
echo   http://localhost:8080
echo ========================================
python balancer_web.py
pause
