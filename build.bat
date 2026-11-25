@echo off
rem Compile everything into out\ and run the test suite (requires JDK 17+).
if exist out rmdir /s /q out
dir /s /b src\*.java > sources.txt
javac -Xlint:all -d out @sources.txt || exit /b 1
del sources.txt
java -cp out com.ziad.bank.BankTests
