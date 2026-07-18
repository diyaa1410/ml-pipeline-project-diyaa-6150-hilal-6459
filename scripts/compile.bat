@echo off
   REM Compiles the whole Java project into .\out - zero external dependencies needed.
   REM Passes each .java file directly to javac (quoted, to handle spaces in the
   REM path) instead of using an @argfile, which mangles backslashes on Windows.
   setlocal enabledelayedexpansion
   cd /d "%~dp0.."
   if exist out rmdir /s /q out
   mkdir out
   set FILELIST=
   for /r src\main\java %%f in (*.java) do set FILELIST=!FILELIST! "%%f"
   javac -d out !FILELIST!
   if errorlevel 1 (
     echo.
     echo COMPILE FAILED - see errors above.
     exit /b 1
   )
   echo Compiled successfully -^> out\