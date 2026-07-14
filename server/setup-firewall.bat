@echo off
chcp 65001 >nul
:: CallGuard 서버 방화벽 개방 스크립트 (최초 1회 실행)
:: 아이폰 핫스팟 등은 Windows가 '공용 네트워크'로 분류하므로
:: 공용 프로필을 포함해 TCP 8080(시그널링), 8081(모니터링)을 개방한다.

:: 관리자 권한 자동 상승
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo 관리자 권한이 필요합니다. UAC 창에서 [예]를 눌러주세요...
    powershell -Command "Start-Process '%~f0' -Verb RunAs"
    exit /b
)

echo 기존 CallGuard 방화벽 규칙 제거 중...
netsh advfirewall firewall delete rule name="CallGuard Server (8080-8081)" >nul 2>&1

echo 방화벽 인바운드 규칙 추가 중 (TCP 8080-8081, 모든 프로필)...
netsh advfirewall firewall add rule name="CallGuard Server (8080-8081)" dir=in action=allow protocol=TCP localport=8080-8081 profile=any

if %errorlevel% equ 0 (
    echo.
    echo ✅ 완료! 이제 아이폰 핫스팟 같은 공용 네트워크에서도 폰이 이 PC의 서버에 접속할 수 있습니다.
) else (
    echo.
    echo ❌ 규칙 추가에 실패했습니다. 관리자 권한으로 다시 실행해 주세요.
)
echo.
pause
