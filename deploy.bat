@echo off
rem 目标 mods 目录：按需修改这一行
set MODS_DIR=D:\.minecraft\mods

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
echo [2/2] 复制到 %MODS_DIR% ...
set FOUND=0
for %%f in ("build\libs\*.jar") do (
    copy /Y "%%f" "%MODS_DIR%\"
    set FOUND=1
)
if %FOUND% NEQ 1 (
    echo 未在 build\libs 下找到 jar，构建产物异常！
    pause
    exit /b 1
)
echo.
echo 部署完成！现在可以在 PCL2 中启动游戏了。
pause
