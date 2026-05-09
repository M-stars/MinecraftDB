@echo off
echo === MinecraftDB 构建并部署 ===
echo.
echo [1/2] 构建 mod...
call .\gradlew.bat build
if %ERRORLEVEL% NEQ 0 (
    echo 构建失败！请检查错误信息。
    pause
    exit /b 1
)
echo.
echo [2/2] 复制到 PCL2 mods 目录...
copy /Y "build\libs\minecraftdb-1.0.0.jar" "D:\.minecraft\mods\"
echo.
echo 部署完成！现在可以在 PCL2 中启动游戏了。
pause
