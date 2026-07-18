@echo off
cd /d "%~dp0.."
set N=%1
set C=%2
if "%N%"=="" set N=200
if "%C%"=="" set C=80
java -cp out com.mlpipeline.loadtest.LoadTester %N% %C%
